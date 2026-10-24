package com.raghu.crm.engagement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateInteractionRequest(@NotNull InteractionType type,
	@NotBlank @Size(max = 160) String summary, @Size(max = 4000) String details,
	@PastOrPresent Instant occurredAt, @Pattern(regexp = "[0-9]{1,19}") String leadId) {
}
