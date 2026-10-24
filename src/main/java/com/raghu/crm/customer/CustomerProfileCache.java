package com.raghu.crm.customer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

@Component
public class CustomerProfileCache {

	private static final DefaultRedisScript<Long> PUT = new DefaultRedisScript<>();
	static {
		PUT.setLocation(new ClassPathResource("redis/profile-cache-put.lua"));
		PUT.setResultType(Long.class);
	}

	private final StringRedisTemplate redis;
	private final JsonMapper mapper;
	private final MeterRegistry metrics;
	private final String prefix;
	private final Duration ttl;

	public CustomerProfileCache(StringRedisTemplate redis, JsonMapper mapper, MeterRegistry metrics,
			@Value("${crm.customer-cache.key-prefix:crm:profile:v1:}") String prefix,
			@Value("${crm.customer-cache.ttl:60s}") Duration ttl) {
		if (prefix.isBlank() || ttl.toMillis() < 1 || ttl.compareTo(Duration.ofDays(1)) > 0) {
			throw new IllegalArgumentException("Profile cache requires a prefix and a TTL between 1 ms and 1 day");
		}
		this.redis = redis;
		this.mapper = mapper;
		this.metrics = metrics;
		this.prefix = prefix;
		this.ttl = ttl;
	}

	public CustomerResponse get(String id) {
		Timer.Sample sample = Timer.start(metrics);
		try {
			String json = (String) redis.opsForHash().get(key(id), "data");
			if (json == null) {
				outcome("miss");
				return null;
			}
			CustomerResponse profile = mapper.readValue(json, CustomerResponse.class);
			if (profile == null || !id.equals(profile.id()) || profile.version() < 0) {
				outcome("invalid");
				return null;
			}
			outcome("hit");
			return profile;
		}
		catch (JacksonException exception) {
			outcome("invalid");
			return null;
		}
		catch (DataAccessException exception) {
			outcome("unavailable");
			return null;
		}
		finally {
			sample.stop(metrics.timer("crm.customer_cache.lookup"));
		}
	}

	public void put(CustomerResponse profile) {
		// shortcut: fencing lasts only while the entry exists; strict consistency needs transactional invalidation.
		try {
			Long stored = redis.execute(PUT, List.of(key(profile.id())),
				Long.toString(profile.version()), mapper.writeValueAsString(profile), Long.toString(ttl.toMillis()));
			metrics.counter("crm.customer_cache.writes", "outcome", Long.valueOf(1).equals(stored) ? "stored" : "older_rejected").increment();
		}
		catch (DataAccessException | JacksonException exception) {
			metrics.counter("crm.customer_cache.writes", "outcome", "unavailable").increment();
		}
	}

	public String key(String id) {
		return prefix + id;
	}

	private void outcome(String outcome) {
		metrics.counter("crm.customer_cache.requests", "outcome", outcome).increment();
	}
}
