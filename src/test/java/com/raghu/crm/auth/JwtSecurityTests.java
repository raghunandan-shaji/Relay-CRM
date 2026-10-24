package com.raghu.crm.auth;

import com.raghu.crm.config.JwtConfig;
import com.raghu.crm.customer.CustomerResponse;
import com.raghu.crm.customer.CustomerService;
import com.raghu.crm.customer.CustomerSearchService;
import com.raghu.crm.customer.CustomerSummary;
import com.raghu.crm.systemdesign.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "crm.auth.staff-emails=staff@example.com")
@AutoConfigureMockMvc
class JwtSecurityTests {

	@Autowired private MockMvc mvc;
	@Autowired private JwtEncoder encoder;
	@MockitoBean private CustomerService customers;
	@MockitoBean private RedisRateLimiter limiter;
	@MockitoBean private CustomerSearchService search;

	@BeforeEach
	void allowRequestsForSecurityTests() {
		when(limiter.check(any(), anyString(), anyInt(), any()))
			.thenReturn(new RedisRateLimiter.Decision(true, 100, 60));
	}

	@Test
	void profileRequiresBearerToken() throws Exception {
		mvc.perform(get("/api/customers/123"))
			.andExpect(status().isUnauthorized());
		mvc.perform(put("/api/customers/123").contentType("application/json")
				.content("{\"fullName\":\"Changed\",\"version\":0}"))
			.andExpect(status().isUnauthorized());
		verifyNoInteractions(customers);
	}

	@Test
	void loginReturnsTokenWithoutSessionCookie() throws Exception {
		when(customers.authenticate("demo@example.com", "password-123")).thenReturn("123");
		mvc.perform(post("/api/auth/login").contentType("application/json")
				.content("{\"email\":\"demo@example.com\",\"password\":\"password-123\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken", not(emptyOrNullString())))
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andExpect(jsonPath("$.customerId").value("123"))
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void invalidCredentialsReturnGenericUnauthorizedResponse() throws Exception {
		when(customers.authenticate("demo@example.com", "wrong"))
			.thenThrow(new BadCredentialsException("Internal detail must not escape"));
		mvc.perform(post("/api/auth/login").contentType("application/json")
				.content("{\"email\":\"demo@example.com\",\"password\":\"wrong\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.detail").value("Invalid email or password"));
	}

	@Test
	void loginValidatesInputBeforeReadingDatabase() throws Exception {
		mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(customers);
	}

	@Test
	void signedTokenAllowsOwnerToReadAndUpdate() throws Exception {
		Instant now = Instant.now();
		CustomerResponse customer = new CustomerResponse("123", "shard-0", "Demo", "demo@example.com",
			null, null, now, now, 0);
		when(customers.read("123")).thenReturn(new CustomerService.ProfileRead(customer, "redis"));
		when(customers.update(eq("123"), any())).thenReturn(customer);
		String token = token("123", JwtConfig.ISSUER, JwtConfig.AUDIENCE, now.plusSeconds(900));
		mvc.perform(get("/api/customers/123").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value("123"))
			.andExpect(header().string("X-Profile-Source", "redis"))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());
		mvc.perform(put("/api/customers/123").header("Authorization", "Bearer " + token)
				.contentType("application/json").content("{\"fullName\":\"Changed\",\"version\":0}"))
			.andExpect(status().isOk());
	}

	@Test
	void validTokenCannotReadOrUpdateAnotherCustomer() throws Exception {
		String token = token("456", JwtConfig.ISSUER, JwtConfig.AUDIENCE, Instant.now().plusSeconds(900));
		mvc.perform(get("/api/customers/123").header("Authorization", "Bearer " + token))
			.andExpect(status().isForbidden());
		mvc.perform(put("/api/customers/123").header("Authorization", "Bearer " + token)
				.contentType("application/json").content("{\"fullName\":\"Changed\",\"version\":0}"))
			.andExpect(status().isForbidden());
		verifyNoInteractions(customers);
	}

	@Test
	void rejectsInvalidSignatureExpiredTokenAndWrongClaims() throws Exception {
		String valid = token("123", JwtConfig.ISSUER, JwtConfig.AUDIENCE, Instant.now().plusSeconds(900));
		String[] parts = valid.split("\\.");
		String forged = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
		for (String invalid : List.of(
			forged,
			"not-a-jwt",
			token("123", JwtConfig.ISSUER, JwtConfig.AUDIENCE, Instant.now().minusSeconds(120)),
			token("123", "other-issuer", JwtConfig.AUDIENCE, Instant.now().plusSeconds(900)),
			token("123", JwtConfig.ISSUER, "other-api", Instant.now().plusSeconds(900)),
			token("", JwtConfig.ISSUER, JwtConfig.AUDIENCE, Instant.now().plusSeconds(900)))) {
			mvc.perform(get("/api/customers/123").header("Authorization", "Bearer " + invalid))
				.andExpect(status().isUnauthorized());
		}
		verifyNoInteractions(customers);
	}

	@Test
	void registrationRejectsPasswordBeyondBcryptByteLimit() throws Exception {
		mvc.perform(post("/api/customers").contentType("application/json")
				.content("{\"fullName\":\"Demo\",\"email\":\"demo@example.com\",\"password\":\"" + "é".repeat(40) + "\"}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(customers);
	}

	private String token(String subject, String issuer, String audience, Instant expiry) {
		return token(subject, issuer, audience, expiry, "");
	}

	private String token(String subject, String issuer, String audience, Instant expiry, String scope) {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
			.subject(subject).claim("scope", scope).issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiry).build();
		return encoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
	}

	@Test
	void customerCannotSearchOrRequestStaffPrivilege() throws Exception {
		String token = token("123", JwtConfig.ISSUER, JwtConfig.AUDIENCE, Instant.now().plusSeconds(900));
		for (String endpoint : List.of("search", "suggestions")) {
			mvc.perform(get("/api/customers/" + endpoint).param("prefix", "ra")
				.header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
		}
		verifyNoInteractions(search);
	}

	@Test
	void staffCanSearchAndReadAnotherProfileButCannotUpdateIt() throws Exception {
		String token = token("456", JwtConfig.ISSUER, JwtConfig.AUDIENCE, Instant.now().plusSeconds(900), "crm:staff");
		when(search.search("ra", 20)).thenReturn(List.of(new CustomerSummary("123", "Raghu", "demo@example.com", null, "shard-0")));
		mvc.perform(get("/api/customers/search").param("prefix", "ra").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("123"));
		Instant now = Instant.now();
		when(customers.read("123")).thenReturn(new CustomerService.ProfileRead(
			new CustomerResponse("123", "shard-0", "Raghu", "demo@example.com", null, null, now, now, 0), "mongodb"));
		mvc.perform(get("/api/customers/123").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(put("/api/customers/123").header("Authorization", "Bearer " + token)
			.contentType("application/json").content("{\"fullName\":\"Wrong\",\"version\":0}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void staffSearchValidatesPrefixAndResultLimit() throws Exception {
		String token = token("456", JwtConfig.ISSUER, JwtConfig.AUDIENCE, Instant.now().plusSeconds(900), "crm:staff");
		mvc.perform(get("/api/customers/search").param("prefix", "r").header("Authorization", "Bearer " + token))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/customers/search").param("prefix", "ra").param("limit", "500")
			.header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
		verifyNoInteractions(search);
	}
}
