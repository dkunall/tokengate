package com.tokengate.metering.adapter.out.jdbc;

import com.tokengate.metering.application.port.UsageLedger;
import com.tokengate.metering.domain.DailyUsage;
import com.tokengate.metering.domain.UsageCharge;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class JdbcUsageLedger implements UsageLedger {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcUsageLedger(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public boolean applyOnce(UUID eventId, Instant occurredAt, UsageCharge c) {
        Boolean applied = tx.execute(status -> {
            // A concurrent duplicate blocks on the primary key until the first insert commits, then
            // sees the conflict, so two consumers can never both count one event.
            int inserted = jdbc.sql("""
                            INSERT INTO processed_events (event_id, tenant_id, occurred_at)
                            VALUES (:id, :tenant, :at) ON CONFLICT (event_id) DO NOTHING""")
                    .param("id", eventId)
                    .param("tenant", c.tenantId())
                    .param("at", Timestamp.from(occurredAt))
                    .update();
            if (inserted == 0) {
                return false;
            }
            jdbc.sql("""
                            INSERT INTO daily_usage (tenant_id, usage_day, model, provider, requests, cache_hits,
                                                     unpriced_requests, prompt_tokens, completion_tokens, cost_usd)
                            VALUES (:tenant, :day, :model, :provider, 1, :hit, :unpriced, :prompt, :completion, :cost)
                            ON CONFLICT (tenant_id, usage_day, model, provider) DO UPDATE SET
                                requests = daily_usage.requests + 1,
                                cache_hits = daily_usage.cache_hits + EXCLUDED.cache_hits,
                                unpriced_requests = daily_usage.unpriced_requests + EXCLUDED.unpriced_requests,
                                prompt_tokens = daily_usage.prompt_tokens + EXCLUDED.prompt_tokens,
                                completion_tokens = daily_usage.completion_tokens + EXCLUDED.completion_tokens,
                                cost_usd = daily_usage.cost_usd + EXCLUDED.cost_usd,
                                updated_at = now()""")
                    .param("tenant", c.tenantId())
                    .param("day", c.day())
                    .param("model", c.model())
                    .param("provider", c.provider())
                    .param("hit", c.cacheHit() ? 1 : 0)
                    .param("unpriced", c.priced() ? 0 : 1)
                    .param("prompt", c.promptTokens())
                    .param("completion", c.completionTokens())
                    .param("cost", c.cost())
                    .update();
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    @Override
    public List<DailyUsage> daily(String tenantId, LocalDate from, LocalDate toInclusive) {
        return jdbc.sql("""
                        SELECT tenant_id, usage_day, model, provider, requests, cache_hits, unpriced_requests,
                               prompt_tokens, completion_tokens, cost_usd
                        FROM daily_usage
                        WHERE tenant_id = :tenant AND usage_day BETWEEN :from AND :to
                        ORDER BY usage_day, model, provider""")
                .param("tenant", tenantId)
                .param("from", from)
                .param("to", toInclusive)
                .query((rs, n) -> new DailyUsage(rs.getString("tenant_id"),
                        rs.getObject("usage_day", LocalDate.class), rs.getString("model"),
                        rs.getString("provider"), rs.getLong("requests"), rs.getLong("cache_hits"),
                        rs.getLong("unpriced_requests"), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"), rs.getBigDecimal("cost_usd")))
                .list();
    }
}
