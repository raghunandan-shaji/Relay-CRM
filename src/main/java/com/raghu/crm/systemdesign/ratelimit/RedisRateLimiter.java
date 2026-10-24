package com.raghu.crm.systemdesign.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
public class RedisRateLimiter {

	// shortcut: fixed windows permit boundary bursts; use a token bucket when traffic needs smoothing.
	@SuppressWarnings("rawtypes")
	private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();
	static {
		SCRIPT.setLocation(new ClassPathResource("redis/rate-limit.lua"));
		SCRIPT.setResultType(List.class);
	}

	private final StringRedisTemplate redis;
	private final MeterRegistry metrics;
	private final RateLimitProperties properties;

	public RedisRateLimiter(StringRedisTemplate redis, MeterRegistry metrics, RateLimitProperties properties) {
		this.redis = redis;
		this.metrics = metrics;
		this.properties = properties;
	}

	public Decision check(Policy policy, String identity, int limit, Duration window) {
		if (identity == null || identity.isBlank() || limit < 1 || window == null || window.toMillis() < 1) {
			throw new IllegalArgumentException("A nonempty identity, positive limit and window are required");
		}
		String name = policy.name().toLowerCase(Locale.ROOT);
		Timer.Sample sample = Timer.start(metrics);
		try {
			List<?> result = redis.execute(SCRIPT,
				List.of(properties.keyPrefix() + name + ":" + identity),
				Integer.toString(limit), Long.toString(window.toMillis()));
			if (result == null || result.size() != 3) {
				throw new DataRetrievalFailureException("Redis returned no rate-limit decision");
			}
			boolean allowed = ((Number) result.get(0)).longValue() == 1;
			Decision decision = new Decision(allowed, ((Number) result.get(1)).intValue(),
				Math.max(1, (((Number) result.get(2)).longValue() + 999) / 1000));
			metrics.counter("crm.rate_limit.requests", "policy", name,
				"outcome", allowed ? "allowed" : "rejected").increment();
			return decision;
		}
		catch (DataAccessException exception) {
			metrics.counter("crm.rate_limit.requests", "policy", name, "outcome", "unavailable").increment();
			throw exception;
		}
		finally {
			sample.stop(metrics.timer("crm.rate_limit.lookup", "policy", name));
		}
	}

	public enum Policy { LOGIN, REGISTRATION, API }

	public record Decision(boolean allowed, int remaining, long resetAfterSeconds) {
	}
}
