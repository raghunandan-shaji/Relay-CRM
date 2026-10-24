package com.raghu.crm.customer;

import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CustomerServiceCacheTests {

	private final ShardRouter router = mock(ShardRouter.class);
	private final CustomerProfileCache cache = mock(CustomerProfileCache.class);
	private final CustomerService service = new CustomerService(mock(SnowflakeIdGenerator.class), router,
		mock(StringRedisTemplate.class), new BCryptPasswordEncoder(4), cache);

	@Test
	void cacheHitAvoidsMongoEntirely() {
		Instant now = Instant.now();
		CustomerResponse profile = new CustomerResponse("123", "shard-0", "Demo", "demo@example.com", null, null, now, now, 1);
		when(cache.get("123")).thenReturn(profile);
		assertEquals(new CustomerService.ProfileRead(profile, "redis"), service.read("123"));
		verifyNoInteractions(router);
	}

	@Test
	void cacheMissReadsOneShardAndPopulatesCache() {
		MongoTemplate mongo = mock(MongoTemplate.class);
		when(router.templateFor("123")).thenReturn(mongo);
		when(router.shardIdFor("123")).thenReturn("shard-0");
		Customer customer = new Customer("123", "Demo", "demo@example.com", "hash", null, null, Instant.now(), Instant.now(), 0, "demo");
		when(mongo.findById("123", Customer.class)).thenReturn(customer);
		CustomerService.ProfileRead read = service.read("123");
		assertEquals("mongodb", read.source());
		verify(cache).put(read.customer());
	}
}
