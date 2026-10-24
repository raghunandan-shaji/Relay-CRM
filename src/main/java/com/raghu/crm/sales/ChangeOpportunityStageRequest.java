package com.raghu.crm.sales;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeOpportunityStageRequest(@NotNull OpportunityStage stage, @NotNull @PositiveOrZero Long version) {
}
