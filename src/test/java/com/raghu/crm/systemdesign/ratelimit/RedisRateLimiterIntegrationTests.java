package com.raghu.crm.systemdesign.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class RedisRateLimiterIntegrationTests {

	@Autowired private StringRedisTemplate redis;

	@Test
	void twoInstancesEnforceOneAtomicQuotaUnderConcurrency() throws Exception {
		String prefix = "crm:test:rate-limit:" + UUID.randomUUID() + ":";
		RateLimitProperties properties = new RateLimitProperties(prefix, 25, 5, 120, Duration.ofSeconds(60));
		SimpleMeterRegistry metrics = new SimpleMeterRegistry();
		try (var executor = Executors.newFixedThreadPool(20)) {
			RedisRateLimiter first = new RedisRateLimiter(redis, metrics, properties);
			RedisRateLimiter second = new RedisRateLimiter(redis, metrics, properties);
			List<Future<RedisRateLimiter.Decision>> futures = new ArrayList<>();
			for (int i = 0; i < 100; i++) {
				RedisRateLimiter limiter = i % 2 == 0 ? first : second;
				futures.add(executor.submit(() -> limiter.check(RedisRateLimiter.Policy.LOGIN, "shared-client", 25, properties.window())));
			}
			int allowed = 0;
			for (Future<RedisRateLimiter.Decision> future : futures) {
				RedisRateLimiter.Decision result = future.get();
				if (result.allowed()) allowed++;
				assertTrue(result.remaining() >= 0);
				assertTrue(result.resetAfterSeconds() >= 1);
			}
			assertEquals(25, allowed);
			assertEquals(25.0, metrics.get("crm.rate_limit.requests").tags("policy", "login", "outcome", "allowed").counter().count());
			assertEquals(75.0, metrics.get("crm.rate_limit.requests").tags("policy", "login", "outcome", "rejected").counter().count());
			assertEquals(100, metrics.get("crm.rate_limit.lookup").tag("policy", "login").timer().count());
			assertEquals("25", redis.opsForValue().get(prefix + "login:shared-client"));
			System.out.println("Shared Redis quota: 2 limiter instances, 20 threads, 100 requests, 25 allowed, 75 rejected, 0 quota overshoot");
		}
		finally {
			metrics.close();
			redis.delete(prefix + "login:shared-client");
		}
	}

	@Test
	void expiryRestoresQuotaAndSeparateIdentitiesHaveSeparateBudgets() throws Exception {
		String prefix = "crm:test:rate-limit:" + UUID.randomUUID() + ":";
		RateLimitProperties properties = new RateLimitProperties(prefix, 1, 1, 1, Duration.ofSeconds(1));
		SimpleMeterRegistry metrics = new SimpleMeterRegistry();
		try {
			RedisRateLimiter limiter = new RedisRateLimiter(redis, metrics, properties);
			assertTrue(limiter.check(RedisRateLimiter.Policy.API, "one", 1, properties.window()).allowed());
			assertFalse(limiter.check(RedisRateLimiter.Policy.API, "one", 1, properties.window()).allowed());
			assertTrue(limiter.check(RedisRateLimiter.Policy.API, "two", 1, properties.window()).allowed());
			assertTrue(limiter.check(RedisRateLimiter.Policy.LOGIN, "one", 1, properties.window()).allowed());
			Thread.sleep(1100);
			assertTrue(limiter.check(RedisRateLimiter.Policy.API, "one", 1, properties.window()).allowed());
		}
		finally {
			metrics.close();
			redis.delete(List.of(prefix + "api:one", prefix + "api:two", prefix + "login:one"));
		}
	}
}
