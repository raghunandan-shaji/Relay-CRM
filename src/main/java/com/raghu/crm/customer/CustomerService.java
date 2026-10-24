package com.raghu.crm.customer;

import com.raghu.crm.systemdesign.id.SnowflakeIdGenerator;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
public class CustomerService {

	private static final String EMAIL_KEY_PREFIX = "crm:customer-email:";

	private final SnowflakeIdGenerator idGenerator;
	private final ShardRouter shardRouter;
	private final StringRedisTemplate redis;
	private final PasswordEncoder passwordEncoder;
	private final String dummyPasswordHash;

	public CustomerService(SnowflakeIdGenerator idGenerator, ShardRouter shardRouter,
			StringRedisTemplate redis, PasswordEncoder passwordEncoder) {
		this.idGenerator = idGenerator;
		this.shardRouter = shardRouter;
		this.redis = redis;
		this.passwordEncoder = passwordEncoder;
		this.dummyPasswordHash = passwordEncoder.encode("dummy-login-timing-check");
	}

	public CustomerResponse create(CreateCustomerRequest request) {
		String id = Long.toString(idGenerator.nextId());
		String email = normalizeEmail(request.email());
		String emailKey = emailKey(email);
		String passwordHash = passwordEncoder.encode(request.password());
		Boolean reserved = redis.opsForValue().setIfAbsent(emailKey, id);
		if (!Boolean.TRUE.equals(reserved)) {
			throw new DuplicateEmailException(email);
		}

		Instant now = now();
		Customer customer = new Customer(
			id,
			request.fullName().trim(),
			email,
			passwordHash,
			nullIfBlank(request.phone()),
			nullIfBlank(request.company()),
			now,
			now,
			0);

		try {
			shardRouter.templateFor(id).insert(customer);
		}
		catch (RuntimeException exception) {
			if (id.equals(redis.opsForValue().get(emailKey))) {
				redis.delete(emailKey);
			}
			throw exception;
		}

		return CustomerResponse.from(customer, shardRouter.shardIdFor(id));
	}

	public CustomerResponse get(String id) {
		MongoTemplate template = shardRouter.templateFor(id);
		Customer customer = template.findById(id, Customer.class);
		if (customer == null) {
			throw new CustomerNotFoundException(id);
		}
		return CustomerResponse.from(customer, shardRouter.shardIdFor(id));
	}

	public String authenticate(String email, String password) {
		String id = redis.opsForValue().get(emailKey(email));
		Customer customer = id == null ? null : shardRouter.templateFor(id).findById(id, Customer.class);
		String hash = customer == null ? dummyPasswordHash : customer.passwordHash();
		boolean validLength = password.getBytes(StandardCharsets.UTF_8).length <= 72;
		boolean matches = passwordEncoder.matches(validLength ? password : "", hash);
		if (!validLength || !matches || customer == null) {
			throw new BadCredentialsException("Invalid email or password");
		}
		return customer.id();
	}

	public CustomerResponse update(String id, UpdateCustomerRequest request) {
		MongoTemplate template = shardRouter.templateFor(id);
		Query query = Query.query(Criteria.where("_id").is(id).and("version").is(request.version()));
		Update update = new Update()
			.set("fullName", request.fullName().trim())
			.set("phone", nullIfBlank(request.phone()))
			.set("company", nullIfBlank(request.company()))
			.set("updatedAt", now())
			.inc("version", 1);

		Customer customer = template.findAndModify(
			query,
			update,
			FindAndModifyOptions.options().returnNew(true),
			Customer.class);
		if (customer != null) {
			return CustomerResponse.from(customer, shardRouter.shardIdFor(id));
		}
		if (!template.exists(Query.query(Criteria.where("_id").is(id)), Customer.class)) {
			throw new CustomerNotFoundException(id);
		}
		throw new ProfileVersionConflictException(id);
	}

	static String emailKey(String email) {
		return EMAIL_KEY_PREFIX + normalizeEmail(email);
	}

	private static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static String nullIfBlank(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MILLIS);
	}
}
