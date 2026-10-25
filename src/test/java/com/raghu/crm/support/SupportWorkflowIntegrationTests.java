package com.raghu.crm.support;

import com.jayway.jsonpath.JsonPath;
import com.raghu.crm.customer.*;
import com.raghu.crm.engagement.*;
import com.raghu.crm.notifications.*;
import com.raghu.crm.sales.SalesException;
import com.raghu.crm.systemdesign.sharding.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="RUN_INTEGRATION_TESTS",matches="true")
class SupportWorkflowIntegrationTests {
	private static final String STAFF = "support-staff-"+UUID.randomUUID()+"@example.com";
	@DynamicPropertySource static void settings(DynamicPropertyRegistry props) {
		props.add("crm.auth.staff-emails",()->STAFF);
		String prefix="crm:test:support:"+UUID.randomUUID()+":";
		props.add("crm.rate-limit.key-prefix",()->prefix);
		props.add("crm.rate-limit.api",()->1000);
	}
	@LocalServerPort int port;
	@Autowired CustomerService customers;
	@Autowired CustomerProfileCache cache;
	@Autowired TicketService tickets;
	@Autowired EngagementService engagement;
	@MockitoSpyBean OutboxService outbox;
	@Autowired ShardRouter router;
	@Autowired MongoShardRegistry shards;
	@Autowired SnowflakeIdGenerator ids;
	@Autowired StringRedisTemplate redis;
	@Autowired JsonMapper mapper;
	final List<CustomerResponse> fixtures=new ArrayList<>();
	final HttpClient http=HttpClient.newHttpClient();
	CustomerResponse staff,owner,other;
	@BeforeEach void seed(){
		tickets.initializeIndexes();outbox.initializeIndexes();engagement.initializeIndexes(shards);
		staff=create("Support Staff",STAFF);owner=create("Ticket Owner","support-owner-"+UUID.randomUUID()+"@example.com");other=create("Other Customer","support-other-"+UUID.randomUUID()+"@example.com");
	}
	@AfterEach void cleanup(){
		List<String> all=fixtures.stream().map(CustomerResponse::id).toList();
		for(String shard:shards.shardIds()){
			for(Class<?> type:List.of(SupportTicket.class,CustomerEvent.class,SalesActivity.class))shards.template(shard).remove(Query.query(Criteria.where("customerId").in(all)),type);
			shards.template(shard).remove(Query.query(Criteria.where("_id").in(all)),Customer.class);
		}
		for(CustomerResponse fixture:fixtures)redis.delete(List.of("crm:customer-email:"+fixture.email(),cache.key(fixture.id())));
	}
	@Test void realHttpTicketConversationAndInboxRespectOwnership()throws Exception{
		String customer=login(owner.email()),employee=login(staff.email()),outsider=login(other.email());String base="/api/customers/"+owner.id();
		assertEquals(200,request("GET","/",null,null).statusCode());
		for(String asset:List.of("app.js","ui.js","styles.css","favicon.svg"))assertEquals(200,request("GET","/"+asset,null,null).statusCode());
		assertEquals(401,request("GET",base+"/tickets",null,null).statusCode());
		assertEquals(403,request("GET",base+"/tickets",null,outsider).statusCode());
		assertEquals(403,request("GET","/api/support/tickets",null,customer).statusCode());
		var created=request("POST",base+"/tickets","{\"subject\":\"Cannot sign in\",\"message\":\"Please help\",\"priority\":\"HIGH\",\"createdBy\":\"999\"}",customer);
		assertEquals(201,created.statusCode(),created.body());assertFalse(created.body().contains("pendingEvent"));
		SupportTicket ticket=mapper.readValue(created.body(),SupportTicket.class);String path=base+"/tickets/"+ticket.id();
		assertEquals(owner.id(),ticket.createdBy());assertEquals(staff.id(),ticket.representativeId());
		assertEquals(200,request("GET","/api/support/tickets?status=OPEN",null,employee).statusCode());
		assertEquals(403,request("PUT",path,"{\"status\":\"CLOSED\",\"version\":0}",customer).statusCode());
		assertEquals(403,request("POST",path+"/messages","{\"message\":\"Blocked\",\"version\":0}",outsider).statusCode());
		assertEquals(200,request("POST",path+"/messages","{\"message\":\"More information\",\"version\":0}",customer).statusCode());
		assertEquals(200,request("POST",path+"/messages","{\"message\":\"Try a new token\",\"version\":1}",employee).statusCode());
		assertEquals(409,request("POST",path+"/messages","{\"message\":\"Stale\",\"version\":1}",customer).statusCode());
		assertEquals(3,((List<?>)JsonPath.read(request("GET",path+"/messages",null,customer).body(),"$")).size());
		assertEquals(200,request("PUT",path,"{\"status\":\"RESOLVED\",\"version\":2}",employee).statusCode());
		assertEquals(409,request("PUT",path,"{\"status\":\"OPEN\",\"version\":3}",employee).statusCode());
		assertEquals(200,request("PUT",path,"{\"status\":\"CLOSED\",\"version\":3}",employee).statusCode());
		assertEquals(409,request("POST",path+"/messages","{\"message\":\"Closed\",\"version\":4}",customer).statusCode());
		String inbox=base+"/notifications";var notifications=request("GET",inbox,null,customer);
		assertEquals(200,notifications.statusCode());assertEquals(4,((List<?>)JsonPath.read(notifications.body(),"$")).size());
		String eventId=JsonPath.read(notifications.body(),"$[0].id");
		assertEquals(403,request("GET",inbox,null,employee).statusCode());assertEquals(403,request("GET",inbox,null,outsider).statusCode());
		String read=inbox+"/"+eventId+"/read";var first=request("PUT",read,null,customer);var second=request("PUT",read,null,customer);
		assertEquals(200,first.statusCode());assertEquals(first.body(),second.body());assertNotNull(JsonPath.read(first.body(),"$.readAt"));
		assertEquals(403,request("PUT",read,null,outsider).statusCode());
		assertEquals(400,request("POST",base+"/tickets","{\"subject\":\"Bad\",\"message\":\"\",\"priority\":\"NORMAL\"}",customer).statusCode());
		assertEquals(400,request("GET",base+"/tickets?limit=100",null,customer).statusCode());
		System.out.println("Support HTTP: customer create/reply/read pass; staff queue/status/reply pass; foreign access and customer status changes=403; stale/closed replies=409; inbox read acknowledgement idempotent");
	}
	@Test void committedOutboxSurvivesWorkerRestartAndDuplicateDeliveryKeepsReadAcknowledgement(){
		doNothing().when(outbox).tryDelivery(anyString(),anyString(),anyString());
		SupportTicket ticket=tickets.create(owner.id(),new CreateTicketRequest("Recovery","Durable message",TicketPriority.NORMAL),owner.id());
		SupportTicket persisted=tickets.get(owner.id(),ticket.id());assertNotNull(persisted.pendingEvent());
		assertEquals(0,eventCount(owner.id()));
		OutboxService restarted=new OutboxService(router,shards,ids,new SimpleMeterRegistry(),true);
		restarted.recover();assertEquals(1,eventCount(owner.id()));assertNull(tickets.get(owner.id(),ticket.id()).pendingEvent());
		// Simulate a crash after publishing but before the source event was cleared.
		CustomerEvent event=persisted.pendingEvent();Instant readAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		router.templateFor(owner.id()).updateFirst(Query.query(Criteria.where("_id").is(event.id())),new Update().set("readAt",readAt),CustomerEvent.class);
		router.templateFor(owner.id()).updateFirst(Query.query(Criteria.where("_id").is(ticket.id())),new Update().set("pendingEvent",event),SupportTicket.class);
		restarted.recover();restarted.recover();assertEquals(1,eventCount(owner.id()));assertNull(tickets.get(owner.id(),ticket.id()).pendingEvent());
		assertEquals(readAt,router.templateFor(owner.id()).findById(event.id(),CustomerEvent.class).readAt());
		System.out.println("Outbox recovery: committed pending event delivered by restarted worker; crash-after-insert replay creates no duplicate and preserves readAt");
	}
	@Test void concurrentRepliesHaveOneWinnerAndOneNewEvent()throws Exception{
		SupportTicket ticket=tickets.create(owner.id(),new CreateTicketRequest("Concurrent","Initial",TicketPriority.NORMAL),owner.id());
		CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> attempts=new ArrayList<>();
		try(var pool=Executors.newFixedThreadPool(16)){
			for(int i=0;i<32;i++)attempts.add(pool.submit(()->{start.await();try{tickets.reply(owner.id(),ticket.id(),new TicketReplyRequest("Reply",0L),staff.id(),true);return true;}catch(SalesException exception){assertEquals(HttpStatus.CONFLICT,exception.status());return false;}}));
			start.countDown();int winners=0;for(var attempt:attempts)if(attempt.get(10,TimeUnit.SECONDS))winners++;assertEquals(1,winners);
		}
		assertEquals(1,tickets.get(owner.id(),ticket.id()).version());assertEquals(2,eventCount(owner.id()));
		System.out.println("Concurrent support replies: 32 attempts, 1 success, 31 conflicts, exactly 1 new reply event");
	}
	@Test void taskNotificationsAndTicketEventsAreColocatedAcrossThreeShards(){
		Map<String,String> placed=new TreeMap<>();
		for(int i=0;placed.size()<3&&i<1000;i++){
			String id=Long.toString(ids.nextId()),shard=router.shardIdFor(id);if(placed.containsKey(shard))continue;
			Instant now=Instant.now();String email="support-shard-"+id+"@example.com";
			router.templateFor(id).insert(new Customer(id,"Shard Owner",email,"no-login",null,null,now,now,0,"shard owner"));
			fixtures.add(new CustomerResponse(id,shard,"Shard Owner",email,null,null,now,now,0));placed.put(shard,id);
		}
		assertEquals(3,placed.size());
		for(var entry:placed.entrySet()){
			String id=entry.getValue();SupportTicket ticket=tickets.create(id,new CreateTicketRequest("Shard ticket","Message",TicketPriority.NORMAL),id);
			tickets.reply(id,ticket.id(),new TicketReplyRequest("Staff reply",0L),staff.id(),true);
			SalesActivity task=engagement.createActivity(id,new CreateActivityRequest(ActivityType.TASK,"Follow-up",null,Instant.now().plusSeconds(3600),null,null),staff.id());
			engagement.changeActivityStatus(id,task.id(),new ChangeActivityStatusRequest(ActivityStatus.COMPLETED,"Done",0L),staff.id());
			for(String shard:shards.shardIds()){
				Query key=Query.query(Criteria.where("customerId").is(id));
				assertEquals(shard.equals(entry.getKey())?1:0,shards.template(shard).count(key,SupportTicket.class));
				assertEquals(shard.equals(entry.getKey())?4:0,shards.template(shard).count(key,CustomerEvent.class));
			}
		}
		System.out.println("Support/task notifications: each of 3 physical shards holds its customer's ticket plus 4 events, none on the other shards");
	}
	long eventCount(String id){return router.templateFor(id).count(Query.query(Criteria.where("customerId").is(id)),CustomerEvent.class);}
	CustomerResponse create(String name,String email){var customer=customers.create(new CreateCustomerRequest(name,email,"password-123",null,null));fixtures.add(customer);return customer;}
	String login(String email)throws Exception{var result=request("POST","/api/auth/login","{\"email\":\""+email+"\",\"password\":\"password-123\"}",null);assertEquals(200,result.statusCode(),result.body());return JsonPath.read(result.body(),"$.accessToken");}
	HttpResponse<String> request(String method,String path,String body,String token)throws Exception{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));if(body!=null)request.header("Content-Type","application/json");if(token!=null)request.header("Authorization","Bearer "+token);return http.send(request.build(),HttpResponse.BodyHandlers.ofString());}
}
