package com.raghu.crm.support;
import jakarta.validation.constraints.*;

public record TicketReplyRequest(@NotBlank @Size(max = 4000) String message, @NotNull @PositiveOrZero Long version) { }
