package com.raghu.crm.customer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

	private final CustomerService customerService;
	private final CustomerSearchService search;

	public CustomerController(CustomerService customerService, CustomerSearchService search) {
		this.customerService = customerService;
		this.search = search;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CustomerResponse create(@Valid @RequestBody CreateCustomerRequest request) {
		return customerService.create(request);
	}

	@GetMapping("/{id}")
	@PreAuthorize("#id == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public ResponseEntity<CustomerResponse> get(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String id) {
		CustomerService.ProfileRead result = customerService.read(id);
		return ResponseEntity.ok().header("X-Profile-Source", result.source()).body(result.customer());
	}

	@GetMapping("/search")
	@PreAuthorize("hasAuthority('SCOPE_crm:staff')")
	public List<CustomerSummary> search(@RequestParam @Size(min = 2, max = 120) String prefix,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return search.search(prefix, limit);
	}

	@GetMapping("/suggestions")
	@PreAuthorize("hasAuthority('SCOPE_crm:staff')")
	public List<CustomerSuggestion> suggestions(@RequestParam @Size(min = 2, max = 120) String prefix,
			@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
		return search.suggestions(prefix, limit);
	}

	@PutMapping("/{id}")
	@PreAuthorize("#id == authentication.name")
	public CustomerResponse update(
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String id,
			@Valid @RequestBody UpdateCustomerRequest request) {
		return customerService.update(id, request);
	}
}
