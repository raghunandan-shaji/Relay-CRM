package com.raghu.crm.systemdesign.ratelimit;

import com.raghu.crm.ScalableCrmApplication;
import com.raghu.crm.auth.AuthController;
import com.raghu.crm.auth.LoginRequest;
import com.raghu.crm.customer.CreateCustomerRequest;
import com.raghu.crm.customer.Customer;
import com.raghu.crm.customer.CustomerResponse;
import com.raghu.crm.customer.CustomerService;
import com.raghu.crm.customer.CustomerProfileCache;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class RateLimitServersIntegrationTests {

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	@Test
	void twoHttpServersShareAllThreeQuotas() throws Exception {
		String prefix = "crm:test:two-servers:" + UUID.randomUUID() + ":";
		try (ConfigurableApplicationContext first = start(prefix, 201);
			 ConfigurableApplicationContext second = start(prefix, 202)) {
			int[] ports = {port(first), port(second)};
			StringRedisTemplate redis = first.getBean(StringRedisTemplate.class);
			CustomerService customers = first.getBean(CustomerService.class);
			ShardRouter router = first.getBean(ShardRouter.class);
			List<CustomerResponse> created = new ArrayList<>();
			try {
				// Invalid bodies exercise the quota without creating accounts or hashing passwords.
				for (int i = 0; i < 4; i++) {
					HttpResponse<String> response = request(ports[i % 2], "POST", "/api/auth/login", "{}", null);
					assertEquals(400, response.statusCode(), response.body());
					assertEquals(Integer.toString(3 - i), response.headers().firstValue("X-RateLimit-Remaining").orElseThrow());
				}
				assertRejected(request(ports[0], "POST", "/api/auth/login", "{}", null));
				assertRejected(request(ports[1], "POST", "/api/auth/login", "{}", null));
				for (int i = 0; i < 2; i++) {
					assertEquals(400, request(ports[i], "POST", "/api/customers", "{}", null).statusCode());
				}
				assertRejected(request(ports[1], "POST", "/api/customers", "{}", null));

				for (int i = 0; i < 2; i++) {
					created.add(customers.create(new CreateCustomerRequest("Quota Test",
						"quota-" + UUID.randomUUID() + "@example.com", "password-123", null, null)));
				}
				AuthController auth = first.getBean(AuthController.class);
				CustomerResponse owner = created.get(0);
				String token = auth.login(new LoginRequest(owner.email(), "password-123")).accessToken();
				for (int i = 0; i < 3; i++) {
					assertEquals(200, request(ports[i % 2], "GET", "/api/customers/" + owner.id(), null, token).statusCode());
				}
				assertRejected(request(ports[1], "GET", "/api/customers/" + owner.id(), null, token));
				assertRejected(request(ports[0], "GET", "/api/customers/" + owner.id(), null, token));
				CustomerResponse other = created.get(1);
				String otherToken = auth.login(new LoginRequest(other.email(), "password-123")).accessToken();
				assertEquals(200, request(ports[1], "GET", "/api/customers/" + other.id(), null, otherToken).statusCode());
				assertEquals(200, request(ports[0], "GET", "/actuator/health/liveness", null, null).statusCode());
				System.out.println("Two running HTTP servers: combined login budget=4, registration=2, customer API=3; both return 429 after shared exhaustion; other customer and liveness stay available");
			}
			finally {
				List<String> keys = new ArrayList<>(List.of(prefix + "login:127.0.0.1", prefix + "registration:127.0.0.1"));
				for (CustomerResponse customer : created) {
					router.templateFor(customer.id()).remove(Query.query(Criteria.where("_id").is(customer.id())), Customer.class);
					keys.add("crm:customer-email:" + customer.email());
					keys.add(prefix + "api:" + customer.id());
					keys.add(first.getBean(CustomerProfileCache.class).key(customer.id()));
				}
				redis.delete(keys);
			}
		}
	}

	private ConfigurableApplicationContext start(String prefix, int workerId) {
		return new SpringApplicationBuilder(ScalableCrmApplication.class).run(
			"--server.port=0", "--crm.id.worker-id=" + workerId,
			"--crm.rate-limit.key-prefix=" + prefix, "--crm.rate-limit.login=4",
			"--crm.rate-limit.registration=2", "--crm.rate-limit.api=3", "--crm.rate-limit.window=60s",
			"--spring.main.banner-mode=off");
	}

	private int port(ConfigurableApplicationContext context) {
		return ((WebServerApplicationContext) context).getWebServer().getPort();
	}

	private void assertRejected(HttpResponse<String> response) {
		assertEquals(429, response.statusCode(), response.body());
		assertTrue(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()) >= 1);
		assertEquals("0", response.headers().firstValue("X-RateLimit-Remaining").orElseThrow());
	}

	private HttpResponse<String> request(int port, String method, String path, String body, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) request.header("Content-Type", "application/json");
		if (token != null) request.header("Authorization", "Bearer " + token);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
