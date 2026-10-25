package com.raghu.crm;

import com.raghu.crm.customer.*;
import com.raghu.crm.engagement.*;
import com.raghu.crm.notifications.*;
import com.raghu.crm.sales.*;
import com.raghu.crm.support.*;
import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Persistent synthetic demonstration data, explicitly enabled by the local operator. */
@SpringBootTest(properties={"crm.id.worker-id=91", "crm.auth.staff-emails=raghu.demo@crm.local,ananya.iyer@demo.relay.test,arjun.nair@demo.relay.test,kavya.menon@demo.relay.test,rohan.kulkarni@demo.relay.test,neha.sharma@demo.relay.test,vikram.rao@demo.relay.test"})
@EnabledIfEnvironmentVariable(named="RUN_DEMO_SEED",matches="true")
class DemoDataSeedTests {
	private static final String MARKER="relay-demo-v1";
	private static final String PASSWORD="demo-password-123";
	private static final List<String> STAFF=List.of("Ananya Iyer","Arjun Nair","Kavya Menon","Rohan Kulkarni","Neha Sharma","Vikram Rao");
	private static final List<String> NAMES=List.of(
		"Aarav Mehta","Diya Nair","Aditya Rao","Ishita Sen","Rohan Deshmukh","Kavya Pillai","Arjun Reddy","Ananya Menon","Vikram Bhat","Meera Iyer",
		"Rahul Suresh","Nisha Patel","Siddharth Joshi","Pooja Nambiar","Karthik Subramanian","Sneha Kulkarni","Varun Krishnan","Aditi Sharma","Nikhil Verma","Riya Shah",
		"Akash Gupta","Divya Prakash","Sanjay Kumar","Swathi Srinivasan","Prateek Bansal","Lakshmi Narayanan","Abhishek Singh","Priya Thomas","Manish Agarwal","Deepa Raman",
		"Saurabh Mishra","Shreya Kapoor","Harish Nair","Anjali Das","Gautham Menon","Neha Chatterjee","Vivek Patil","Aishwarya Rao","Rohit Malhotra","Keerthana Ravi",
		"Ashwin Rajan","Tanvi Kulkarni","Dinesh Babu","Sana Khan","Mohit Jain","Bhavana Reddy","Yash Desai","Malavika Krishnan","Sameer Sheikh","Gayathri Suresh");
	private static final List<String> COMPANIES=List.of("Aavishkar Textiles · Bengaluru","Nila Analytics · Chennai","Malabar Foods · Kochi","Triveni Logistics · Pune","Sahyadri Retail · Mumbai","Udaya Healthcare · Hyderabad","Kaveri Engineering · Coimbatore","Navrang Studios · Ahmedabad","Pragati Learning · Delhi","Konkan Hospitality · Mangaluru");
	@Autowired ShardRouter router;
	@Autowired MongoShardRegistry shards;
	@Autowired SnowflakeIdGenerator ids;
	@Autowired PasswordEncoder passwords;
	@Autowired StringRedisTemplate redis;
	@Autowired LeadService leads;
	@Autowired EngagementService engagement;
	@Autowired TicketService tickets;
	@Autowired OutboxService outbox;
	@Autowired CustomerSearchService search;
	@Autowired JsonMapper json;
	private String hash;
	private final List<Customer> accounts=new ArrayList<>();

	@Test void seedPersistentDemoDataWithoutResettingExistingRecords() throws Exception {
		Files.createDirectories(Path.of(".local"));
		try(var channel=FileChannel.open(Path.of(".local/demo-seed.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
			var lock=channel.tryLock()) {
			assertNotNull(lock,"Another demo seeder is already running");
			hash=passwords.encode(PASSWORD);
			leads.initializeIndexes(shards);engagement.initializeIndexes(shards);tickets.initializeIndexes();outbox.initializeIndexes();search.initialize();
			List<Customer> staff=new ArrayList<>();
			for(String name:STAFF)staff.add(account(name,"Relay CRM · Sales & Support",null));
			for(int index=0;index<NAMES.size();index++) {
				Customer customer=account(NAMES.get(index),COMPANIES.get(index%COMPANIES.size()),"+91 00000 "+String.format(Locale.ROOT,"%05d",1000+index));
				String representative=staff.get(index%staff.size()).id();
				seedLeads(customer,representative,index);seedHistory(customer,representative,index);seedActivities(customer,representative,index);seedTickets(customer,representative,index);
			}
			List<String> customerIds=accounts.subList(STAFF.size(),accounts.size()).stream().map(Customer::id).toList();
			Map<String,Object> evidence=new LinkedHashMap<>();
			evidence.put("seed",MARKER);evidence.put("staff",staff.stream().map(account->Map.of("name",account.fullName(),"email",account.email(),"id",account.id())).toList());
			evidence.put("customers",accounts.stream().filter(account->customerIds.contains(account.id())).map(account->Map.of("name",account.fullName(),"email",account.email(),"id",account.id(),"company",account.company())).toList());
			Map<String,Long> distribution=new TreeMap<>(),counts=new LinkedHashMap<>();
			for(Customer account:accounts)distribution.merge(router.shardIdFor(account.id()),1L,Long::sum);
			for(Class<?> type:List.of(Lead.class,Interaction.class,SalesActivity.class,SupportTicket.class,CustomerEvent.class)) {
				long count=0;for(String shard:shards.shardIds())count+=shards.template(shard).count(Query.query(Criteria.where("customerId").in(customerIds)),type);
				counts.put(type.getSimpleName(),count);
			}
			long opportunities=0;for(String shard:shards.shardIds())opportunities+=shards.template(shard).count(Query.query(Criteria.where("customerId").in(customerIds).and("opportunity").ne(null)),Lead.class);
			counts.put("Opportunity",opportunities);evidence.put("counts",counts);evidence.put("distribution",distribution);
			assertEquals(56,accounts.size());assertTrue(counts.get("Lead")>=100);assertTrue(counts.get("Interaction")>=200);assertTrue(counts.get("SalesActivity")>=100);assertTrue(counts.get("SupportTicket")>=63);
			Files.writeString(Path.of(".local/demo-accounts.json"),json.writeValueAsString(evidence));
			System.out.println("Persistent demo seed: 6 staff + 50 customers; records="+counts+"; account shards="+distribution);
		}
	}

	private Customer account(String name,String company,String phone) {
		String email=name.toLowerCase(Locale.ROOT).replace(' ','.')+"@demo.relay.test";
		for(String shard:shards.shardIds()) {
			Document existing=shards.template(shard).findOne(Query.query(Criteria.where("email").is(email)),Document.class,"customers");
			if(existing!=null) {
				assertEquals(MARKER,existing.getString("demoSeed"),"Refusing to modify an existing, non-demo account: "+email);
				Customer found=shards.template(shard).getConverter().read(Customer.class,existing);
				String indexed=redis.opsForValue().get("crm:customer-email:"+email);
				if(indexed==null)assertTrue(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("crm:customer-email:"+email,found.id())));
				else assertEquals(found.id(),indexed,"Email index belongs to another record");
				accounts.add(found);return found;
			}
		}
		String id=Long.toString(ids.nextId()),key="crm:customer-email:"+email;
		assertTrue(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,id)),"Email is already reserved; investigate before retrying: "+email);
		Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Customer customer=new Customer(id,name,email,hash,phone,company,now,now,0,name.toLowerCase(Locale.ROOT));
		Document document=new Document();router.templateFor(id).getConverter().write(customer,document);document.put("demoSeed",MARKER);
		router.templateFor(id).insert(document,"customers");accounts.add(customer);return customer;
	}

	private void seedLeads(Customer customer,String representative,int index) {
		for(int item=0;item<2;item++) {
			String title=item==0?"Customer workspace rollout":"Annual support subscription";
			if(exists(customer.id(),Lead.class,"title",title))continue;
			Lead lead=leads.create(customer.id(),new CreateLeadRequest(title,List.of("Website","Referral","Industry event","Partner").get(index%4),"Enquiry from "+customer.company()+". Customer prefers a guided demonstration.",representative));
			if(item==1||index%4==2) {
				lead=leads.qualify(customer.id(),lead.id(),new CreateOpportunityRequest(new BigDecimal(35000+index*2500+item*18000).setScale(2),"INR",lead.version()));
				OpportunityStage stage=OpportunityStage.values()[index%5];
				if(stage!=OpportunityStage.OPEN)leads.changeOpportunityStage(customer.id(),lead.id(),new ChangeOpportunityStageRequest(stage,lead.version()));
			} else if(index%4==1)leads.changeStatus(customer.id(),lead.id(),new ChangeLeadStatusRequest(LeadStatus.CONTACTED,lead.version()));
			else if(index%4==3)leads.changeStatus(customer.id(),lead.id(),new ChangeLeadStatusRequest(LeadStatus.LOST,lead.version()));
		}
	}

	private void seedHistory(Customer customer,String representative,int index) {
		String first=customer.fullName().split(" ")[0];
		List<String> summaries=List.of("Discovery call with "+first,"Shared proposal and pricing","Product demonstration completed","Recorded onboarding requirements");
		List<String> details=List.of("Discussed customer records and handover between regional sales teams.","Shared an INR quotation and implementation outline for "+customer.company()+".","Walked through lead tracking, customer history, and the support portal.","Customer wants role-based access, follow-up reminders, and a shared communication history.");
		for(int item=0;item<4;item++)if(!exists(customer.id(),Interaction.class,"summary",summaries.get(item)))engagement.record(customer.id(),new CreateInteractionRequest(InteractionType.values()[item],summaries.get(item),details.get(item),Instant.now().minusSeconds((long)(14-item*3)*86400+index*60),null),representative);
	}

	private void seedActivities(Customer customer,String representative,int index) {
		for(int item=0;item<2;item++) {
			String title=item==0?"Follow up on the implementation proposal":"Prepare customer onboarding checklist";
			if(exists(customer.id(),SalesActivity.class,"title",title))continue;
			SalesActivity activity=engagement.createActivity(customer.id(),new CreateActivityRequest(item==0?ActivityType.CALL:ActivityType.TASK,title,
				item==0?"Confirm the rollout scope and answer pricing questions.":"Confirm team members, training dates, and the initial customer import.",Instant.now().plusSeconds((long)(1+index%7)*86400),null,representative),representative);
			if(item==1&&index%3!=2)engagement.changeActivityStatus(customer.id(),activity.id(),new ChangeActivityStatusRequest(index%3==0?ActivityStatus.COMPLETED:ActivityStatus.CANCELLED,index%3==0?"Checklist shared and training slot confirmed.":"Customer requested a revised onboarding date.",activity.version()),representative);
		}
	}

	private void seedTickets(Customer customer,String representative,int index) {
		for(int item=0;item<(index%4==0?2:1);item++) {
			String subject=item==0?List.of("Help importing customer contacts","Clarification on team permissions","Need assistance with reports","Update company billing details").get(index%4):"Schedule onboarding training";
			if(exists(customer.id(),SupportTicket.class,"subject",subject))continue;
			SupportTicket ticket=tickets.create(customer.id(),new CreateTicketRequest(subject,item==0?"Please guide our team through this setup. We would like to start using the workspace this week.":"Can you arrange a training session for our regional team?",TicketPriority.values()[index%4]),customer.id());
			if(!representative.equals(ticket.representativeId()))ticket=tickets.update(customer.id(),ticket.id(),new ChangeTicketRequest(null,representative,ticket.version()),representative);
			ticket=tickets.reply(customer.id(),ticket.id(),new TicketReplyRequest("We have reviewed your request and shared the next steps. Your representative will help with the setup.",ticket.version()),representative,true);
			if(index%2==0)ticket=tickets.reply(customer.id(),ticket.id(),new TicketReplyRequest("Thank you. We have shared the information with our team.",ticket.version()),customer.id(),false);
			TicketStatus status=TicketStatus.values()[(index+item)%4];
			if(status!=TicketStatus.OPEN)tickets.update(customer.id(),ticket.id(),new ChangeTicketRequest(status,null,ticket.version()),representative);
		}
	}

	private boolean exists(String customerId,Class<?> type,String field,String value) {
		// shortcut: preserve existing records; repair partially initialized records explicitly after an interrupted seed.
		return router.templateFor(customerId).exists(Query.query(Criteria.where("customerId").is(customerId).and(field).is(value)),type);
	}
}
