package com.raghu.crm.customer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

	private final CustomerService customerService;

	public CustomerController(CustomerService customerService) {
		this.customerService = customerService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CustomerResponse create(@Valid @RequestBody CreateCustomerRequest request) {
		return customerService.create(request);
	}

	@GetMapping("/{id}")
	@PreAuthorize("#id == authentication.name")
	public CustomerResponse get(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String id) {
		return customerService.get(id);
	}

	@PutMapping("/{id}")
	@PreAuthorize("#id == authentication.name")
	public CustomerResponse update(
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String id,
			@Valid @RequestBody UpdateCustomerRequest request) {
		return customerService.update(id, request);
	}
}
