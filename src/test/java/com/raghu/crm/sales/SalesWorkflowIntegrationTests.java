package com.raghu.crm.sales;

import com.jayway.jsonpath.JsonPath;
import com.raghu.crm.customer.CreateCustomerRequest;
import com.raghu.crm.customer.Customer;
import com.raghu.crm.customer.CustomerProfileCache;
import com.raghu.crm.customer.CustomerResponse;
import com.raghu.crm.customer.CustomerService;
import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class SalesWorkflowIntegrationTests {

	private static final List<String> STAFF_EMAILS = List.of("sales-one-" + UUID.randomUUID() + "@example.com",
		"sales-two-" + UUID.randomUUID() + "@example.com", "sales-three-" + UUID.randomUUID() + "@example.com");

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry settings) {
		settings.add("crm.auth.staff-emails", () -> String.join(",", STAFF_EMAILS));
		String prefix = "crm:test:sales-http:" + UUID.randomUUID() + ":";
		settings.add("crm.rate-limit.key-prefix", () -> prefix);
		settings.add("crm.rate-limit.api", () -> 500);
	}

	@LocalServerPort private int port;
	@Autowired private LeadService leads;
	@Autowired private CustomerService customers;
	@Autowired private CustomerProfileCache cache;
	@Autowired private StringRedisTemplate redis;
	@Autowired private ShardRouter router;
	@Autowired private MongoShardRegistry shards;
	@Autowired private SnowflakeIdGenerator ids;
	@Autowired private JsonMapper mapper;
	private final List<CustomerResponse> createdCustomers = new ArrayList<>();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private CustomerResponse customer;

	@BeforeEach
	void seed() {
		leads.initializeIndexes(shards);
		for (String email : STAFF_EMAILS) createCustomer("Sales Rep", email);
		customer = createCustomer("Sales Customer", "sales-customer-" + UUID.randomUUID() + "@example.com");
	}

	@AfterEach
	void cleanup() {
		List<String> allIds = createdCustomers.stream().map(CustomerResponse::id).toList();
		for (String shard : shards.shardIds()) {
			shards.template(shard).remove(Query.query(Criteria.where("customerId").in(allIds)), Lead.class);
			shards.template(shard).remove(Query.query(Criteria.where("_id").in(allIds)), Customer.class);
		}
		List<String> keys = new ArrayList<>();
		for (CustomerResponse value : createdCustomers) {
			keys.add("crm:customer-email:" + value.email());
			keys.add(cache.key(value.id()));
		}
		redis.delete(keys);
	}

	@Test
	void realHttpPipelinePreservesMoneyVersionsAndStaffAccess() throws Exception {
		String staff = login(STAFF_EMAILS.getFirst());
		String ordinary = login(customer.email());
		String base = "/api/customers/" + customer.id() + "/leads";
		assertEquals(401, request("GET", base, null, null).statusCode());
		assertEquals(403, request("POST", base, "{\"title\":\"Blocked\"}", ordinary).statusCode());
		assertEquals(200, request("GET", "/api/sales-representatives", null, staff).statusCode());
		HttpResponse<String> created = request("POST", base, "{\"title\":\"CRM Subscription\",\"source\":\"Website\"}", staff);
		assertEquals(201, created.statusCode(), created.body());
		Lead lead = mapper.readValue(created.body(), Lead.class);
		assertEquals(router.shardIdFor(customer.id()), created.headers().firstValue("X-Data-Shard").orElseThrow());
		String assigned = lead.representativeId();
		assertTrue(createdCustomers.subList(0, 3).stream().anyMatch(rep -> rep.id().equals(assigned)));
		String path = base + "/" + lead.id();
		assertEquals(200, request("GET", path, null, staff).statusCode());
		assertEquals(404, request("GET", "/api/customers/" + createdCustomers.getFirst().id() + "/leads/" + lead.id(), null, staff).statusCode());
		assertEquals(1, ((List<?>) JsonPath.read(request("GET", base, null, staff).body(), "$" )).size());
		lead = changed("PUT", path + "/status", "{\"status\":\"CONTACTED\",\"version\":0}", staff, 200);
		assertEquals(1, lead.version());
		assertEquals(409, request("PUT", path + "/status", "{\"status\":\"LOST\",\"version\":0}", staff).statusCode());
		String rep = createdCustomers.get(2).id();
		lead = changed("PUT", path + "/representative", "{\"representativeId\":\"" + rep + "\",\"version\":1}", staff, 200);
		assertEquals(rep, lead.representativeId());
		lead = changed("POST", path + "/opportunity", "{\"amount\":123456.78,\"currency\":\"INR\",\"version\":2}", staff, 201);
		assertEquals(LeadStatus.QUALIFIED, lead.status());
		assertEquals(OpportunityStage.OPEN, lead.opportunity().stage());
		String opportunityId = lead.opportunity().id();
		Document stored = router.templateFor(customer.id()).getCollection("leads").find(new Document("_id", lead.id())).first();
		assertNotNull(stored);
		Decimal128 amount = stored.get("opportunity", Document.class).get("amount", Decimal128.class);
		assertEquals(0, new BigDecimal("123456.78").compareTo(amount.bigDecimalValue()));
		lead = changed("PUT", path + "/opportunity/stage", "{\"stage\":\"PROPOSAL\",\"version\":3}", staff, 200);
		lead = changed("PUT", path + "/opportunity/stage", "{\"stage\":\"NEGOTIATION\",\"version\":4}", staff, 200);
		lead = changed("PUT", path + "/opportunity/stage", "{\"stage\":\"WON\",\"version\":5}", staff, 200);
		assertEquals(6, lead.version());
		assertEquals(opportunityId, lead.opportunity().id());
		assertEquals(409, request("PUT", path + "/opportunity/stage", "{\"stage\":\"LOST\",\"version\":6}", staff).statusCode());
		assertEquals(409, request("PUT", path + "/representative", "{\"representativeId\":\"" + rep + "\",\"version\":6}", staff).statusCode());
		assertEquals(409, request("POST", path + "/opportunity", "{\"amount\":1,\"currency\":\"INR\",\"version\":6}", staff).statusCode());
		assertEquals(404, request("POST", "/api/customers/1/leads", "{\"title\":\"Missing customer\"}", staff).statusCode());
		Lead invalidCurrency = leads.create(customer.id(), new CreateLeadRequest("Currency validation", null, null, null));
		assertEquals(400, request("POST", base + "/" + invalidCurrency.id() + "/opportunity",
			"{\"amount\":1,\"currency\":\"ZZZ\",\"version\":0}", staff).statusCode());
		assertEquals(0, leads.get(customer.id(), invalidCurrency.id()).version());
		assertEquals(400, request("PUT", path + "/opportunity/stage", "{\"stage\":\"INVALID\",\"version\":6}", staff).statusCode());
		assertEquals(400, request("POST", base, "{", staff).statusCode());
		assertEquals(400, request("GET", base + "?limit=abc", null, staff).statusCode());
		System.out.println("Real HTTP sales pipeline: create=201, contacted/reassigned=200, qualify=201, proposal/negotiation/won=200; stale/closed changes=409; ordinary write=403; missing customer=404; amount stored as exact Decimal128");
	}

	@Test
	void concurrentQualificationHasExactlyOneWinner() throws Exception {
		Lead initial = leads.create(customer.id(), new CreateLeadRequest("Concurrent deal", null, null, null));
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Lead>> attempts = new ArrayList<>();
		try (var executor = Executors.newFixedThreadPool(16)) {
			for (int i = 0; i < 32; i++) {
				attempts.add(executor.submit(() -> {
					start.await();
					try {
						return leads.qualify(customer.id(), initial.id(), new CreateOpportunityRequest(new BigDecimal("19999.95"), "INR", 0L));
					}
					catch (SalesException exception) {
						assertEquals(HttpStatus.CONFLICT, exception.status());
						return null;
					}
				}));
			}
			start.countDown();
			int winners = 0;
			for (Future<Lead> attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS) != null) winners++;
			assertEquals(1, winners);
		}
		Lead stored = leads.get(customer.id(), initial.id());
		assertEquals(1, stored.version());
		assertEquals(LeadStatus.QUALIFIED, stored.status());
		assertNotNull(stored.opportunity());
		assertEquals(1, router.templateFor(customer.id()).count(Query.query(Criteria.where("customerId").is(customer.id()).and("opportunity").ne(null)), Lead.class));
		System.out.println("Concurrent qualification: 32 attempts, 1 success, 31 conflicts, exactly 1 embedded opportunity");
	}

	@Test
	void leadsAndOpportunitiesStayWithTheirCustomerAcrossThreePhysicalShards() {
		Map<String, CustomerResponse> perShard = new LinkedHashMap<>();
		for (int attempt = 0; perShard.size() < 3 && attempt < 1000; attempt++) {
			String id = Long.toString(ids.nextId());
			String shard = router.shardIdFor(id);
			if (perShard.containsKey(shard)) continue;
			Instant now = Instant.now();
			String email = "sales-shard-" + id + "@example.com";
			router.templateFor(id).insert(new Customer(id, "Shard Fixture", email, "test-fixture-no-login", null, null, now, now, 0, "shard fixture"));
			CustomerResponse fixture = new CustomerResponse(id, shard, "Shard Fixture", email, null, null, now, now, 0);
			createdCustomers.add(fixture);
			perShard.put(shard, fixture);
		}
		assertEquals(3, perShard.size());
		Map<String, Long> physicalCounts = new java.util.TreeMap<>();
		for (var entry : perShard.entrySet()) {
			String customerId = entry.getValue().id();
			List<String> leadIds = new ArrayList<>();
			for (int i = 0; i < 10; i++) leadIds.add(leads.create(customerId, new CreateLeadRequest("Shard Deal " + i, null, null, null)).id());
			Lead qualified = leads.qualify(customerId, leadIds.getFirst(), new CreateOpportunityRequest(new BigDecimal("100.00"), "INR", 0L));
			assertNotNull(qualified.opportunity());
			List<Lead> firstPage = leads.list(customerId, null, 5);
			List<Lead> secondPage = leads.list(customerId, firstPage.getLast().id(), 5);
			assertEquals(5, firstPage.size());
			assertEquals(5, secondPage.size());
			assertEquals(10, java.util.stream.Stream.concat(firstPage.stream(), secondPage.stream()).map(Lead::id).distinct().count());
			assertTrue(leads.list(customerId, secondPage.getLast().id(), 5).isEmpty());
			for (String shard : shards.shardIds()) {
				long count = shards.template(shard).count(Query.query(Criteria.where("customerId").is(customerId)), Lead.class);
				assertEquals(shard.equals(entry.getKey()) ? 10 : 0, count);
				if (shard.equals(entry.getKey())) physicalCounts.put(shard, count);
			}
		}
		System.out.println("Physical sales colocation (10 leads and 1 embedded opportunity per customer): " + physicalCounts + "; keyset pages contain no duplicates");
	}

	private CustomerResponse createCustomer(String name, String email) {
		CustomerResponse created = customers.create(new CreateCustomerRequest(name, email, "password-123", null, null));
		createdCustomers.add(created);
		return created;
	}

	private String login(String email) throws Exception {
		HttpResponse<String> response = request("POST", "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"password-123\"}", null);
		assertEquals(200, response.statusCode(), response.body());
		return JsonPath.read(response.body(), "$.accessToken");
	}

	private Lead changed(String method, String path, String body, String token, int status) throws Exception {
		HttpResponse<String> response = request(method, path, body, token);
		assertEquals(status, response.statusCode(), response.body());
		return mapper.readValue(response.body(), Lead.class);
	}

	private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) request.header("Content-Type", "application/json");
		if (token != null) request.header("Authorization", "Bearer " + token);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
