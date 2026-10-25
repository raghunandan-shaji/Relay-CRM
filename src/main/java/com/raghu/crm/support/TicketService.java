package com.raghu.crm.support;

import com.raghu.crm.customer.Customer;
import com.raghu.crm.notifications.CustomerEvent;
import com.raghu.crm.notifications.OutboxService;
import com.raghu.crm.sales.SalesException;
import com.raghu.crm.sales.SalesRepresentativeService;
import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class TicketService {
	private final ShardRouter router;
	private final MongoShardRegistry shards;
	private final SnowflakeIdGenerator ids;
	private final SalesRepresentativeService representatives;
	private final OutboxService outbox;
	public TicketService(ShardRouter router, MongoShardRegistry shards, SnowflakeIdGenerator ids,
			SalesRepresentativeService representatives, OutboxService outbox) {
		this.router = router; this.shards = shards; this.ids = ids;
		this.representatives = representatives; this.outbox = outbox;
	}

	public void initializeIndexes() {
		for (String shard : shards.shardIds()) {
			var indexes = shards.template(shard).indexOps(SupportTicket.class);
			indexes.createIndex(new Index().on("customerId", Sort.Direction.ASC).on("_id", Sort.Direction.ASC).named("customer_tickets"));
			indexes.createIndex(new Index().on("status", Sort.Direction.ASC).on("_id", Sort.Direction.ASC).named("ticket_queue"));
		}
	}

	public SupportTicket create(String customerId, CreateTicketRequest request, String actorId) {
		if (!router.templateFor(customerId).exists(Query.query(Criteria.where("_id").is(customerId)), Customer.class))
			throw new SalesException(HttpStatus.NOT_FOUND, "Customer was not found");
		String id = Long.toString(ids.nextId());
		Instant now = now();
		CustomerEvent event = outbox.event(customerId, "TICKET", id, "CREATED", request.subject().strip(), request.message().strip(), actorId, true);
		SupportTicket ticket = new SupportTicket(id, customerId, request.subject().strip(), request.priority(), TicketStatus.OPEN,
			representatives.assign(id, null), now, now, actorId, 0, event);
		router.templateFor(customerId).insert(ticket);
		outbox.tryDelivery(customerId, "tickets", id);
		return ticket;
	}

	public SupportTicket get(String customerId, String id) {
		SupportTicket found = router.templateFor(customerId).findOne(key(customerId, id), SupportTicket.class);
		if (found == null) throw new SalesException(HttpStatus.NOT_FOUND, "Ticket was not found for this customer");
		return found;
	}

	public List<SupportTicket> list(String customerId, TicketStatus status, String afterId, int limit) {
		checkLimit(limit);
		Criteria criteria = customerId == null ? new Criteria() : Criteria.where("customerId").is(customerId);
		if (status != null) criteria.and("status").is(status);
		if (afterId != null) criteria.and("_id").gt(afterId);
		Query query = Query.query(criteria).with(Sort.by("_id")).limit(limit).maxTime(Duration.ofSeconds(2));
		if (customerId != null) return router.templateFor(customerId).find(query.withHint("customer_tickets"), SupportTicket.class);
		if (status != null) query.withHint("ticket_queue");
		List<SupportTicket> all = new ArrayList<>();
		for (String shard : shards.shardIds()) all.addAll(shards.template(shard).find(query, SupportTicket.class));
		return all.stream().sorted(Comparator.comparing(SupportTicket::id)).limit(limit).toList();
	}

	public List<CustomerEvent> messages(String customerId, String id, String afterId, int limit) {
		checkLimit(limit);
		get(customerId, id);
		outbox.tryDelivery(customerId, "tickets", id);
		Criteria criteria = Criteria.where("customerId").is(customerId).and("sourceType").is("TICKET").and("sourceId").is(id);
		if (afterId != null) criteria.and("_id").gt(afterId);
		return router.templateFor(customerId).find(Query.query(criteria).with(Sort.by("_id")).limit(limit)
			.withHint("source_events").maxTime(Duration.ofSeconds(2)), CustomerEvent.class);
	}

	public SupportTicket reply(String customerId, String id, TicketReplyRequest request, String actorId, boolean staff) {
		SupportTicket ticket = writable(customerId, id, request.version());
		CustomerEvent event = outbox.event(customerId, "TICKET", id, "REPLIED", ticket.subject(), request.message().strip(), actorId, staff);
		return change(ticket, new Update().set("pendingEvent", event));
	}

	public SupportTicket update(String customerId, String id, ChangeTicketRequest request, String actorId) {
		SupportTicket ticket = writable(customerId, id, request.version());
		if (request.status() == null && request.representativeId() == null) throw new IllegalArgumentException("Choose a status or representative");
		Update update = new Update();
		if (request.status() != null) {
			if (!ticket.status().canChangeTo(request.status())) throw new SalesException(HttpStatus.CONFLICT, "Ticket status must move forward");
			update.set("status", request.status());
		}
		if (request.representativeId() != null) update.set("representativeId", representatives.assign(id, request.representativeId()));
		String detail = request.status() == null ? "Ticket representative changed" : "Ticket status changed to " + request.status();
		return change(ticket, update.set("pendingEvent", outbox.event(customerId, "TICKET", id, "UPDATED", ticket.subject(), detail, actorId, true)));
	}

	private SupportTicket writable(String customerId, String id, long version) {
		outbox.flush(customerId, "tickets", id);
		SupportTicket ticket = get(customerId, id);
		if (ticket.version() != version || ticket.status() == TicketStatus.CLOSED)
			throw new SalesException(HttpStatus.CONFLICT, "Ticket is closed or its version changed; reload before retrying");
		return ticket;
	}

	private SupportTicket change(SupportTicket ticket, Update update) {
		Query query = key(ticket.customerId(), ticket.id()).addCriteria(Criteria.where("version").is(ticket.version()).and("pendingEvent").is(null));
		SupportTicket changed = router.templateFor(ticket.customerId()).findAndModify(query,
			update.set("updatedAt", now()).inc("version", 1), FindAndModifyOptions.options().returnNew(true), SupportTicket.class);
		if (changed == null) throw new SalesException(HttpStatus.CONFLICT, "Ticket changed; reload before retrying");
		outbox.tryDelivery(ticket.customerId(), "tickets", ticket.id());
		return changed;
	}

	private static Query key(String customerId, String id) { return Query.query(Criteria.where("_id").is(id).and("customerId").is(customerId)); }
	private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
	private static void checkLimit(int limit) { if (limit < 1 || limit > 50) throw new IllegalArgumentException("Result limit must be 1–50"); }
}
