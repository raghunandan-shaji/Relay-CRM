package com.raghu.crm.support;
import jakarta.validation.constraints.*;

public record CreateTicketRequest(@NotBlank @Size(max = 160) String subject,
	@NotBlank @Size(max = 4000) String message, @NotNull TicketPriority priority) { }
