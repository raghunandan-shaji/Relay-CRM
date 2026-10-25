package com.raghu.crm.engagement;

import com.jayway.jsonpath.JsonPath;
import com.raghu.crm.customer.*;
import com.raghu.crm.sales.*;
import com.raghu.crm.notifications.CustomerEvent;
import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.MongoShardRegistry;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.junit.jupiter.api.*;
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

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class EngagementWorkflowIntegrationTests {
	private static final String STAFF_EMAIL = "engagement-staff-" + UUID.randomUUID() + "@example.com";

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry settings) {
		settings.add("crm.auth.staff-emails", () -> STAFF_EMAIL);
		String prefix = "crm:test:engagement:" + UUID.randomUUID() + ":";
		settings.add("crm.rate-limit.key-prefix", () -> prefix);
		settings.add("crm.rate-limit.api", () -> 500);
	}

	@LocalServerPort private int port;
	@Autowired private EngagementService engagement;
	@Autowired private LeadService leads;
	@Autowired private CustomerService customers;
	@Autowired private CustomerProfileCache cache;
	@Autowired private StringRedisTemplate redis;
	@Autowired private ShardRouter router;
	@Autowired private MongoShardRegistry shards;
	@Autowired private SnowflakeIdGenerator ids;
	@Autowired private JsonMapper mapper;
	private final List<CustomerResponse> fixtures = new ArrayList<>();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	private CustomerResponse staff;
	private CustomerResponse customer;

	@BeforeEach
	void seed() {
		leads.initializeIndexes(shards);
		engagement.initializeIndexes(shards);
		staff = createCustomer("Engagement Staff", STAFF_EMAIL);
		customer = createCustomer("Engagement Customer", "engagement-" + UUID.randomUUID() + "@example.com");
	}

	@AfterEach
	void cleanup() {
		List<String> allIds = fixtures.stream().map(CustomerResponse::id).toList();
		for (String shard : shards.shardIds()) {
			for (Class<?> type : List.of(Interaction.class, SalesActivity.class, Lead.class, CustomerEvent.class)) {
				shards.template(shard).remove(Query.query(Criteria.where("customerId").in(allIds)), type);
			}
			shards.template(shard).remove(Query.query(Criteria.where("_id").in(allIds)), Customer.class);
		}
		List<String> keys = new ArrayList<>();
		for (CustomerResponse fixture : fixtures) {
			keys.add("crm:customer-email:" + fixture.email());
			keys.add(cache.key(fixture.id()));
		}
		redis.delete(keys);
	}

	@Test
	void realHttpHistoryTasksAndOwnerAccess() throws Exception {
		String staffToken = login(staff.email());
		String ownerToken = login(customer.email());
		String base = "/api/customers/" + customer.id();
		assertEquals(401, request("GET", base + "/interactions", null, null).statusCode());
		assertEquals(403, request("GET", "/api/customers/" + staff.id() + "/interactions", null, ownerToken).statusCode());
		assertEquals(403, request("POST", base + "/interactions", "{\"type\":\"CALL\",\"summary\":\"Blocked\"}", ownerToken).statusCode());
		Lead lead = leads.create(customer.id(), new CreateLeadRequest("Linked lead", null, null, null));
		var recorded = request("POST", base + "/interactions", "{\"type\":\"CALL\",\"summary\":\"Discussed requirements\",\"leadId\":\""
			+ lead.id() + "\",\"recordedBy\":\"999\"}", staffToken);
		assertEquals(201, recorded.statusCode(), recorded.body());
		Interaction interaction = mapper.readValue(recorded.body(), Interaction.class);
		assertEquals(staff.id(), interaction.recordedBy());
		assertEquals(lead.id(), interaction.leadId());
		assertEquals(router.shardIdFor(customer.id()), recorded.headers().firstValue("X-Data-Shard").orElseThrow());
		assertEquals(200, request("GET", base + "/interactions/" + interaction.id(), null, ownerToken).statusCode());
		assertEquals(403, request("GET", "/api/customers/" + staff.id() + "/interactions/" + interaction.id(), null, ownerToken).statusCode());
		assertEquals(404, request("GET", "/api/customers/" + staff.id() + "/interactions/" + interaction.id(), null, staffToken).statusCode());
		assertEquals(1, ((List<?>) JsonPath.read(request("GET", base + "/interactions", null, ownerToken).body(), "$" )).size());
		assertEquals(400, request("GET", base + "/interactions?beforeId=" + interaction.id(), null, ownerToken).statusCode());
		assertEquals(400, request("GET", base + "/interactions?beforeAt=2020-01-01T00:00:00Z", null, ownerToken).statusCode());
		assertEquals(404, request("POST", "/api/customers/" + staff.id() + "/interactions",
			"{\"type\":\"NOTE\",\"summary\":\"Wrong customer lead\",\"leadId\":\"" + lead.id() + "\"}", staffToken).statusCode());
		var created = request("POST", base + "/activities", "{\"type\":\"CALL\",\"title\":\"Follow up\",\"dueAt\":\""
			+ Instant.now().plusSeconds(3600) + "\",\"createdBy\":\"999\"}", staffToken);
		assertEquals(201, created.statusCode(), created.body());
		SalesActivity activity = mapper.readValue(created.body(), SalesActivity.class);
		assertEquals(staff.id(), activity.createdBy());
		assertEquals(staff.id(), activity.representativeId());
		String path = base + "/activities/" + activity.id();
		assertEquals(200, request("GET", path, null, ownerToken).statusCode());
		assertEquals(1, ((List<?>) JsonPath.read(request("GET", base + "/activities?status=OPEN", null, ownerToken).body(), "$" )).size());
		assertEquals(403, request("PUT", path + "/status", "{\"status\":\"COMPLETED\",\"version\":0}", ownerToken).statusCode());
		var closed = request("PUT", path + "/status", "{\"status\":\"COMPLETED\",\"outcome\":\"Called customer\",\"version\":0}", staffToken);
		assertEquals(200, closed.statusCode(), closed.body());
		SalesActivity completed = mapper.readValue(closed.body(), SalesActivity.class);
		assertEquals(1, completed.version());
		assertEquals(ActivityStatus.COMPLETED, completed.status());
		assertEquals(staff.id(), completed.closedBy());
		assertNotNull(completed.closedAt());
		assertEquals(409, request("PUT", path + "/status", "{\"status\":\"CANCELLED\",\"version\":0}", staffToken).statusCode());
		assertEquals(409, request("PUT", path + "/status", "{\"status\":\"OPEN\",\"version\":1}", staffToken).statusCode());
		assertEquals(404, request("PUT", base + "/activities/1/status", "{\"status\":\"COMPLETED\",\"version\":0}", staffToken).statusCode());
		assertEquals(0, ((List<?>) JsonPath.read(request("GET", base + "/activities?status=OPEN", null, ownerToken).body(), "$" )).size());
		assertEquals(1, ((List<?>) JsonPath.read(request("GET", base + "/activities?status=COMPLETED", null, ownerToken).body(), "$" )).size());
		System.out.println("Engagement HTTP: staff writes=201/200, owner reads=200, owner writes/other customer reads=403, actor forgery ignored, stale/reopen=409");
	}

	@Test
	void eventTimePaginationHandlesBackdatedAndTiedEvents() {
		Instant tied = Instant.parse("2026-01-02T12:00:00.123Z");
		for (int i = 0; i < 5; i++) engagement.record(customer.id(), new CreateInteractionRequest(InteractionType.NOTE, "Tied " + i, null, tied, null), staff.id());
		engagement.record(customer.id(), new CreateInteractionRequest(InteractionType.EMAIL, "Earlier", null, tied.minusSeconds(100), null), staff.id());
		List<Interaction> all = new ArrayList<>();
		Instant beforeAt = null;
		String beforeId = null;
		for (int page = 0; page < 4; page++) {
			List<Interaction> batch = engagement.timeline(customer.id(), beforeAt, beforeId, 2);
			if (batch.isEmpty()) break;
			all.addAll(batch);
			beforeAt = batch.getLast().occurredAt();
			beforeId = batch.getLast().id();
		}
		assertEquals(6, all.size());
		assertEquals(6, all.stream().map(Interaction::id).distinct().count());
		assertEquals("Earlier", all.getLast().summary());
		assertEquals(5, all.stream().filter(value -> value.occurredAt().equals(tied)).count());
		assertThrows(IllegalArgumentException.class, () -> engagement.timeline(customer.id(), tied, null, 2));
		System.out.println("Timeline cursor: 6 events including 5 identical timestamps, page size 2, no duplicates or omissions; backdated event sorts by event time");
	}

	@Test
	void concurrentLoggingLosesNothingAndOnlyOneClosureWins() throws Exception {
		SalesActivity activity = engagement.createActivity(customer.id(), new CreateActivityRequest(ActivityType.TASK,
			"Concurrent task", null, Instant.now().plusSeconds(3600), null, null), staff.id());
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> writes = new ArrayList<>();
		List<Future<Boolean>> closures = new ArrayList<>();
		try (var executor = Executors.newFixedThreadPool(16)) {
			for (int i = 0; i < 32; i++) {
				writes.add(executor.submit(() -> {
					start.await();
					return engagement.record(customer.id(), new CreateInteractionRequest(InteractionType.NOTE, "Concurrent note", null, null, null), staff.id()).id();
				}));
				closures.add(executor.submit(() -> {
					start.await();
					try {
						engagement.changeActivityStatus(customer.id(), activity.id(), new ChangeActivityStatusRequest(ActivityStatus.COMPLETED, "Done", 0L), staff.id());
						return true;
					}
					catch (SalesException exception) {
						assertEquals(HttpStatus.CONFLICT, exception.status());
						return false;
					}
				}));
			}
			start.countDown();
			Set<String> unique = new HashSet<>();
			for (Future<String> write : writes) unique.add(write.get(10, TimeUnit.SECONDS));
			assertEquals(32, unique.size());
			int winners = 0;
			for (Future<Boolean> closure : closures) if (closure.get(10, TimeUnit.SECONDS)) winners++;
			assertEquals(1, winners);
		}
		assertEquals(32, router.templateFor(customer.id()).count(Query.query(Criteria.where("customerId").is(customer.id())), Interaction.class));
		assertEquals(1, engagement.activity(customer.id(), activity.id()).version());
		System.out.println("Concurrent engagement: 32 logs persisted, 32 distinct IDs; 32 task closure attempts -> 1 success, 31 conflicts, version 1");
	}

	@Test
	void historiesAndActivitiesStayWithCustomerAcrossAllShards() {
		Map<String, String> perShard = new TreeMap<>();
		for (int attempt = 0; perShard.size() < 3 && attempt < 1000; attempt++) {
			String id = Long.toString(ids.nextId());
			String shard = router.shardIdFor(id);
			if (perShard.containsKey(shard)) continue;
			Instant now = Instant.now();
			String email = "engagement-shard-" + id + "@example.com";
			router.templateFor(id).insert(new Customer(id, "Shard Fixture", email, "test-no-login", null, null, now, now, 0, "shard fixture"));
			fixtures.add(new CustomerResponse(id, shard, "Shard Fixture", email, null, null, now, now, 0));
			perShard.put(shard, id);
		}
		assertEquals(3, perShard.size());
		for (var entry : perShard.entrySet()) {
			String id = entry.getValue();
			for (int i = 0; i < 20; i++) engagement.record(id, new CreateInteractionRequest(InteractionType.NOTE, "Shard note", null, null, null), staff.id());
			for (int i = 0; i < 10; i++) engagement.createActivity(id, new CreateActivityRequest(ActivityType.TASK, "Shard task", null, Instant.now().plusSeconds(3600), null, null), staff.id());
			List<SalesActivity> first = engagement.activities(id, ActivityStatus.OPEN, null, 5);
			List<SalesActivity> second = engagement.activities(id, ActivityStatus.OPEN, first.getLast().id(), 5);
			assertEquals(10, java.util.stream.Stream.concat(first.stream(), second.stream()).map(SalesActivity::id).distinct().count());
			assertTrue(engagement.activities(id, ActivityStatus.OPEN, second.getLast().id(), 5).isEmpty());
			for (String shard : shards.shardIds()) {
				Query query = Query.query(Criteria.where("customerId").is(id));
				assertEquals(shard.equals(entry.getKey()) ? 20 : 0, shards.template(shard).count(query, Interaction.class));
				assertEquals(shard.equals(entry.getKey()) ? 10 : 0, shards.template(shard).count(query, SalesActivity.class));
			}
			assertEquals(0, router.templateFor(id).findById(id, Customer.class).version());
		}
		System.out.println("Physical engagement colocation: shard-0/1/2 each store 20 interactions + 10 activities for their fixture customer; profile version remains 0");
	}

	private CustomerResponse createCustomer(String name, String email) {
		CustomerResponse value = customers.create(new CreateCustomerRequest(name, email, "password-123", null, null));
		fixtures.add(value);
		return value;
	}

	private String login(String email) throws Exception {
		var response = request("POST", "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"password-123\"}", null);
		assertEquals(200, response.statusCode(), response.body());
		return JsonPath.read(response.body(), "$.accessToken");
	}

	private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) request.header("Content-Type", "application/json");
		if (token != null) request.header("Authorization", "Bearer " + token);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
