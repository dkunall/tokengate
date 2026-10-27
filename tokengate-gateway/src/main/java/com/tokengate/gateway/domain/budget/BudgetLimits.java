package com.tokengate.gateway.domain.budget;

import com.tokengate.gateway.domain.routing.Cost;

public record BudgetLimits(long dailyTokens, Cost monthlyCostCap) {

    public BudgetLimits {
        if (dailyTokens < 1) {
            throw new IllegalArgumentException("dailyTokens must be positive");
        }
        if (monthlyCostCap.micros() < 1) {
            throw new IllegalArgumentException("monthly cost cap must be positive");
        }
    }
}
