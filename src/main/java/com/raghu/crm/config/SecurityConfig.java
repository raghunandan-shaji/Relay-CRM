package com.raghu.crm.config;

import com.raghu.crm.systemdesign.ratelimit.RateLimitFilter;
import com.raghu.crm.systemdesign.ratelimit.RateLimitProperties;
import com.raghu.crm.systemdesign.ratelimit.RedisRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(RateLimitProperties.class)
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, RedisRateLimiter limiter, RateLimitProperties properties) throws Exception {
		http.csrf(csrf -> csrf.disable())
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
				"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.disable())
			.authorizeHttpRequests(authorize -> authorize
			.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
			.requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**").hasAuthority("SCOPE_crm:staff")
			.requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/ui.js", "/styles.css", "/favicon.svg").permitAll()
			.requestMatchers(HttpMethod.POST, "/api/customers", "/api/auth/login").permitAll()
			.requestMatchers("/api/support/**").hasAuthority("SCOPE_crm:staff")
			.requestMatchers("/api/customers/*/tickets", "/api/customers/*/tickets/**",
				"/api/customers/*/notifications", "/api/customers/*/notifications/**").authenticated()
			.requestMatchers(HttpMethod.GET, "/api/customers/*/interactions", "/api/customers/*/interactions/**",
				"/api/customers/*/activities", "/api/customers/*/activities/**").authenticated()
			.requestMatchers("/api/customers/*/interactions", "/api/customers/*/interactions/**",
				"/api/customers/*/activities", "/api/customers/*/activities/**").hasAuthority("SCOPE_crm:staff")
			.requestMatchers("/api/sales-representatives", "/api/customers/*/leads", "/api/customers/*/leads/**").hasAuthority("SCOPE_crm:staff")
			.requestMatchers(HttpMethod.GET, "/api/customers", "/api/customers/search", "/api/customers/suggestions").hasAuthority("SCOPE_crm:staff")
			.requestMatchers(HttpMethod.GET, "/api/customers/{id}").authenticated()
			.requestMatchers(HttpMethod.PUT, "/api/customers/{id}").authenticated()
			.anyRequest().denyAll())
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
			.addFilterAfter(new RateLimitFilter(limiter, properties), BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
