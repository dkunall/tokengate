package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.application.error.BudgetExceededException;
import com.tokengate.gateway.application.error.RateLimitedException;
import com.tokengate.gateway.application.port.out.BudgetLedger;
import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.application.port.out.RateLimiter;
import com.tokengate.gateway.domain.budget.BudgetDecision;
import com.tokengate.gateway.domain.budget.BudgetPeriod;
import com.tokengate.gateway.domain.budget.BudgetReservation;
import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.tenant.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * Rate limit and budget checks. Both fail open when Redis is unreachable: a short outage of the limiter
 * should not take every tenant down with it. The metering totals in Postgres still see every call.
 */
public final class Admission {

    private static final Logger log = LoggerFactory.getLogger(Admission.class);

    private final RateLimiter rateLimiter;
    private final BudgetLedger budget;
    private final GatewayMetrics metrics;
    private final Clock clock;

    public Admission(RateLimiter rateLimiter, BudgetLedger budget, GatewayMetrics metrics, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.metrics = metrics;
        this.clock = clock;
    }

    public RateLimitDecision admit(Tenant tenant) {
        RateLimitDecision d;
        try {
            d = rateLimiter.tryAcquire(tenant.id(), tenant.rateLimit(), 1);
        } catch (RuntimeException e) {
            log.warn("rate limiter unavailable, admitting request: {}", e.toString());
            long burst = tenant.rateLimit().burst();
            return new RateLimitDecision(true, burst, burst, Duration.ZERO, Duration.ZERO);
        }
        if (!d.allowed()) {
            metrics.rejected(tenant.id().value(), "rate_limited");
            throw new RateLimitedException(d);
        }
        return d;
    }

    /** @return the reservation to settle later, or null if the ledger was unreachable and the call was let through */
    public BudgetReservation reserve(Tenant tenant, long tokens, Cost estimatedCost) {
        BudgetReservation r = new BudgetReservation(tenant.id().value(), BudgetPeriod.at(clock.instant()), tokens,
                estimatedCost);
        BudgetDecision decision;
        try {
            decision = budget.reserve(r, tenant.budget());
        } catch (RuntimeException e) {
            log.warn("budget ledger unavailable, admitting request: {}", e.toString());
            return null;
        }
        if (!decision.allowed()) {
            metrics.rejected(tenant.id().value(), "budget_" + decision.reason().name().toLowerCase());
            throw new BudgetExceededException(decision);
        }
        return r;
    }

    public void settle(BudgetReservation r, long actualTokens, Cost actualCost) {
        if (r == null) {
            return;
        }
        try {
            budget.settle(r, actualTokens, actualCost);
        } catch (RuntimeException e) {
            log.warn("could not settle budget reservation for {}: {}", r.tenantId(), e.toString());
        }
    }

    public void release(BudgetReservation r) {
        if (r == null) {
            return;
        }
        try {
            budget.release(r);
        } catch (RuntimeException e) {
            log.warn("could not release budget reservation for {}: {}", r.tenantId(), e.toString());
        }
    }
}
