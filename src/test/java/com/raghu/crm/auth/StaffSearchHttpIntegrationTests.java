package com.raghu.crm.auth;

import com.jayway.jsonpath.JsonPath;
import com.raghu.crm.customer.CreateCustomerRequest;
import com.raghu.crm.customer.Customer;
import com.raghu.crm.customer.CustomerProfileCache;
import com.raghu.crm.customer.CustomerResponse;
import com.raghu.crm.customer.CustomerSearchService;
import com.raghu.crm.customer.CustomerService;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class StaffSearchHttpIntegrationTests {

	private static final String STAFF_EMAIL = "staff-" + UUID.randomUUID() + "@example.com";
	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry settings) {
		settings.add("crm.auth.staff-emails", () -> STAFF_EMAIL);
		String prefix = "crm:test:staff-http:" + UUID.randomUUID() + ":";
		settings.add("crm.rate-limit.key-prefix", () -> prefix);
	}

	@LocalServerPort private int port;
	@Autowired private CustomerService customers;
	@Autowired private CustomerSearchService search;
	@Autowired private ShardRouter router;
	@Autowired private CustomerProfileCache cache;
	@Autowired private StringRedisTemplate redis;
	@Autowired private JsonMapper mapper;
	@Autowired private JwtDecoder decoder;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	@Test
	void staffSearchWorksWhileCustomerTokensCannotExposeCachedProfiles() throws Exception {
		search.initialize();
		String prefix = "q" + UUID.randomUUID().toString().substring(0, 8) + " ";
		List<CustomerResponse> created = new ArrayList<>();
		try {
			created.add(customers.create(new CreateCustomerRequest(prefix + "Operator", STAFF_EMAIL, "password-123", null, null)));
			CustomerResponse target = customers.create(new CreateCustomerRequest(prefix + "Raghu", "target-" + UUID.randomUUID() + "@example.com", "password-123", null, null));
			created.add(target);
			String ordinaryEmail = "ordinary-" + UUID.randomUUID() + "@example.com";
			HttpResponse<String> signup = request("POST", "/api/customers", "{\"fullName\":\"" + prefix + "Raghav\",\"email\":\"" + ordinaryEmail + "\",\"password\":\"password-123\",\"role\":\"STAFF\"}", null);
			assertEquals(201, signup.statusCode(), signup.body());
			created.add(mapper.readValue(signup.body(), CustomerResponse.class));
			String staffToken = login(STAFF_EMAIL);
			String ordinaryToken = login(ordinaryEmail);
			assertEquals("crm:staff", decoder.decode(staffToken).getClaimAsString("scope"));
			assertEquals("", decoder.decode(ordinaryToken).getClaimAsString("scope"));
			String query = "?prefix=" + URLEncoder.encode(prefix + "ra", StandardCharsets.UTF_8) + "&limit=5";
			assertEquals(401, request("GET", "/api/customers/search" + query, null, null).statusCode());
			assertEquals(403, request("GET", "/api/customers/search" + query, null, ordinaryToken).statusCode());
			HttpResponse<String> found = request("GET", "/api/customers/search" + query, null, staffToken);
			assertEquals(200, found.statusCode(), found.body());
			List<String> names = JsonPath.read(found.body(), "$[*].fullName");
			assertEquals(List.of(prefix + "Raghav", prefix + "Raghu"), names);
			HttpResponse<String> suggestions = request("GET", "/api/customers/suggestions" + query, null, staffToken);
			assertEquals(200, suggestions.statusCode(), suggestions.body());
			assertEquals(names, JsonPath.read(suggestions.body(), "$[*].fullName"));
			assertFalse(suggestions.body().contains("email"));
			HttpResponse<String> first = request("GET", "/api/customers/" + target.id(), null, staffToken);
			assertEquals(200, first.statusCode(), first.body());
			assertEquals("mongodb", first.headers().firstValue("X-Profile-Source").orElseThrow());
			assertEquals("redis", request("GET", "/api/customers/" + target.id(), null, staffToken)
				.headers().firstValue("X-Profile-Source").orElseThrow());
			assertEquals(403, request("GET", "/api/customers/" + target.id(), null, ordinaryToken).statusCode());
			System.out.println("Real HTTP staff search/suggestions=200; ordinary search=403; client-supplied STAFF role ignored; profile source MongoDB then Redis; cached foreign profile still returns 403");
		}
		finally {
			for (CustomerResponse customer : created) {
				router.templateFor(customer.id()).remove(Query.query(Criteria.where("_id").is(customer.id())), Customer.class);
				redis.delete(List.of("crm:customer-email:" + customer.email(), cache.key(customer.id())));
			}
		}
	}

	private String login(String email) throws Exception {
		HttpResponse<String> response = request("POST", "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"password-123\"}", null);
		assertEquals(200, response.statusCode(), response.body());
		return JsonPath.read(response.body(), "$.accessToken");
	}

	private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) request.header("Content-Type", "application/json");
		if (token != null) request.header("Authorization", "Bearer " + token);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
