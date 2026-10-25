package com.raghu.crm.customer;

import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Collation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class CustomerSearchService {

	static final String INDEX_NAME = "customer_name_prefix";
	private final MongoShardRegistry shards;
	private final MeterRegistry metrics;

	public CustomerSearchService(MongoShardRegistry shards, MeterRegistry metrics) {
		this.shards = shards;
		this.metrics = metrics;
	}

	public void initialize() {
		for (String shard : shards.shardIds()) {
			MongoTemplate template = shards.template(shard);
			template.indexOps(Customer.class).createIndex(new Index()
				.on("nameKey", Sort.Direction.ASC).on("_id", Sort.Direction.ASC)
				.named(INDEX_NAME).collation(Collation.simple()));
			// shortcut: one-time legacy backfill runs at startup; move large migrations to an offline job.
			try (var legacy = template.stream(Query.query(Criteria.where("nameKey").is(null)).cursorBatchSize(200), Customer.class)) {
				legacy.forEach(customer -> template.updateFirst(
					Query.query(Criteria.where("_id").is(customer.id()).and("nameKey").is(null).and("fullName").is(customer.fullName())),
					new Update().set("nameKey", CustomerNames.normalize(customer.fullName())), Customer.class));
			}
		}
	}

	public List<CustomerSummary> search(String prefix, int limit) {
		Timer.Sample sample = Timer.start(metrics);
		try {
			List<CustomerSummary> matches = new ArrayList<>();
			Query query = prefixQuery(prefix, limit);
			for (String shard : shards.shardIds()) {
				for (CustomerSummary customer : shards.template(shard).find(query, CustomerSummary.class, "customers")) {
					matches.add(new CustomerSummary(customer.id(), customer.fullName(), customer.email(), customer.company(), shard));
				}
			}
			matches.sort(Comparator.comparing((CustomerSummary customer) -> CustomerNames.normalize(customer.fullName()), CustomerNames::compare)
				.thenComparing(CustomerSummary::id));
			metrics.counter("crm.customer_search.requests", "outcome", "success").increment();
			return matches.stream().limit(limit).toList();
		}
		catch (RuntimeException exception) {
			metrics.counter("crm.customer_search.requests", "outcome", "error").increment();
			throw exception;
		}
		finally {
			sample.stop(metrics.timer("crm.customer_search.lookup"));
		}
	}

	public List<CustomerSuggestion> suggestions(String prefix, int limit) {
		Timer.Sample sample = Timer.start(metrics);
		try {
			return search(prefix, limit).stream()
				.map(customer -> new CustomerSuggestion(customer.id(), customer.fullName())).toList();
		}
		finally {
			sample.stop(metrics.timer("crm.customer_autocomplete.lookup"));
		}
	}

	public List<CustomerSummary> directory(String afterId, int limit) {
		if (limit < 1 || limit > 50) throw new IllegalArgumentException("Directory limit must be 1–50");
		Criteria criteria = afterId == null ? new Criteria() : Criteria.where("_id").gt(afterId);
		Query query = Query.query(criteria).with(Sort.by("_id")).limit(limit)
			.withHint("_id_").maxTime(Duration.ofSeconds(2));
		query.fields().include("fullName", "email", "company");
		List<CustomerSummary> accounts = new ArrayList<>();
		for (String shard : shards.shardIds()) {
			for (CustomerSummary customer : shards.template(shard).find(query, CustomerSummary.class, "customers")) {
				accounts.add(new CustomerSummary(customer.id(), customer.fullName(), customer.email(), customer.company(), shard));
			}
		}
		return accounts.stream().sorted(Comparator.comparing(CustomerSummary::id)).limit(limit).toList();
	}

	static Query prefixQuery(String prefix, int limit) {
		String normalized = CustomerNames.normalize(prefix);
		if (normalized.codePointCount(0, normalized.length()) < 2 || normalized.length() > 120 || limit < 1 || limit > 50) {
			throw new IllegalArgumentException("Search prefix must contain 2–120 characters; limit must be 1–50");
		}
		Criteria range = Criteria.where("nameKey").gte(normalized);
		String upper = CustomerNames.upperBound(normalized);
		if (upper != null) range.lt(upper);
		Query query = Query.query(range).with(Sort.by("nameKey", "_id")).limit(limit)
			.withHint(INDEX_NAME).collation(Collation.simple()).maxTime(Duration.ofSeconds(2));
		query.fields().include("fullName", "email", "company");
		return query;
	}
}
