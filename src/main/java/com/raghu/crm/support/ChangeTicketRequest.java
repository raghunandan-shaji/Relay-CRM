package com.raghu.crm.support;
import jakarta.validation.constraints.*;

public record ChangeTicketRequest(TicketStatus status, @Pattern(regexp = "[0-9]{1,19}") String representativeId,
	@NotNull @PositiveOrZero Long version) { }
