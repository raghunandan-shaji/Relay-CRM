package com.raghu.crm.sales;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("leads")
public record Lead(@Id String id, String customerId, String title, String source, String notes,
	LeadStatus status, String representativeId, Opportunity opportunity,
	Instant createdAt, Instant updatedAt, long version) {
}
