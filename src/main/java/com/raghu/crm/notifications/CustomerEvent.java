package com.raghu.crm.notifications;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document("customer_events")
public record CustomerEvent(@Id String id, String customerId, String sourceType, String sourceId,
	String kind, String title, String body, String actorId, Instant occurredAt, boolean notifyCustomer, Instant readAt) {
}
