package com.raghu.crm.customer;

import com.mongodb.ExplainVerbosity;
import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class CustomerSearchIntegrationTests {

	@Autowired private CustomerSearchService search;
	@Autowired private CustomerService customers;
	@Autowired private CustomerProfileCache cache;
	@Autowired private StringRedisTemplate redis;
	@Autowired private ShardRouter router;
	@Autowired private MongoShardRegistry shards;
	@Autowired private SnowflakeIdGenerator ids;
	private final List<Customer> inserted = new ArrayList<>();
	private final String prefix = "q" + UUID.randomUUID().toString().substring(0, 8) + " ";

	@BeforeEach
	void ensureIndexes() {
		search.initialize();
	}

	@AfterEach
	void cleanup() {
		for (String shard : shards.shardIds()) {
			List<String> localIds = inserted.stream().map(Customer::id).filter(id -> router.shardIdFor(id).equals(shard)).toList();
			if (!localIds.isEmpty()) shards.template(shard).remove(Query.query(Criteria.where("_id").in(localIds)), Customer.class);
		}
		if (!inserted.isEmpty()) redis.delete(inserted.stream().map(customer -> cache.key(customer.id())).toList());
	}

	@Test
	void globallySortedBoundedSearchAndSuggestionsMatchReference() {
		for (String name : List.of("Rahul", "Raghu", "Raman", "Rakesh", "RAHUL", "Ramya", "Ravi", "Radhika", "Elsewhere")) {
			insert(prefix + name);
		}
		List<String> expected = inserted.stream().filter(customer -> CustomerNames.normalize(customer.fullName()).startsWith(prefix + "ra"))
			.sorted(Comparator.comparing(Customer::nameKey, CustomerNames::compare).thenComparing(Customer::id))
			.limit(5).map(Customer::id).toList();
		assertEquals(expected, search.search((prefix + "RA").toUpperCase(java.util.Locale.ROOT), 5).stream().map(CustomerSummary::id).toList());
		assertEquals(expected, search.suggestions(prefix + "ra", 5).stream().map(CustomerSuggestion::id).toList());
		assertTrue(search.search(prefix + ".*", 5).isEmpty());
		System.out.println("Search/autocomplete: expected top 5 of 8 matching fixtures returned exactly; literal special characters and case normalization verified");
	}

	@Test
	void backfillsLegacyNamesAndRenameUpdatesIndexWithoutChangingIds() {
		Customer legacy = insert(prefix + "Legacy");
		router.templateFor(legacy.id()).updateFirst(Query.query(Criteria.where("_id").is(legacy.id())), new Update().unset("nameKey"), Customer.class);
		search.initialize();
		assertEquals(legacy.nameKey(), router.templateFor(legacy.id()).findById(legacy.id(), Customer.class).nameKey());
		search.initialize();
		CustomerResponse updated = customers.update(legacy.id(), new UpdateCustomerRequest(prefix + "Renamed", null, null, 0));
		assertEquals(legacy.id(), updated.id());
		assertTrue(search.search(prefix + "legacy", 5).isEmpty());
		assertEquals(legacy.id(), search.search(prefix + "renamed", 5).getFirst().id());
	}

	@Test
	void explainShowsIndexAvoidsCollectionScanForSelectivePrefix() {
		for (int i = 0; i < 600; i++) {
			insert(prefix + (i % 50 == 0 ? "Target " : "Other ") + String.format(java.util.Locale.ROOT, "%04d", i));
		}
		Set<String> occupied = new HashSet<>();
		inserted.forEach(customer -> occupied.add(router.shardIdFor(customer.id())));
		assertEquals(3, occupied.size());
		Query query = CustomerSearchService.prefixQuery(prefix + "target", 10);
		long indexedExamined = 0;
		long baselineExamined = 0;
		for (String shard : shards.shardIds()) {
			var collection = shards.template(shard).getCollection("customers");
			Document indexed = collection.find(query.getQueryObject()).projection(query.getFieldsObject())
				.sort(query.getSortObject()).limit(10).hintString(CustomerSearchService.INDEX_NAME)
				.maxTime(2, TimeUnit.SECONDS).explain(ExplainVerbosity.EXECUTION_STATS);
			Document baseline = collection.find(query.getQueryObject()).projection(query.getFieldsObject())
				.sort(query.getSortObject()).limit(10).hint(new Document("$natural", 1))
				.maxTime(2, TimeUnit.SECONDS).explain(ExplainVerbosity.EXECUTION_STATS);
			indexedExamined += indexed.get("executionStats", Document.class).get("totalDocsExamined", Number.class).longValue();
			baselineExamined += baseline.get("executionStats", Document.class).get("totalDocsExamined", Number.class).longValue();
		}
		assertTrue(indexedExamined <= 12, "Indexed query examined " + indexedExamined + " documents");
		assertTrue(baselineExamined >= 600);
		assertEquals(10, search.search(prefix + "target", 10).size());
		System.out.printf("Search explain over 600 fixtures on 3 shards: indexed documents examined=%d, forced collection-scan baseline=%d%n", indexedExamined, baselineExamined);
	}

	private Customer insert(String name) {
		String id = Long.toString(ids.nextId());
		Instant now = Instant.now();
		Customer customer = new Customer(id, name, "search-" + id + "@example.com", "test-fixture-no-login",
			null, "CRM Test", now, now, 0, CustomerNames.normalize(name));
		router.templateFor(id).insert(customer);
		inserted.add(customer);
		return customer;
	}
}
