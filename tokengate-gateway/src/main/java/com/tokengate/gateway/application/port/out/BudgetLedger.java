package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.budget.BudgetDecision;
import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.budget.BudgetPeriod;
import com.tokengate.gateway.domain.budget.BudgetReservation;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.tenant.TenantId;

/**
 * Fast running counters for budget enforcement. The metering service's Postgres totals are what invoices
 * are built from; these counters only decide whether the next call may go out.
 */
public interface BudgetLedger {

    /** Atomically checks both limits and, if allowed, adds the reservation to the counters. */
    BudgetDecision reserve(BudgetReservation reservation, BudgetLimits limits);

    /** Replaces the reserved amounts with what the call actually used. */
    void settle(BudgetReservation reservation, long actualTokens, Cost actualCost);

    void release(BudgetReservation reservation);

    Usage usage(TenantId tenant, BudgetPeriod period);

    record Usage(long tokensToday, Cost costThisMonth) {
    }
}
