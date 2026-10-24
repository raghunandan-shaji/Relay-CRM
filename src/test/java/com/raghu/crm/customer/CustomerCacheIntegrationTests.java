package com.raghu.crm.customer;

import com.raghu.crm.systemdesign.sharding.ShardRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class CustomerCacheIntegrationTests {

	@Autowired private CustomerService customers;
	@Autowired private CustomerProfileCache cache;
	@Autowired private StringRedisTemplate redis;
	@Autowired private ShardRouter router;
	@Autowired private JsonMapper mapper;

	@Test
	void sharedCacheServesProfilesAndRejectsLateOlderWrites() {
		CustomerResponse created = customers.create(new CreateCustomerRequest("Cache Test", "cache-" + UUID.randomUUID() + "@example.com",
			"password-123", null, null));
		SimpleMeterRegistry metrics = new SimpleMeterRegistry();
		try {
			assertEquals("mongodb", customers.read(created.id()).source());
			assertEquals("redis", customers.read(created.id()).source());
			String json = (String) redis.opsForHash().get(cache.key(created.id()), "data");
			assertFalse(json.contains("password"));
			assertTrue(redis.getExpire(cache.key(created.id()), TimeUnit.SECONDS) > 0);
			CustomerResponse updated = customers.update(created.id(), new UpdateCustomerRequest("New Name", null, null, 0));
			cache.put(created);
			assertEquals(updated, customers.get(created.id()));
			CustomerProfileCache otherInstance = new CustomerProfileCache(redis, mapper, metrics, "crm:profile:v1:", Duration.ofSeconds(60));
			assertEquals(updated, otherInstance.get(created.id()));
			System.out.println("Profile cache: first read MongoDB, next read Redis; updated version shared across cache instances; delayed version-0 write cannot replace version 1; no password stored");
		}
		finally {
			router.templateFor(created.id()).remove(Query.query(Criteria.where("_id").is(created.id())), Customer.class);
			redis.delete(java.util.List.of("crm:customer-email:" + created.email(), cache.key(created.id())));
			metrics.close();
		}
	}

	@Test
	void expiredCacheEntryBecomesMiss() throws Exception {
		String prefix = "crm:test:profile:" + UUID.randomUUID() + ":";
		SimpleMeterRegistry metrics = new SimpleMeterRegistry();
		CustomerProfileCache shortCache = new CustomerProfileCache(redis, mapper, metrics, prefix, Duration.ofSeconds(1));
		try {
			Instant now = Instant.now();
			CustomerResponse profile = new CustomerResponse("123", "shard-0", "Demo", "demo@example.com", null, null, now, now, 0);
			shortCache.put(profile);
			assertEquals(profile, shortCache.get("123"));
			Thread.sleep(1100);
			assertNull(shortCache.get("123"));
		}
		finally {
			redis.delete(shortCache.key("123"));
			metrics.close();
		}
	}
}
