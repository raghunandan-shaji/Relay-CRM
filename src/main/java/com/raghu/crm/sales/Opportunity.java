package com.raghu.crm.sales;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;

public record Opportunity(String id, @Field(targetType = FieldType.DECIMAL128) BigDecimal amount,
	String currency, OpportunityStage stage, Instant createdAt, Instant updatedAt) {
}
