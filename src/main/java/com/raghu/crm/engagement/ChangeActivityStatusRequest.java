package com.raghu.crm.engagement;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ChangeActivityStatusRequest(@NotNull ActivityStatus status,
	@Size(max = 1000) String outcome, @NotNull @PositiveOrZero Long version) {
}
