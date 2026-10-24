package com.raghu.crm.engagement;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateActivityRequest(@NotNull ActivityType type,
	@NotBlank @Size(max = 160) String title, @Size(max = 4000) String description,
	@NotNull @FutureOrPresent Instant dueAt, @Pattern(regexp = "[0-9]{1,19}") String leadId,
	@Pattern(regexp = "[0-9]{1,19}") String representativeId) {
}
