package com.tokengate.gateway.adapter.out.redis;

import com.tokengate.gateway.application.port.out.BudgetLedger;
import com.tokengate.gateway.domain.budget.BudgetDecision;
import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.budget.BudgetPeriod;
import com.tokengate.gateway.domain.budget.BudgetReservation;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.tenant.TenantId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Reserve-then-settle counters. Redis is the enforcement copy; the metering service's Postgres totals are the
 * billing copy. They can drift (see ADR 0004), which is why invoices never read from here.
 */
@Component
public class RedisBudgetLedger implements BudgetLedger {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisTokenBucketRateLimiter.loaded("redis/budget_reserve.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADJUST = RedisTokenBucketRateLimiter.loaded("redis/budget_adjust.lua");

    private final StringRedisTemplate redis;

    public RedisBudgetLedger(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public BudgetDecision reserve(BudgetReservation r, BudgetLimits limits) {
        BudgetPeriod p = r.period();
        List<?> reply = redis.execute(RESERVE, keys(r),
                Long.toString(r.tokens()), Long.toString(r.cost().micros()),
                Long.toString(limits.dailyTokens()), Long.toString(limits.monthlyCostCap().micros()),
                Long.toString(p.untilNextDay().toSeconds()), Long.toString(p.untilNextMonth().toSeconds()));
        if (reply == null || reply.size() < 4) {
            throw new IllegalStateException("unexpected reply from budget script: " + reply);
        }
        boolean allowed = num(reply, 0) == 1;
        BudgetDecision.Reason reason = switch ((int) num(reply, 1)) {
            case 1 -> BudgetDecision.Reason.DAILY_TOKENS;
            case 2 -> BudgetDecision.Reason.MONTHLY_COST;
            default -> BudgetDecision.Reason.NONE;
        };
        Duration retryAfter = switch (reason) {
            case DAILY_TOKENS -> p.untilNextDay();
            case MONTHLY_COST -> p.untilNextMonth();
            case NONE -> Duration.ZERO;
        };
        return new BudgetDecision(allowed, reason, num(reply, 2), limits.dailyTokens(), num(reply, 3),
                limits.monthlyCostCap().micros(), retryAfter);
    }

    @Override
    public void settle(BudgetReservation r, long actualTokens, Cost actualCost) {
        adjust(r, actualTokens - r.tokens(), actualCost.micros() - r.cost().micros());
    }

    @Override
    public void release(BudgetReservation r) {
        adjust(r, -r.tokens(), -r.cost().micros());
    }

    @Override
    public Usage usage(TenantId tenant, BudgetPeriod period) {
        List<String> values = redis.opsForValue().multiGet(List.of(
                RedisKeys.tokensDay(tenant.value(), period.day()),
                RedisKeys.costMonth(tenant.value(), period.month())));
        long tokens = values == null || values.get(0) == null ? 0 : Long.parseLong(values.get(0));
        long cost = values == null || values.get(1) == null ? 0 : Long.parseLong(values.get(1));
        return new Usage(tokens, new Cost(cost));
    }

    private void adjust(BudgetReservation r, long tokenDelta, long costDelta) {
        if (tokenDelta == 0 && costDelta == 0) {
            return;
        }
        redis.execute(ADJUST, keys(r), Long.toString(tokenDelta), Long.toString(costDelta));
    }

    private static List<String> keys(BudgetReservation r) {
        return List.of(RedisKeys.tokensDay(r.tenantId(), r.period().day()),
                RedisKeys.costMonth(r.tenantId(), r.period().month()));
    }

    private static long num(List<?> reply, int i) {
        return ((Number) reply.get(i)).longValue();
    }
}
