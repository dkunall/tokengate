package com.tokengate.gateway.adapter.out.persistence;

import com.tokengate.gateway.application.port.out.ApiKeyRepository;
import com.tokengate.gateway.domain.tenant.ApiKey;
import com.tokengate.gateway.domain.tenant.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcApiKeyRepository implements ApiKeyRepository {

    private static final RowMapper<ApiKey> ROW = (rs, n) -> new ApiKey(
            rs.getString("id"),
            TenantId.of(rs.getString("tenant_id")),
            rs.getString("prefix"),
            rs.getString("secret_hash"),
            rs.getString("name"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("revoked_at")));

    private final JdbcClient jdbc;

    public JdbcApiKeyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ApiKey key) {
        jdbc.sql("""
                insert into api_keys (id, tenant_id, prefix, secret_hash, name, created_at, revoked_at)
                values (?, ?, ?, ?, ?, ?, ?)""")
                .params(UUID.fromString(key.id()), key.tenantId().value(), key.prefix(), key.secretHash(), key.name(),
                        Timestamp.from(key.createdAt()), toTimestamp(key.revokedAt()))
                .update();
    }

    @Override
    public void update(ApiKey key) {
        jdbc.sql("update api_keys set name = ?, revoked_at = ? where id = ?")
                .params(key.name(), toTimestamp(key.revokedAt()), UUID.fromString(key.id()))
                .update();
    }

    @Override
    public Optional<ApiKey> findByPrefix(String prefix) {
        return jdbc.sql("select * from api_keys where prefix = ?").param(prefix).query(ROW).optional();
    }

    @Override
    public Optional<ApiKey> findById(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.sql("select * from api_keys where id = ?").param(uuid).query(ROW).optional();
    }

    @Override
    public List<ApiKey> findByTenant(TenantId tenantId) {
        return jdbc.sql("select * from api_keys where tenant_id = ? order by created_at")
                .param(tenantId.value()).query(ROW).list();
    }

    private static Instant toInstant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp toTimestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
