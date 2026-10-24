package com.raghu.crm.customer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerProfileCacheTests {

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
	private final JsonMapper mapper = JsonMapper.builder().build();
	private final CustomerProfileCache cache = new CustomerProfileCache(redis, mapper, new SimpleMeterRegistry(), "test:", Duration.ofSeconds(60));

	@Test
	void roundTripOnlyCachesThePublicProfile() {
		when(redis.opsForHash()).thenReturn(hashes);
		Instant now = Instant.parse("2026-10-25T00:00:00Z");
		CustomerResponse profile = new CustomerResponse("123", "shard-0", "Demo", "demo@example.com", null, null, now, now, 1);
		when(hashes.get("test:123", "data")).thenReturn(mapper.writeValueAsString(profile));
		assertEquals(profile, cache.get("123"));
	}

	@Test
	void corruptOrWrongCustomerCacheEntriesAreMisses() {
		when(redis.opsForHash()).thenReturn(hashes);
		when(hashes.get("test:123", "data")).thenReturn("invalid-json");
		assertNull(cache.get("123"));
		when(hashes.get("test:123", "data")).thenReturn(mapper.writeValueAsString(
			new CustomerResponse("456", "shard-0", "Demo", "demo@example.com", null, null, Instant.now(), Instant.now(), 0)));
		assertNull(cache.get("123"));
	}

	@Test
	void unavailableCacheReturnsMissInsteadOfFailingProfileRead() {
		when(redis.opsForHash()).thenThrow(new DataAccessResourceFailureException("offline"));
		assertNull(cache.get("123"));
	}
}
