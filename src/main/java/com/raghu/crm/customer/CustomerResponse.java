package com.raghu.crm.customer;

import java.time.Instant;

public record CustomerResponse(
	String id,
	String shardId,
	String fullName,
	String email,
	String phone,
	String company,
	Instant createdAt,
	Instant updatedAt,
	long version
) {
	static CustomerResponse from(Customer customer, String shardId) {
		return new CustomerResponse(
			customer.id(),
			shardId,
			customer.fullName(),
			customer.email(),
			customer.phone(),
			customer.company(),
			customer.createdAt(),
			customer.updatedAt(),
			customer.version());
	}
}
