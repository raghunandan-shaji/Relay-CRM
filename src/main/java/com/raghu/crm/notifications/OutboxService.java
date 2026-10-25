package com.raghu.crm.notifications;

import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class OutboxService {
	private static final List<String> SOURCES = List.of("tickets", "activities");
	private final ShardRouter router;
	private final MongoShardRegistry shards;
	private final SnowflakeIdGenerator ids;
	private final MeterRegistry metrics;
	private final boolean enabled;

	public OutboxService(ShardRouter router, MongoShardRegistry shards, SnowflakeIdGenerator ids,
			MeterRegistry metrics, @Value("${crm.outbox.enabled:true}") boolean enabled) {
		this.router = router;
		this.shards = shards;
		this.ids = ids;
		this.metrics = metrics;
		this.enabled = enabled;
	}

	public CustomerEvent event(String customerId, String sourceType, String sourceId, String kind,
			String title, String body, String actorId, boolean notifyCustomer) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		return new CustomerEvent(Long.toString(ids.nextId()), customerId, sourceType, sourceId, kind,
			title, body, actorId, now, notifyCustomer, notifyCustomer ? null : now);
	}

	public void initializeIndexes() {
		for (String shard : shards.shardIds()) {
			MongoTemplate template = shards.template(shard);
			for (String source : SOURCES) template.indexOps(source).createIndex(
				new Index().on("pendingEvent._id", Sort.Direction.ASC).sparse().named("pending_events"));
			template.indexOps(CustomerEvent.class).createIndex(new Index().on("customerId", Sort.Direction.ASC)
				.on("notifyCustomer", Sort.Direction.ASC).on("_id", Sort.Direction.DESC).named("customer_inbox"));
			template.indexOps(CustomerEvent.class).createIndex(new Index().on("customerId", Sort.Direction.ASC)
				.on("sourceType", Sort.Direction.ASC).on("sourceId", Sort.Direction.ASC).on("_id", Sort.Direction.ASC).named("source_events"));
		}
	}

	public void flush(String customerId, String collection, String id) {
		deliver(router.templateFor(customerId), collection, id);
	}

	public void tryDelivery(String customerId, String collection, String id) {
		try { flush(customerId, collection, id); }
		catch (DataAccessException exception) { metrics.counter("crm.outbox.delivery", "outcome", "retry").increment(); }
	}

	@Scheduled(fixedDelayString = "${crm.outbox.delay:2000}")
	public void recover() {
		if (!enabled) return;
		for (String shard : shards.shardIds()) {
			for (String source : SOURCES) {
				try {
					MongoTemplate template = shards.template(shard);
					List<Document> pending = template.find(Query.query(Criteria.where("pendingEvent._id").exists(true))
						.limit(50).withHint("pending_events"), Document.class, source);
					for (Document record : pending) deliver(template, source, record.getString("_id"));
				}
				catch (DataAccessException exception) { metrics.counter("crm.outbox.delivery", "outcome", "retry").increment(); }
			}
		}
	}

	private void deliver(MongoTemplate template, String collection, String id) {
		if (!SOURCES.contains(collection)) throw new IllegalArgumentException("Unsupported outbox source");
		Document source = template.findById(id, Document.class, collection);
		if (source == null || !(source.get("pendingEvent") instanceof Document pending)) return;
		CustomerEvent event = template.getConverter().read(CustomerEvent.class, pending);
		try { template.insert(event); }
		catch (DuplicateKeyException exception) {
			CustomerEvent existing = template.findById(event.id(), CustomerEvent.class);
			if (existing == null || !existing.customerId().equals(event.customerId())
				|| !existing.sourceId().equals(event.sourceId()) || !existing.kind().equals(event.kind())
				|| !java.util.Objects.equals(existing.body(), event.body())) {
				throw new DataIntegrityViolationException("Event ID collision", exception);
			}
		}
		// A competing worker may already have cleared this event and a writer queued the next one.
		template.updateFirst(Query.query(Criteria.where("_id").is(id).and("pendingEvent._id").is(event.id())),
			new Update().unset("pendingEvent"), collection);
		metrics.counter("crm.outbox.delivery", "outcome", "delivered").increment();
	}
}
