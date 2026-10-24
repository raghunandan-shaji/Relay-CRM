package com.raghu.crm.systemdesign.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("crm.rate-limit")
public record RateLimitProperties(String keyPrefix, int login, int registration, int api, Duration window) {

	public RateLimitProperties {
		if (keyPrefix == null || keyPrefix.isBlank()) {
			throw new IllegalArgumentException("Rate-limit key prefix is required");
		}
		if (login < 1 || registration < 1 || api < 1) {
			throw new IllegalArgumentException("All rate limits must be positive");
		}
		if (window == null || window.toMillis() < 1 || window.compareTo(Duration.ofDays(1)) > 0) {
			throw new IllegalArgumentException("Rate-limit window must be between 1 ms and 1 day");
		}
	}
}
