package com.tokengate.gateway.domain.budget;

import java.time.Duration;

public record BudgetDecision(boolean allowed, Reason reason, long tokensUsed, long tokensLimit,
                             long costUsedMicros, long costCapMicros, Duration retryAfter) {

    public enum Reason { NONE, DAILY_TOKENS, MONTHLY_COST }
}
