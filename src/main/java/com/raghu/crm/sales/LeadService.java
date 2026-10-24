package com.raghu.crm.sales;

import com.raghu.crm.customer.Customer;
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
import java.util.Currency;
import java.util.List;

@Service
public class LeadService {

	private final ShardRouter router;
	private final SnowflakeIdGenerator ids;
	private final SalesRepresentativeService representatives;
	private final MeterRegistry metrics;

	public LeadService(ShardRouter router, SnowflakeIdGenerator ids, SalesRepresentativeService representatives, MeterRegistry metrics) {
		this.router = router;
		this.ids = ids;
		this.representatives = representatives;
		this.metrics = metrics;
	}

	public void initializeIndexes(MongoShardRegistry shards) {
		for (String shard : shards.shardIds()) {
			shards.template(shard).indexOps(Lead.class).createIndex(new Index()
				.on("customerId", Sort.Direction.ASC).on("_id", Sort.Direction.ASC).named("leads_by_customer"));
		}
	}

	public Lead create(String customerId, CreateLeadRequest request) {
		MongoTemplate template = router.templateFor(customerId);
		if (!template.exists(Query.query(Criteria.where("_id").is(customerId)), Customer.class)) {
			throw new SalesException(HttpStatus.NOT_FOUND, "Customer was not found");
		}
		String id = Long.toString(ids.nextId());
		String representative = representatives.assign(id, request.representativeId());
		Instant now = now();
		Lead lead = new Lead(id, customerId, request.title().strip(), clean(request.source()), clean(request.notes()),
			LeadStatus.NEW, representative, null, now, now, 0);
		template.insert(lead);
		metrics.counter("crm.sales.created", "entity", "lead").increment();
		return lead;
	}

	public Lead get(String customerId, String leadId) {
		Lead lead = router.templateFor(customerId).findOne(key(customerId, leadId), Lead.class);
		if (lead == null) throw new SalesException(HttpStatus.NOT_FOUND, "Lead was not found for this customer");
		return lead;
	}

	public List<Lead> list(String customerId, String afterId, int limit) {
		if (limit < 1 || limit > 50) throw new IllegalArgumentException("Lead list limit must be 1–50");
		Criteria criteria = Criteria.where("customerId").is(customerId);
		if (afterId != null) criteria.and("_id").gt(afterId);
		return router.templateFor(customerId).find(Query.query(criteria).with(Sort.by("_id"))
			.limit(limit).withHint("leads_by_customer").maxTime(Duration.ofSeconds(2)), Lead.class);
	}

	public Lead changeStatus(String customerId, String leadId, ChangeLeadStatusRequest request) {
		Lead current = expected(customerId, leadId, request.version());
		if (!current.status().canChangeTo(request.status())) {
			throw new SalesException(HttpStatus.CONFLICT, "Lead status transition is not allowed; qualify through the opportunity endpoint");
		}
		return change(current, new Update().set("status", request.status()), "lead_status");
	}

	public Lead reassign(String customerId, String leadId, AssignRepresentativeRequest request) {
		Lead current = expected(customerId, leadId, request.version());
		if (current.status() == LeadStatus.LOST || (current.opportunity() != null && current.opportunity().stage().closed())) {
			throw new SalesException(HttpStatus.CONFLICT, "Closed sales records cannot be reassigned");
		}
		String representativeId = representatives.assign(leadId, request.representativeId());
		return change(current, new Update().set("representativeId", representativeId), "assignment");
	}

	public Lead qualify(String customerId, String leadId, CreateOpportunityRequest request) {
		Lead current = expected(customerId, leadId, request.version());
		if (!current.status().canQualify() || current.opportunity() != null) {
			throw new SalesException(HttpStatus.CONFLICT, "Only an unqualified, active lead can create an opportunity");
		}
		Currency.getInstance(request.currency());
		Instant now = now();
		Opportunity opportunity = new Opportunity(Long.toString(ids.nextId()), request.amount(), request.currency(), OpportunityStage.OPEN, now, now);
		// Qualification and opportunity creation share one document, making conversion one atomic write.
		return change(current, new Update().set("status", LeadStatus.QUALIFIED).set("opportunity", opportunity), "qualification");
	}

	public Lead changeOpportunityStage(String customerId, String leadId, ChangeOpportunityStageRequest request) {
		Lead current = expected(customerId, leadId, request.version());
		if (current.opportunity() == null) throw new SalesException(HttpStatus.NOT_FOUND, "This lead has no opportunity");
		if (!current.opportunity().stage().canChangeTo(request.stage())) {
			throw new SalesException(HttpStatus.CONFLICT, "Opportunity stages move forward; WON and LOST are terminal");
		}
		return change(current, new Update().set("opportunity.stage", request.stage()).set("opportunity.updatedAt", now()), "opportunity_stage");
	}

	private Lead expected(String customerId, String leadId, Long version) {
		Lead current = get(customerId, leadId);
		if (version == null || current.version() != version) {
			throw new SalesException(HttpStatus.CONFLICT, "Sales record changed; reload and use its current version");
		}
		return current;
	}

	private Lead change(Lead current, Update update, String action) {
		Timer.Sample sample = Timer.start(metrics);
		try {
			Query query = key(current.customerId(), current.id());
			query.addCriteria(Criteria.where("version").is(current.version()));
			Lead changed = router.templateFor(current.customerId()).findAndModify(query,
				update.set("updatedAt", now()).inc("version", 1), FindAndModifyOptions.options().returnNew(true), Lead.class);
			if (changed == null) {
				metrics.counter("crm.sales.changes", "action", action, "outcome", "conflict").increment();
				throw new SalesException(HttpStatus.CONFLICT, "Sales record changed concurrently; reload and retry");
			}
			metrics.counter("crm.sales.changes", "action", action, "outcome", "success").increment();
			return changed;
		}
		finally {
			sample.stop(metrics.timer("crm.sales.write", "action", action));
		}
	}

	private static Query key(String customerId, String leadId) {
		return Query.query(Criteria.where("_id").is(leadId).and("customerId").is(customerId));
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS);
	}

	private static String clean(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
