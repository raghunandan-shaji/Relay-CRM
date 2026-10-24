package com.raghu.crm.customer;

import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class CustomerWorkflowIntegrationTests {

	@Autowired
	private CustomerService customerService;

	@Autowired
	private SnowflakeIdGenerator idGenerator;

	@Autowired
	private ShardRouter shardRouter;

	@Autowired
	private MongoShardRegistry shardRegistry;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private CustomerProfileCache cache;

	@Test
	void createsReadsAndUpdatesCustomerWithUniquenessAndVersionChecks() {
		String email = "workflow-" + UUID.randomUUID() + "@example.com";
		CustomerResponse created = null;
		try {
			CreateCustomerRequest createRequest = new CreateCustomerRequest(
				"Raghu Demo", email, "secure-password", "9876543210", "CRM Lab");
			created = customerService.create(createRequest);

			assertEquals(created, customerService.get(created.id()));
			assertEquals(shardRouter.shardIdFor(created.id()), created.shardId());
			assertThrows(DuplicateEmailException.class, () -> customerService.create(createRequest));

			CustomerResponse updated = customerService.update(
				created.id(),
				new UpdateCustomerRequest("Raghu Updated", "9999999999", "System Design Lab", 0));
			assertEquals(1, updated.version());
			assertEquals("Raghu Updated", updated.fullName());
			assertThrows(ProfileVersionConflictException.class, () -> customerService.update(
				updated.id(),
				new UpdateCustomerRequest("Stale Write", null, null, 0)));
		}
		finally {
			if (created != null) {
				shardRouter.templateFor(created.id()).remove(
					Query.query(Criteria.where("_id").is(created.id())), Customer.class);
				redis.delete(cache.key(created.id()));
			}
			redis.delete(CustomerService.emailKey(email));
		}
	}

	@Test
	void physicallyDistributesCustomersAcrossAllThreeShards() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		counts.put("shard-0", 0);
		counts.put("shard-1", 0);
		counts.put("shard-2", 0);
		List<Customer> customers = new ArrayList<>();

		try {
			for (int i = 0; i < 300; i++) {
				String id = Long.toString(idGenerator.nextId());
				String shardId = shardRouter.shardIdFor(id);
				Instant now = Instant.now();
				Customer customer = new Customer(
					id, "Distribution Test " + i, "distribution-" + id + "@example.com",
					"not-used-in-this-routing-test", null, null, now, now, 0, "distribution test " + i);
				shardRouter.templateFor(id).insert(customer);
				customers.add(customer);
				counts.compute(shardId, (ignored, count) -> count + 1);
			}

			assertEquals(300, counts.values().stream().mapToInt(Integer::intValue).sum());
			counts.values().forEach(count -> assertTrue(count >= 60, "Unbalanced physical distribution: " + counts));
			System.out.println("Physical customer documents by shard: " + counts);
		}
		finally {
			for (Customer customer : customers) {
				shardRegistry.template(shardRouter.shardIdFor(customer.id())).remove(customer);
			}
		}
	}
}
