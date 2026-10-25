package com.raghu.crm.support;

import com.raghu.crm.notifications.CustomerEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api")
public class TicketController {
	private final TicketService tickets;
	public TicketController(TicketService tickets) { this.tickets = tickets; }

	@GetMapping("/support/tickets")
	@PreAuthorize("hasAuthority('SCOPE_crm:staff')")
	public List<SupportTicket> queue(@RequestParam(required = false) TicketStatus status,
			@RequestParam(required = false) @Pattern(regexp = "[0-9]{1,19}") String afterId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return tickets.list(null, status, afterId, limit);
	}

	@PostMapping("/customers/{customerId}/tickets")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public SupportTicket create(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@Valid @RequestBody CreateTicketRequest request, Authentication actor) {
		return tickets.create(customerId, request, actor.getName());
	}

	@GetMapping("/customers/{customerId}/tickets")
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public List<SupportTicket> list(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@RequestParam(required = false) TicketStatus status,
			@RequestParam(required = false) @Pattern(regexp = "[0-9]{1,19}") String afterId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return tickets.list(customerId, status, afterId, limit);
	}

	@GetMapping("/customers/{customerId}/tickets/{ticketId}")
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public SupportTicket get(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String ticketId) { return tickets.get(customerId, ticketId); }

	@GetMapping("/customers/{customerId}/tickets/{ticketId}/messages")
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public List<CustomerEvent> messages(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String ticketId,
			@RequestParam(required = false) @Pattern(regexp = "[0-9]{1,19}") String afterId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) { return tickets.messages(customerId, ticketId, afterId, limit); }

	@PostMapping("/customers/{customerId}/tickets/{ticketId}/messages")
	@PreAuthorize("#customerId == authentication.name or hasAuthority('SCOPE_crm:staff')")
	public SupportTicket reply(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String ticketId,
			@Valid @RequestBody TicketReplyRequest request, Authentication actor) {
		boolean staff = actor.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("SCOPE_crm:staff"));
		return tickets.reply(customerId, ticketId, request, actor.getName(), staff);
	}

	@PutMapping("/customers/{customerId}/tickets/{ticketId}")
	@PreAuthorize("hasAuthority('SCOPE_crm:staff')")
	public SupportTicket update(@PathVariable @Pattern(regexp = "[0-9]{1,19}") String customerId,
			@PathVariable @Pattern(regexp = "[0-9]{1,19}") String ticketId,
			@Valid @RequestBody ChangeTicketRequest request, Authentication actor) {
		return tickets.update(customerId, ticketId, request, actor.getName());
	}
}
