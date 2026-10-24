package com.raghu.crm.customer;

import org.springframework.data.annotation.Id;

public record CustomerSummary(@Id String id, String fullName, String email, String company, String shardId) {
}
