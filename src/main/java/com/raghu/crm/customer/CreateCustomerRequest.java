package com.raghu.crm.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record CreateCustomerRequest(
	@NotBlank @Size(max = 120) String fullName,
	@NotBlank @Email @Size(max = 254) String email,
	@NotBlank @Size(min = 8, max = 72) String password,
	@Size(max = 30) String phone,
	@Size(max = 160) String company
) {
	@AssertTrue(message = "password must be at most 72 UTF-8 bytes")
	public boolean isPasswordWithinByteLimit() {
		return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
	}
}
