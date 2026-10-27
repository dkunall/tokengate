package com.tokengate.gateway.adapter.out.persistence;

import com.tokengate.gateway.application.port.out.TenantRepository;
import com.tokengate.gateway.domain.budget.BudgetLimits;
import com.tokengate.gateway.domain.cache.CachePolicy;
import com.tokengate.gateway.domain.pii.PiiMode;
import com.tokengate.gateway.domain.ratelimit.RateLimitRule;
import com.tokengate.gateway.domain.routing.Cost;
import com.tokengate.gateway.domain.routing.RoutingPolicy;
import com.tokengate.gateway.domain.tenant.PiiSettings;
import com.tokengate.gateway.domain.tenant.Tenant;
import com.tokengate.gateway.domain.tenant.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcTenantRepository implements TenantRepository {

    private static final String COLUMNS = """
            id, name, rate_per_minute, rate_burst, daily_tokens, monthly_cost_cap_micros, routing_policy,
            cache_enabled, semantic_enabled, semantic_threshold, cache_ttl_seconds, cache_streamed,
            cache_non_deterministic, pii_mode, pii_custom_terms, active, created_at""";

    private static final RowMapper<Tenant> ROW = (rs, n) -> {
        Array terms = rs.getArray("pii_custom_terms");
        List<String> customTerms = terms == null ? List.of() : Arrays.asList((String[]) terms.getArray());
        return new Tenant(
                TenantId.of(rs.getString("id")),
                rs.getString("name"),
                new RateLimitRule(rs.getLong("rate_per_minute"), rs.getLong("rate_burst")),
                new BudgetLimits(rs.getLong("daily_tokens"), new Cost(rs.getLong("monthly_cost_cap_micros"))),
                RoutingPolicy.valueOf(rs.getString("routing_policy")),
                new CachePolicy(rs.getBoolean("cache_enabled"), rs.getBoolean("semantic_enabled"),
                        rs.getDouble("semantic_threshold"), Duration.ofSeconds(rs.getLong("cache_ttl_seconds")),
                        rs.getBoolean("cache_streamed"), rs.getBoolean("cache_non_deterministic")),
                new PiiSettings(PiiMode.valueOf(rs.getString("pii_mode")), customTerms),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcClient jdbc;

    public JdbcTenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Tenant> find(TenantId id) {
        return jdbc.sql("select " + COLUMNS + " from tenants where id = ?").param(id.value()).query(ROW).optional();
    }

    @Override
    public List<Tenant> findAll() {
        return jdbc.sql("select " + COLUMNS + " from tenants order by id").query(ROW).list();
    }

    @Override
    public boolean insert(Tenant t) {
        try {
            jdbc.sql("""
                    insert into tenants (id, name, rate_per_minute, rate_burst, daily_tokens, monthly_cost_cap_micros,
                        routing_policy, cache_enabled, semantic_enabled, semantic_threshold, cache_ttl_seconds,
                        cache_streamed, cache_non_deterministic, pii_mode, pii_custom_terms, active, created_at)
                    values (:id, :name, :rpm, :burst, :daily, :cap, :routing, :cacheOn, :semOn, :threshold, :ttl,
                        :streamed, :nonDet, :pii, :terms, :active, :created)""")
                    .params(params(t))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void update(Tenant t) {
        int rows = jdbc.sql("""
                update tenants set name = :name, rate_per_minute = :rpm, rate_burst = :burst, daily_tokens = :daily,
                    monthly_cost_cap_micros = :cap, routing_policy = :routing, cache_enabled = :cacheOn,
                    semantic_enabled = :semOn, semantic_threshold = :threshold, cache_ttl_seconds = :ttl,
                    cache_streamed = :streamed, cache_non_deterministic = :nonDet, pii_mode = :pii,
                    pii_custom_terms = :terms, active = :active, updated_at = now()
                where id = :id""")
                .params(params(t))
                .update();
        if (rows == 0) {
            insert(t);
        }
    }

    private static Map<String, Object> params(Tenant t) {
        CachePolicy c = t.cache();
        return Map.ofEntries(
                Map.entry("id", t.id().value()),
                Map.entry("name", t.name()),
                Map.entry("rpm", t.rateLimit().perMinute()),
                Map.entry("burst", t.rateLimit().burst()),
                Map.entry("daily", t.budget().dailyTokens()),
                Map.entry("cap", t.budget().monthlyCostCap().micros()),
                Map.entry("routing", t.routing().name()),
                Map.entry("cacheOn", c.enabled()),
                Map.entry("semOn", c.semanticEnabled()),
                Map.entry("threshold", c.semanticThreshold()),
                Map.entry("ttl", c.ttl().toSeconds()),
                Map.entry("streamed", c.cacheStreamed()),
                Map.entry("nonDet", c.cacheNonDeterministic()),
                Map.entry("pii", t.pii().mode().name()),
                Map.entry("terms", t.pii().customTerms().toArray(String[]::new)),
                Map.entry("active", t.active()),
                Map.entry("created", Timestamp.from(t.createdAt())));
    }
}
