package com.raghu.crm.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateCustomerRequest(
	@NotBlank @Size(max = 120) String fullName,
	@Size(max = 30) String phone,
	@Size(max = 160) String company,
	@PositiveOrZero long version
) {
}
