package com.raghu.crm.support;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.raghu.crm.notifications.CustomerEvent;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document("tickets")
public record SupportTicket(@Id String id, String customerId, String subject, TicketPriority priority,
	TicketStatus status, String representativeId, Instant createdAt, Instant updatedAt, String createdBy,
	long version, @JsonIgnore CustomerEvent pendingEvent) {
}
