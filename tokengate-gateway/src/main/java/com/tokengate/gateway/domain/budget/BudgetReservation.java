package com.tokengate.gateway.domain.budget;

import com.tokengate.gateway.domain.routing.Cost;

/**
 * What was held against the tenant's budget before the provider call. After the call the difference between
 * this estimate and the real usage is applied, so the counters end up at actual usage either way.
 */
public record BudgetReservation(String tenantId, BudgetPeriod period, long tokens, Cost cost) {
}
