package com.raghu.crm.customer;

import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomerAuthenticationTests {

	private final ShardRouter router = mock(ShardRouter.class);
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> emails = mock(ValueOperations.class);
	private final MongoTemplate mongo = mock(MongoTemplate.class);
	private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
	private CustomerService customers;

	@BeforeEach
	void setUp() {
		when(redis.opsForValue()).thenReturn(emails);
		customers = new CustomerService(mock(SnowflakeIdGenerator.class), router, redis, passwords, mock(CustomerProfileCache.class));
	}

	@Test
	void normalizedEmailAndCorrectPasswordAuthenticate() {
		knownCustomer();
		assertEquals("123", customers.authenticate(" DEMO@EXAMPLE.COM ", "password-123"));
	}

	@Test
	void wrongPasswordIsRejected() {
		knownCustomer();
		assertThrows(BadCredentialsException.class, () -> customers.authenticate("demo@example.com", "wrong"));
	}

	@Test
	void unknownEmailIsRejectedWithoutSearchingShards() {
		assertThrows(BadCredentialsException.class, () -> customers.authenticate("missing@example.com", "password-123"));
		verifyNoInteractions(router, mongo);
	}

	@Test
	void oversizedUnicodePasswordIsRejectedWithoutBcryptException() {
		knownCustomer();
		assertThrows(BadCredentialsException.class, () -> customers.authenticate("demo@example.com", "é".repeat(40)));
	}

	private void knownCustomer() {
		when(emails.get(CustomerService.emailKey("demo@example.com"))).thenReturn("123");
		when(router.templateFor("123")).thenReturn(mongo);
		when(mongo.findById("123", Customer.class)).thenReturn(new Customer(
			"123", "Demo", "demo@example.com", passwords.encode("password-123"),
			null, null, Instant.now(), Instant.now(), 0, "demo"));
	}
}
