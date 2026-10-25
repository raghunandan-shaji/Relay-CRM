package com.raghu.crm.engagement;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.raghu.crm.notifications.CustomerEvent;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("activities")
public record SalesActivity(@Id String id, String customerId, String leadId, ActivityType type,
	String title, String description, String representativeId, Instant dueAt, ActivityStatus status,
	Instant createdAt, String createdBy, Instant updatedAt, Instant closedAt, String closedBy, String outcome, long version,
	@JsonIgnore CustomerEvent pendingEvent) {
}
