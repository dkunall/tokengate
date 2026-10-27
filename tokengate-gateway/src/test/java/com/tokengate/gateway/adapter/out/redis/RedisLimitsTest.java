package com.tokengate.gateway.adapter.out.redis;

import com.tokengate.gateway.domain.budget.BudgetDecision;
import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.budget.BudgetPeriod;
import com.tokengate.gateway.domain.budget.BudgetReservation;
import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.tenant.TenantId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the Lua scripts against a real Redis, with many callers racing for the last few permits. */
class RedisLimitsTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void start() {
        REDIS.start();
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void flush() {
        redis.execute(c -> {
            c.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Test
    void racingCallersNeverGetMoreThanTheBurst() throws Exception {
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redis);
        // 1/min refill means nothing comes back during the test
        RateLimitRule rule = new RateLimitRule(1, 20);
        List<RateLimitDecision> decisions = race(64, () -> limiter.tryAcquire(new TenantId("acme"), rule, 1));

        assertThat(decisions.stream().filter(RateLimitDecision::allowed)).hasSize(20);
        RateLimitDecision denied = decisions.stream().filter(d -> !d.allowed()).findFirst().orElseThrow();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void tenantsHaveSeparateBuckets() {
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redis);
        RateLimitRule rule = new RateLimitRule(1, 1);
        assertThat(limiter.tryAcquire(new TenantId("aa"), rule, 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire(new TenantId("aa"), rule, 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire(new TenantId("bb"), rule, 1).allowed()).isTrue();
    }

    @Test
    void budgetReservationsNeverOvershootTheDailyLimitUnderConcurrency() throws Exception {
        RedisBudgetLedger ledger = new RedisBudgetLedger(redis);
        BudgetPeriod period = BudgetPeriod.at(Instant.parse("2026-10-27T10:00:00Z"));
        BudgetLimits limits = new BudgetLimits(1_000, Cost.ofUsd("100"));
        BudgetReservation each = new BudgetReservation("acme", period, 30, Cost.ofUsd("0.001"));

        List<BudgetDecision> decisions = race(80, () -> ledger.reserve(each, limits));

        assertThat(decisions.stream().filter(BudgetDecision::allowed)).hasSize(33);
        assertThat(decisions.stream().filter(d -> !d.allowed()))
                .allSatisfy(d -> assertThat(d.reason()).isEqualTo(BudgetDecision.Reason.DAILY_TOKENS));
        assertThat(ledger.usage(new TenantId("acme"), period).tokensToday()).isEqualTo(990);
    }

    @Test
    void monthlyCostCapIsCheckedAfterTokens() {
        RedisBudgetLedger ledger = new RedisBudgetLedger(redis);
        BudgetPeriod period = BudgetPeriod.at(Instant.parse("2026-10-27T10:00:00Z"));
        BudgetLimits limits = new BudgetLimits(1_000_000, Cost.ofUsd("0.01"));
        BudgetReservation r = new BudgetReservation("acme", period, 10, Cost.ofUsd("0.004"));

        assertThat(ledger.reserve(r, limits).allowed()).isTrue();
        assertThat(ledger.reserve(r, limits).allowed()).isTrue();
        BudgetDecision third = ledger.reserve(r, limits);
        assertThat(third.allowed()).isFalse();
        assertThat(third.reason()).isEqualTo(BudgetDecision.Reason.MONTHLY_COST);
        assertThat(third.retryAfter()).isEqualTo(period.untilNextMonth());
    }

    @Test
    void settleReplacesTheEstimateWithActualUsageAndReleaseGivesItBack() {
        RedisBudgetLedger ledger = new RedisBudgetLedger(redis);
        BudgetPeriod period = BudgetPeriod.at(Instant.parse("2026-10-27T10:00:00Z"));
        BudgetLimits limits = new BudgetLimits(10_000, Cost.ofUsd("1"));
        BudgetReservation estimate = new BudgetReservation("acme", period, 500, Cost.ofUsd("0.0005"));
        TenantId acme = new TenantId("acme");

        ledger.reserve(estimate, limits);
        ledger.settle(estimate, 120, Cost.ofUsd("0.00012"));
        assertThat(ledger.usage(acme, period).tokensToday()).isEqualTo(120);
        assertThat(ledger.usage(acme, period).costThisMonth()).isEqualTo(Cost.ofUsd("0.00012"));

        ledger.reserve(estimate, limits);
        ledger.release(estimate);
        assertThat(ledger.usage(acme, period).tokensToday()).isEqualTo(120);
        assertThat(redis.getExpire(RedisKeys.tokensDay("acme", period.day()))).isPositive();
    }

    private static <T> List<T> race(int callers, Supplier<T> action) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            tasks.add(() -> {
                go.await();
                return action.get();
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<T>> futures = tasks.stream().map(pool::submit).toList();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }
}
