package com.raghu.crm.auth;

import com.jayway.jsonpath.JsonPath;
import com.raghu.crm.customer.Customer;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class AuthenticationFlowIntegrationTests {

	@DynamicPropertySource
	static void isolatedQuotaNamespace(DynamicPropertyRegistry properties) {
		String prefix = "crm:test:auth-flow:" + UUID.randomUUID() + ":";
		properties.add("crm.rate-limit.key-prefix", () -> prefix);
	}

	@LocalServerPort private int port;
	@Autowired private ShardRouter router;
	@Autowired private StringRedisTemplate redis;
	@Autowired private PasswordEncoder passwords;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	@Test
	void realHttpRegistrationLoginAndOwnershipEnforcement() throws Exception {
		Map<String, String> created = new LinkedHashMap<>();
		String email = "auth-" + UUID.randomUUID() + "@example.com";
		String otherEmail = "auth-" + UUID.randomUUID() + "@example.com";
		try {
			String id = register(email, created);
			String otherId = register(otherEmail, created);
			Customer stored = router.templateFor(id).findById(id, Customer.class);
			assertNotNull(stored);
			assertTrue(passwords.matches("password-123", stored.passwordHash()));
			assertFalse(stored.passwordHash().equals("password-123"));

			assertEquals(401, request("GET", "/api/customers/" + id, null, null).statusCode());
			HttpResponse<String> login = request("POST", "/api/auth/login",
				"{\"email\":\"" + email.toUpperCase(java.util.Locale.ROOT) + "\",\"password\":\"password-123\"}", null);
			assertEquals(200, login.statusCode(), login.body());
			assertTrue(login.headers().firstValue("Set-Cookie").isEmpty());
			String token = JsonPath.read(login.body(), "$.accessToken");
			assertEquals(id, JsonPath.read(login.body(), "$.customerId"));
			assertEquals(900, (Integer) JsonPath.read(login.body(), "$.expiresIn"));

			HttpResponse<String> profile = request("GET", "/api/customers/" + id, null, token);
			assertEquals(200, profile.statusCode(), profile.body());
			assertEquals(id, JsonPath.read(profile.body(), "$.id"));
			assertFalse(profile.body().contains("password"));
			String update = "{\"fullName\":\"Updated Customer\",\"company\":\"CRM Lab\",\"version\":0}";
			HttpResponse<String> updated = request("PUT", "/api/customers/" + id, update, token);
			assertEquals(200, updated.statusCode(), updated.body());
			assertEquals(1, (Integer) JsonPath.read(updated.body(), "$.version"));
			assertEquals(409, request("PUT", "/api/customers/" + id, update, token).statusCode());
			assertEquals(403, request("GET", "/api/customers/" + otherId, null, token).statusCode());
			assertEquals(403, request("PUT", "/api/customers/" + otherId, update, token).statusCode());
			assertEquals("HTTP Customer", router.templateFor(otherId).findById(otherId, Customer.class).fullName());
			assertEquals(401, request("POST", "/api/auth/login",
				"{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}", null).statusCode());
			assertEquals(401, request("POST", "/api/auth/login",
				"{\"email\":\"missing-" + UUID.randomUUID() + "@example.com\",\"password\":\"wrong-password\"}", null).statusCode());
			System.out.println("Real HTTP auth: anonymous=401, login=200, own profile=200, update=200, stale=409, other customer=403, wrong password=401");
		}
		finally {
			created.forEach((customerEmail, customerId) -> {
				router.templateFor(customerId).remove(Query.query(Criteria.where("_id").is(customerId)), Customer.class);
				redis.delete("crm:customer-email:" + customerEmail);
			});
		}
	}

	private String register(String email, Map<String, String> created) throws Exception {
		HttpResponse<String> response = request("POST", "/api/customers",
			"{\"fullName\":\"HTTP Customer\",\"email\":\"" + email + "\",\"password\":\"password-123\"}", null);
		assertEquals(201, response.statusCode(), response.body());
		String id = JsonPath.read(response.body(), "$.id");
		created.put(email, id);
		assertFalse(response.body().contains("password"));
		return id;
	}

	private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) request.header("Content-Type", "application/json");
		if (token != null) request.header("Authorization", "Bearer " + token);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
