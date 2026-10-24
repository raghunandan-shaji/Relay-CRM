package com.raghu.crm.sales;

import com.raghu.crm.systemdesign.sharding.ShardRouter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@PreAuthorize("hasAuthority('SCOPE_crm:staff')")
@RequestMapping("/api")
public class SalesController {

	private final LeadService leads;
	private final SalesRepresentativeService representatives;
	private final ShardRouter router;

	public SalesController(LeadService leads, SalesRepresentativeService representatives, ShardRouter router) {
		this.leads = leads;
		this.representatives = representatives;
		this.router = router;
	}

	@GetMapping("/sales-representatives")
	public List<SalesRepresentativeService.Representative> representatives() {
		return representatives.available();
	}

	@PostMapping("/customers/{customerId}/leads")
	public ResponseEntity<Lead> create(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@Valid @RequestBody CreateLeadRequest request) {
		return response(customerId, leads.create(customerId, request), HttpStatus.CREATED);
	}

	@GetMapping("/customers/{customerId}/leads")
	public ResponseEntity<List<Lead>> list(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@RequestParam(required = false) @Pattern(regexp = "[0-9]{1,19}") String afterId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return response(customerId, leads.list(customerId, afterId, limit), HttpStatus.OK);
	}

	@GetMapping("/customers/{customerId}/leads/{leadId}")
	public ResponseEntity<Lead> get(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String leadId) {
		return response(customerId, leads.get(customerId, leadId), HttpStatus.OK);
	}

	@PutMapping("/customers/{customerId}/leads/{leadId}/status")
	public ResponseEntity<Lead> status(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String leadId, @Valid @RequestBody ChangeLeadStatusRequest request) {
		return response(customerId, leads.changeStatus(customerId, leadId, request), HttpStatus.OK);
	}

	@PutMapping("/customers/{customerId}/leads/{leadId}/representative")
	public ResponseEntity<Lead> reassign(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String leadId, @Valid @RequestBody AssignRepresentativeRequest request) {
		return response(customerId, leads.reassign(customerId, leadId, request), HttpStatus.OK);
	}

	@PostMapping("/customers/{customerId}/leads/{leadId}/opportunity")
	public ResponseEntity<Lead> qualify(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String leadId, @Valid @RequestBody CreateOpportunityRequest request) {
		return response(customerId, leads.qualify(customerId, leadId, request), HttpStatus.CREATED);
	}

	@PutMapping("/customers/{customerId}/leads/{leadId}/opportunity/stage")
	public ResponseEntity<Lead> stage(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String leadId, @Valid @RequestBody ChangeOpportunityStageRequest request) {
		return response(customerId, leads.changeOpportunityStage(customerId, leadId, request), HttpStatus.OK);
	}

	private <T> ResponseEntity<T> response(String customerId, T body, HttpStatus status) {
		return ResponseEntity.status(status).header("X-Data-Shard", router.shardIdFor(customerId)).body(body);
	}
}
