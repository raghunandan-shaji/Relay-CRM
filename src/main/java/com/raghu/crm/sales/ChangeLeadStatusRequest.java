package com.raghu.crm.sales;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeLeadStatusRequest(@NotNull LeadStatus status, @NotNull @PositiveOrZero Long version) {
}
