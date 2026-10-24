package com.raghu.crm.sales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

public record AssignRepresentativeRequest(@NotBlank @Pattern(regexp = "[0-9]{1,19}") String representativeId,
	@NotNull @PositiveOrZero Long version) {
}
