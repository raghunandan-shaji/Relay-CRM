package com.raghu.crm;

import com.raghu.crm.customer.*;
import com.raghu.crm.engagement.*;
import com.raghu.crm.notifications.CustomerEvent;
import com.raghu.crm.sales.*;
import com.raghu.crm.support.*;
import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opted-in laboratory fixture tool; never runs as an application endpoint. */
@SpringBootTest(properties="crm.id.worker-id=90")
@EnabledIfEnvironmentVariable(named="RUN_BENCHMARK_SEED",matches="true")
class BenchmarkFixtureToolTests {
	@Autowired MongoShardRegistry shards;
	@Autowired ShardRouter router;
	@Autowired SnowflakeIdGenerator ids;
	@Autowired PasswordEncoder passwords;
	@Autowired StringRedisTemplate redis;
	@Autowired JsonMapper json;
	@Autowired CustomerProfileCache cache;
	@Autowired LeadService leads;
	@Autowired EngagementService engagement;
	@Autowired TicketService tickets;
	static final Path PATH=Path.of(".local/benchmark-fixtures.json");
	record Manifest(String runId,String staffEmail,String staffId,List<String> customerIds,Map<String,Long> distribution){}

	@Test void prepareOrRemoveIsolatedBenchmarkFixtures()throws Exception{
		Manifest previous=Files.exists(PATH)?json.readValue(Files.readString(PATH),Manifest.class):null;
		if(Boolean.getBoolean("crm.benchmark.cleanup")){
			if(previous==null)return;
			List<String> all=new ArrayList<>(previous.customerIds());all.add(previous.staffId());
			for(String shard:shards.shardIds()){
				var template=shards.template(shard);
				for(Customer customer:template.find(Query.query(Criteria.where("_id").in(all)),Customer.class))redis.delete(List.of("crm:customer-email:"+customer.email(),cache.key(customer.id())));
				for(Class<?> type:List.of(Interaction.class,SalesActivity.class,Lead.class,SupportTicket.class,CustomerEvent.class))template.remove(Query.query(Criteria.where("customerId").in(all)),type);
				template.remove(Query.query(Criteria.where("_id").in(all)),Customer.class);
			}
			Files.delete(PATH);System.out.println("Removed only benchmark fixture customers and their records: "+all.size());return;
		}
		int target=Integer.getInteger("crm.benchmark.customers",3000);assertTrue(target>=60&&target<=100000);
		String runId=previous==null?UUID.randomUUID().toString():previous.runId();
		String staffId=previous==null?Long.toString(ids.nextId()):previous.staffId();String staffEmail="benchmark-"+runId+"@crm.local";
		List<String> customerIds=new ArrayList<>(previous==null?List.of():previous.customerIds());int oldSize=customerIds.size();
		assertTrue(target>=oldSize,"Cleanup existing fixtures before shrinking the dataset");
		while(customerIds.size()<target)customerIds.add(Long.toString(ids.nextId()));
		Map<String,Long> counts=new TreeMap<>();for(String id:customerIds)counts.merge(router.shardIdFor(id),1L,Long::sum);
		Files.createDirectories(PATH.getParent());Files.writeString(PATH,json.writeValueAsString(new Manifest(runId,staffEmail,staffId,customerIds,counts)));
		Instant now=Instant.now();String hash=passwords.encode("benchmark-password-123");
		if(previous==null){router.templateFor(staffId).insert(new Customer(staffId,"Benchmark Staff",staffEmail,hash,null,"Benchmark",now,now,0,"benchmark staff"));redis.opsForValue().set("crm:customer-email:"+staffEmail,staffId);}
		for(String shard:shards.shardIds()){
			List<Customer> profiles=new ArrayList<>();List<Interaction> history=new ArrayList<>();
			for(int i=oldSize;i<customerIds.size();i++){
				String id=customerIds.get(i);if(!router.shardIdFor(id).equals(shard))continue;
				String name=String.format(Locale.ROOT,"Benchmark Customer %06d",i),email="benchmark-"+runId+"-"+i+"@crm.local";
				profiles.add(new Customer(id,name,email,hash,null,"Benchmark",now,now,0,name.toLowerCase(Locale.ROOT)));
				for(int event=0;event<10;event++)history.add(new Interaction(Long.toString(ids.nextId()),id,null,InteractionType.NOTE,"Benchmark history "+event,"Synthetic fixture",now.minusSeconds(event),now,staffId));
				redis.opsForValue().set("crm:customer-email:"+email,id);
			}
			if(!profiles.isEmpty())shards.template(shard).insert(profiles,Customer.class);
			if(!history.isEmpty())shards.template(shard).insert(history,Interaction.class);
		}
		leads.initializeIndexes(shards);engagement.initializeIndexes(shards);tickets.initializeIndexes();
		// Search initialization also builds its index and normalized-name backfill in normal application startup.
		for(String shard:shards.shardIds())shards.template(shard).indexOps(Customer.class).createIndex(
			new org.springframework.data.mongodb.core.index.Index().on("nameKey",org.springframework.data.domain.Sort.Direction.ASC)
				.on("_id",org.springframework.data.domain.Sort.Direction.ASC).named("customer_name_prefix"));
		assertEquals(target,customerIds.size());System.out.println("Benchmark customers="+target+", initial interactions="+(target*10)+", physical distribution="+counts);
	}
}
