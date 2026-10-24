package com.raghu.crm.engagement;

import com.raghu.crm.systemdesign.sharding.ShardRouter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/customers/{customerId}")
public class EngagementController {

	private final EngagementService engagement;
	private final ShardRouter router;

	public EngagementController(EngagementService engagement, ShardRouter router) {
		this.engagement = engagement;
		this.router = router;
	}

	@PostMapping("/interactions")
	@PreAuthorize("hasAuthority('SCOPE_crm:staff')")
	public ResponseEntity<Interaction> record(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@Valid @RequestBody CreateInteractionRequest request, Authentication actor) {
		return response(customerId, engagement.record(customerId, request, actor.getName()), HttpStatus.CREATED);
	}

	@GetMapping("/interactions")
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public ResponseEntity<List<Interaction>> timeline(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant beforeAt,
			@RequestParam(required = false) @Pattern(regexp = "[0-9]{1,19}") String beforeId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return response(customerId, engagement.timeline(customerId, beforeAt, beforeId, limit), HttpStatus.OK);
	}

	@GetMapping("/interactions/{interactionId}")
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public ResponseEntity<Interaction> interaction(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String interactionId) {
		return response(customerId, engagement.interaction(customerId, interactionId), HttpStatus.OK);
	}

	@PostMapping("/activities")
	@PreAuthorize("hasAuthority('SCOPE_crm:staff')")
	public ResponseEntity<SalesActivity> createActivity(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@Valid @RequestBody CreateActivityRequest request, Authentication actor) {
		return response(customerId, engagement.createActivity(customerId, request, actor.getName()), HttpStatus.CREATED);
	}

	@GetMapping("/activities")
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public ResponseEntity<List<SalesActivity>> activities(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@RequestParam(required = false) ActivityStatus status,
			@RequestParam(required = false) @Pattern(regexp = "[0-9]{1,19}") String afterId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return response(customerId, engagement.activities(customerId, status, afterId, limit), HttpStatus.OK);
	}

	@GetMapping("/activities/{activityId}")
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public ResponseEntity<SalesActivity> activity(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String activityId) {
		return response(customerId, engagement.activity(customerId, activityId), HttpStatus.OK);
	}

	@PutMapping("/activities/{activityId}/status")
	@PreAuthorize("hasAuthority('SCOPE_crm:staff')")
	public ResponseEntity<SalesActivity> changeActivityStatus(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String activityId,
			@Valid @RequestBody ChangeActivityStatusRequest request, Authentication actor) {
		return response(customerId, engagement.changeActivityStatus(customerId, activityId, request, actor.getName()), HttpStatus.OK);
	}

	private <T> ResponseEntity<T> response(String customerId, T body, HttpStatus status) {
		return ResponseEntity.status(status).header("X-Data-Shard", router.shardIdFor(customerId)).body(body);
	}
}
