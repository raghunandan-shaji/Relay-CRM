package com.raghu.crm.systemdesign.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitFilterTests {

	private final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
	private final RateLimitProperties properties = new RateLimitProperties("test:", 10, 5, 120, Duration.ofSeconds(60));
	private final RateLimitFilter filter = new RateLimitFilter(limiter, properties);

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void loginRejectionReturns429AndRetryAfterBeforeController() throws Exception {
		when(limiter.check(any(), anyString(), anyInt(), any())).thenReturn(new RedisRateLimiter.Decision(false, 0, 42));
		MockHttpServletRequest request = request("POST", "/api/auth/login");
		request.addHeader("X-Forwarded-For", "1.2.3.4");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean called = new AtomicBoolean();
		filter.doFilter(request, response, (req, res) -> called.set(true));
		assertEquals(429, response.getStatus());
		assertEquals("42", response.getHeader("Retry-After"));
		assertEquals("0", response.getHeader("X-RateLimit-Remaining"));
		assertTrue(response.getContentAsString().contains("Too Many Requests"));
		assertFalse(called.get());
		verify(limiter).check(RedisRateLimiter.Policy.LOGIN, "127.0.0.1", 10, Duration.ofSeconds(60));
	}

	@Test
	void registrationUsesSeparateIpQuota() throws Exception {
		allow();
		filter.doFilter(request("POST", "/api/customers"), new MockHttpServletResponse(), (req, res) -> {});
		verify(limiter).check(RedisRateLimiter.Policy.REGISTRATION, "127.0.0.1", 5, Duration.ofSeconds(60));
	}

	@Test
	void authenticatedQuotaUsesCustomerIdAndExposesRemainingCount() throws Exception {
		allow();
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken("123", null, List.of()));
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean called = new AtomicBoolean();
		filter.doFilter(request("GET", "/api/customers/123"), response, (req, res) -> called.set(true));
		verify(limiter).check(RedisRateLimiter.Policy.API, "123", 120, Duration.ofSeconds(60));
		assertTrue(called.get());
		assertEquals("119", response.getHeader("X-RateLimit-Remaining"));
		assertEquals("120", response.getHeader("X-RateLimit-Limit"));
	}

	@Test
	void unavailableRedisReturns503BeforeController() throws Exception {
		when(limiter.check(any(), anyString(), anyInt(), any()))
			.thenThrow(new DataAccessResourceFailureException("Private infrastructure detail"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean called = new AtomicBoolean();
		filter.doFilter(request("POST", "/api/auth/login"), response, (req, res) -> called.set(true));
		assertEquals(503, response.getStatus());
		assertFalse(response.getContentAsString().contains("Private infrastructure detail"));
		assertFalse(called.get());
	}

	@Test
	void healthAndUnauthenticatedProfileRequestsDoNotCallRedis() throws Exception {
		AtomicBoolean called = new AtomicBoolean();
		filter.doFilter(request("GET", "/actuator/health/liveness"), new MockHttpServletResponse(), (req, res) -> called.set(true));
		assertTrue(called.get());
		filter.doFilter(request("GET", "/api/customers/123"), new MockHttpServletResponse(), (req, res) -> {});
		verifyNoInteractions(limiter);
	}

	@Test
	void rejectsInvalidConfiguration() {
		assertThrows(IllegalArgumentException.class, () -> new RateLimitProperties("", 1, 1, 1, Duration.ofSeconds(1)));
		assertThrows(IllegalArgumentException.class, () -> new RateLimitProperties("test:", 0, 1, 1, Duration.ofSeconds(1)));
		assertThrows(IllegalArgumentException.class, () -> new RateLimitProperties("test:", 1, 1, 1, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new RateLimitProperties("test:", 1, 1, 1, Duration.ofDays(2)));
	}

	private void allow() {
		when(limiter.check(any(), anyString(), anyInt(), any())).thenReturn(new RedisRateLimiter.Decision(true, 119, 60));
	}

	private MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRemoteAddr("127.0.0.1");
		return request;
	}
}
