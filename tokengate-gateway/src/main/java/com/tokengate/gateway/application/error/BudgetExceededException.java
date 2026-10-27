package com.tokengate.gateway.application.error;

import com.tokengate.gateway.domain.budget.BudgetDecision;

public final class BudgetExceededException extends GatewayException {

    private final BudgetDecision decision;

    public BudgetExceededException(BudgetDecision decision) {
        super(switch (decision.reason()) {
            case DAILY_TOKENS -> "daily token budget exhausted (" + decision.tokensUsed() + "/" + decision.tokensLimit() + ")";
            case MONTHLY_COST -> "monthly cost cap reached";
            case NONE -> "budget exceeded";
        });
        this.decision = decision;
    }

    public BudgetDecision decision() {
        return decision;
    }
}
