package com.raghu.crm.notifications;

import com.raghu.crm.sales.SalesException;
import com.raghu.crm.systemdesign.sharding.ShardRouter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/customers/{customerId}/notifications")
public class NotificationController {
	private final ShardRouter router;
	public NotificationController(ShardRouter router) { this.router = router; }

	@GetMapping
	@PreAuthorize("#customerId == authentication.name")
	public List<CustomerEvent> inbox(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@RequestParam(required = false) @Pattern(regexp = "[0-9]{1,19}") String beforeId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		Criteria criteria = Criteria.where("customerId").is(customerId).and("notifyCustomer").is(true);
		if (beforeId != null) criteria.and("_id").lt(beforeId);
		return router.templateFor(customerId).find(Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "_id"))
			.limit(limit).withHint("customer_inbox").maxTime(Duration.ofSeconds(2)), CustomerEvent.class);
	}

	@PutMapping("/{eventId}/read")
	@PreAuthorize("#customerId == authentication.name")
	public CustomerEvent markRead(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String eventId) {
		Query key = Query.query(Criteria.where("_id").is(eventId).and("customerId").is(customerId).and("notifyCustomer").is(true));
		CustomerEvent changed = router.templateFor(customerId).findAndModify(
			Query.of(key).addCriteria(Criteria.where("readAt").is(null)),
			new Update().set("readAt", Instant.now().truncatedTo(ChronoUnit.MILLIS)),
			FindAndModifyOptions.options().returnNew(true), CustomerEvent.class);
		if (changed != null) return changed;
		CustomerEvent found = router.templateFor(customerId).findOne(key, CustomerEvent.class);
		if (found == null) throw new SalesException(HttpStatus.NOT_FOUND, "Notification was not found");
		return found;
	}
}
