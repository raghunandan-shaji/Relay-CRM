package com.raghu.crm.sales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLeadRequest(@NotBlank @Size(max = 160) String title,
	@Size(max = 80) String source, @Size(max = 1000) String notes,
	@Pattern(regexp = "[0-9]{1,19}") String representativeId) {
}
