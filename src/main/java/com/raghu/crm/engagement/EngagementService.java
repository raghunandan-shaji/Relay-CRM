package com.raghu.crm.engagement;

import com.raghu.crm.customer.Customer;
import com.raghu.crm.sales.LeadService;
import com.raghu.crm.sales.SalesException;
import com.raghu.crm.sales.SalesRepresentativeService;
import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class EngagementService {

	private final ShardRouter router;
	private final SnowflakeIdGenerator ids;
	private final LeadService leads;
	private final SalesRepresentativeService representatives;
	private final MeterRegistry metrics;

	public EngagementService(ShardRouter router, SnowflakeIdGenerator ids, LeadService leads,
			SalesRepresentativeService representatives, MeterRegistry metrics) {
		this.router = router;
		this.ids = ids;
		this.leads = leads;
		this.representatives = representatives;
		this.metrics = metrics;
	}

	public void initializeIndexes(MongoShardRegistry shards) {
		for (String shard : shards.shardIds()) {
			MongoTemplate template = shards.template(shard);
			template.indexOps(Interaction.class).createIndex(new Index().on("customerId", Sort.Direction.ASC)
				.on("occurredAt", Sort.Direction.DESC).on("_id", Sort.Direction.DESC).named("customer_interaction_timeline"));
			template.indexOps(SalesActivity.class).createIndex(new Index().on("customerId", Sort.Direction.ASC)
				.on("_id", Sort.Direction.ASC).named("customer_activities"));
			template.indexOps(SalesActivity.class).createIndex(new Index().on("customerId", Sort.Direction.ASC)
				.on("status", Sort.Direction.ASC).on("_id", Sort.Direction.ASC).named("customer_activities_by_status"));
		}
	}

	public Interaction record(String customerId, CreateInteractionRequest request, String actorId) {
		MongoTemplate template = checkedCustomer(customerId, request.leadId());
		Instant now = now();
		Interaction interaction = new Interaction(Long.toString(ids.nextId()), customerId, request.leadId(), request.type(),
			request.summary().strip(), clean(request.details()), request.occurredAt() == null ? now : millis(request.occurredAt()), now, actorId);
		template.insert(interaction);
		metrics.counter("crm.engagement.created", "entity", "interaction").increment();
		return interaction;
	}

	public Interaction interaction(String customerId, String id) {
		Interaction found = router.templateFor(customerId).findOne(key(customerId, id), Interaction.class);
		if (found == null) throw new SalesException(HttpStatus.NOT_FOUND, "Interaction was not found for this customer");
		return found;
	}

	public List<Interaction> timeline(String customerId, Instant beforeAt, String beforeId, int limit) {
		checkLimit(limit);
		if ((beforeAt == null) != (beforeId == null)) {
			throw new IllegalArgumentException("Timeline cursor requires both beforeAt and beforeId from the last result");
		}
		Criteria criteria = Criteria.where("customerId").is(customerId);
		if (beforeAt != null) {
			criteria.andOperator(new Criteria().orOperator(Criteria.where("occurredAt").lt(millis(beforeAt)),
				Criteria.where("occurredAt").is(millis(beforeAt)).and("_id").lt(beforeId)));
		}
		Timer.Sample sample = Timer.start(metrics);
		try {
			return router.templateFor(customerId).find(Query.query(criteria)
				.with(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("_id"))).limit(limit)
				.withHint("customer_interaction_timeline").maxTime(Duration.ofSeconds(2)), Interaction.class);
		}
		finally {
			sample.stop(metrics.timer("crm.interaction_timeline.lookup"));
		}
	}

	public SalesActivity createActivity(String customerId, CreateActivityRequest request, String actorId) {
		MongoTemplate template = checkedCustomer(customerId, request.leadId());
		String id = Long.toString(ids.nextId());
		String representative = representatives.assign(id, request.representativeId());
		Instant now = now();
		SalesActivity activity = new SalesActivity(id, customerId, request.leadId(), request.type(), request.title().strip(),
			clean(request.description()), representative, millis(request.dueAt()), ActivityStatus.OPEN,
			now, actorId, now, null, null, null, 0);
		template.insert(activity);
		metrics.counter("crm.engagement.created", "entity", "activity").increment();
		return activity;
	}

	public SalesActivity activity(String customerId, String id) {
		SalesActivity found = router.templateFor(customerId).findOne(key(customerId, id), SalesActivity.class);
		if (found == null) throw new SalesException(HttpStatus.NOT_FOUND, "Activity was not found for this customer");
		return found;
	}

	public List<SalesActivity> activities(String customerId, ActivityStatus status, String afterId, int limit) {
		checkLimit(limit);
		Criteria criteria = Criteria.where("customerId").is(customerId);
		if (status != null) criteria.and("status").is(status);
		if (afterId != null) criteria.and("_id").gt(afterId);
		return router.templateFor(customerId).find(Query.query(criteria).with(Sort.by("_id")).limit(limit)
			.withHint(status == null ? "customer_activities" : "customer_activities_by_status")
			.maxTime(Duration.ofSeconds(2)), SalesActivity.class);
	}

	public SalesActivity changeActivityStatus(String customerId, String id, ChangeActivityStatusRequest request, String actorId) {
		if (request.status() == ActivityStatus.OPEN) {
			throw new SalesException(HttpStatus.CONFLICT, "Activities can be completed or cancelled; closed tasks cannot reopen");
		}
		Query query = key(customerId, id);
		query.addCriteria(Criteria.where("version").is(request.version()).and("status").is(ActivityStatus.OPEN));
		Instant now = now();
		Timer.Sample sample = Timer.start(metrics);
		try {
			SalesActivity changed = router.templateFor(customerId).findAndModify(query,
				new Update().set("status", request.status()).set("outcome", clean(request.outcome()))
					.set("closedAt", now).set("closedBy", actorId).set("updatedAt", now).inc("version", 1),
				FindAndModifyOptions.options().returnNew(true), SalesActivity.class);
			if (changed == null) {
				activity(customerId, id);
				metrics.counter("crm.activity.changes", "outcome", "conflict").increment();
				throw new SalesException(HttpStatus.CONFLICT, "Activity is closed or its version changed; reload before retrying");
			}
			metrics.counter("crm.activity.changes", "outcome", "success").increment();
			return changed;
		}
		finally {
			sample.stop(metrics.timer("crm.activity.write"));
		}
	}

	private MongoTemplate checkedCustomer(String customerId, String leadId) {
		MongoTemplate template = router.templateFor(customerId);
		if (!template.exists(Query.query(Criteria.where("_id").is(customerId)), Customer.class)) {
			throw new SalesException(HttpStatus.NOT_FOUND, "Customer was not found");
		}
		if (leadId != null) leads.get(customerId, leadId);
		return template;
	}

	private static Query key(String customerId, String id) {
		return Query.query(Criteria.where("_id").is(id).and("customerId").is(customerId));
	}

	private static void checkLimit(int limit) {
		if (limit < 1 || limit > 50) throw new IllegalArgumentException("Result limit must be 1–50");
	}

	private static Instant now() {
		return millis(Instant.now());
	}

	private static Instant millis(Instant value) {
		return value.truncatedTo(ChronoUnit.MILLIS);
	}

	private static String clean(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
