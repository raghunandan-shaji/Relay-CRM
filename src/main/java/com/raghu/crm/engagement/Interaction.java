package com.raghu.crm.engagement;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("interactions")
public record Interaction(@Id String id, String customerId, String leadId, InteractionType type,
	String summary, String details, Instant occurredAt, Instant recordedAt, String recordedBy) {
}
