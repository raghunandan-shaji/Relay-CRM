package com.raghu.crm.customer;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("customers")
public record Customer(
	@Id String id,
	String fullName,
	String email,
	String passwordHash,
	String phone,
	String company,
	Instant createdAt,
	Instant updatedAt,
	long version
) {
}
