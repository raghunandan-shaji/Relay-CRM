package com.raghu.crm.systemdesign.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public final class RateLimitFilter extends OncePerRequestFilter {

	private final RedisRateLimiter limiter;
	private final RateLimitProperties properties;

	public RateLimitFilter(RedisRateLimiter limiter, RateLimitProperties properties) {
		this.limiter = limiter;
		this.properties = properties;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String path = request.getServletPath();
		if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
		RedisRateLimiter.Policy policy;
		String identity;
		int limit;
		if ("POST".equals(request.getMethod()) && "/api/auth/login".equals(path)) {
			policy = RedisRateLimiter.Policy.LOGIN;
			identity = request.getRemoteAddr();
			limit = properties.login();
		}
		else if ("POST".equals(request.getMethod()) && "/api/customers".equals(path)) {
			policy = RedisRateLimiter.Policy.REGISTRATION;
			identity = request.getRemoteAddr();
			limit = properties.registration();
		}
		else {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (!path.startsWith("/api/") || authentication == null || !authentication.isAuthenticated()) {
				chain.doFilter(request, response);
				return;
			}
			policy = RedisRateLimiter.Policy.API;
			identity = authentication.getName();
			limit = properties.api();
		}

		RedisRateLimiter.Decision decision;
		try {
			decision = limiter.check(policy, identity, limit, properties.window());
		}
		catch (DataAccessException exception) {
			problem(response, 503, "Service Unavailable", "Request quota cannot be checked; retry later");
			return;
		}
		response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
		response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
		response.setHeader("X-RateLimit-Reset-After", Long.toString(decision.resetAfterSeconds()));
		if (!decision.allowed()) {
			response.setHeader("Retry-After", Long.toString(decision.resetAfterSeconds()));
			problem(response, 429, "Too Many Requests", "Request limit reached; retry after the indicated delay");
			return;
		}
		chain.doFilter(request, response);
	}

	private static void problem(HttpServletResponse response, int status, String title, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setHeader("Cache-Control", "no-store");
		response.getWriter().write("{\"status\":" + status + ",\"title\":\"" + title + "\",\"detail\":\"" + detail + "\"}");
	}
}
