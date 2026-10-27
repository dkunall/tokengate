package com.tokengate.gateway.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.application.port.out.RequestLogEntry;
import com.tokengate.gateway.application.port.out.UsageRecorder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;

/** The request log row and its outbox event commit together, so an event is never published for a call we lost. */
@Repository
public class JdbcUsageRecorder implements UsageRecorder {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcUsageRecorder(JdbcClient jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    @Override
    public void record(RequestLogEntry e, UsageEvent event) {
        String payload = toJson(event);
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    insert into request_log (request_id, tenant_id, api_key_id, model, provider, provider_model,
                        prompt_tokens, completion_tokens, estimated_cost_micros, cache, streamed, latency_ms, outcome,
                        attempts, pii_masked, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(e.requestId(), e.tenantId(), e.apiKeyId(), e.model(), e.provider(), e.providerModel(),
                            e.promptTokens(), e.completionTokens(), e.estimatedCostMicros(), e.cache().name(),
                            e.streamed(), e.latencyMs(), e.outcome().name(), e.attempts(), e.piiMasked(),
                            Timestamp.from(e.createdAt()))
                    .update();
            jdbc.sql("insert into outbox (event_id, partition_key, payload) values (?, ?, ?::jsonb)")
                    .params(event.eventId(), event.tenantId(), payload)
                    .update();
        });
    }

    private String toJson(UsageEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("usage event not serialisable", ex);
        }
    }
}
