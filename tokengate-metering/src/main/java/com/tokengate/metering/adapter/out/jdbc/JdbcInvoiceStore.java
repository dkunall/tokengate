package com.tokengate.metering.adapter.out.jdbc;

import com.tokengate.metering.application.port.InvoiceStore;
import com.tokengate.metering.domain.BillingPeriod;
import com.tokengate.metering.domain.Invoice;
import com.tokengate.metering.domain.InvoiceLine;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JdbcInvoiceStore implements InvoiceStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcInvoiceStore(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Optional<Invoice> find(String tenantId, BillingPeriod period) {
        return jdbc.sql("SELECT * FROM invoices WHERE tenant_id = :tenant AND period = :period")
                .param("tenant", tenantId)
                .param("period", period.toString())
                .query(this::header)
                .optional()
                .map(this::withLines);
    }

    @Override
    public List<Invoice> forTenant(String tenantId) {
        return jdbc.sql("SELECT * FROM invoices WHERE tenant_id = :tenant ORDER BY period DESC")
                .param("tenant", tenantId)
                .query(this::header)
                .list()
                .stream()
                .map(this::withLines)
                .toList();
    }

    @Override
    public void saveDraft(Invoice draft) {
        tx.executeWithoutResult(status -> {
            // Upsert keyed on (tenant, period) so two concurrent generate calls end up on one row; the
            // WHERE keeps an issued invoice untouched.
            int rows = jdbc.sql("""
                            INSERT INTO invoices (id, tenant_id, period, status, total_usd, generated_at)
                            VALUES (:id, :tenant, :period, 'DRAFT', :total, :at)
                            ON CONFLICT (tenant_id, period) DO UPDATE
                                SET total_usd = EXCLUDED.total_usd, generated_at = EXCLUDED.generated_at
                                WHERE invoices.status = 'DRAFT'""")
                    .param("id", draft.id())
                    .param("tenant", draft.tenantId())
                    .param("period", draft.period().toString())
                    .param("total", draft.total())
                    .param("at", Timestamp.from(draft.generatedAt()))
                    .update();
            if (rows == 1) {
                UUID id = jdbc.sql("SELECT id FROM invoices WHERE tenant_id = :tenant AND period = :period")
                        .param("tenant", draft.tenantId())
                        .param("period", draft.period().toString())
                        .query(UUID.class)
                        .single();
                replaceLines(id, draft.lines());
            }
        });
    }

    @Override
    public boolean markIssued(Invoice issued) {
        Boolean ok = tx.execute(status -> {
            // Matched on tenant and period rather than id: a concurrent first generate may have won the
            // insert with its own id.
            Optional<UUID> id = jdbc.sql("""
                            UPDATE invoices SET status = 'ISSUED', total_usd = :total, generated_at = :generated,
                                                issued_at = :issued
                            WHERE tenant_id = :tenant AND period = :period AND status = 'DRAFT'
                            RETURNING id""")
                    .param("total", issued.total())
                    .param("generated", Timestamp.from(issued.generatedAt()))
                    .param("issued", Timestamp.from(issued.issuedAt()))
                    .param("tenant", issued.tenantId())
                    .param("period", issued.period().toString())
                    .query(UUID.class)
                    .optional();
            if (id.isEmpty()) {
                return false;
            }
            // Rewritten here as well: a concurrent regenerate may have swapped the draft lines between
            // our generate and this update, and the issued total must match the issued lines.
            replaceLines(id.get(), issued.lines());
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    private void replaceLines(UUID invoiceId, List<InvoiceLine> lines) {
        jdbc.sql("DELETE FROM invoice_lines WHERE invoice_id = :id").param("id", invoiceId).update();
        int no = 1;
        for (InvoiceLine l : lines) {
            jdbc.sql("""
                            INSERT INTO invoice_lines (invoice_id, line_no, model, provider, requests, cache_hits,
                                                       prompt_tokens, completion_tokens, cost_usd)
                            VALUES (:id, :no, :model, :provider, :requests, :hits, :prompt, :completion, :cost)""")
                    .param("id", invoiceId)
                    .param("no", no++)
                    .param("model", l.model())
                    .param("provider", l.provider())
                    .param("requests", l.requests())
                    .param("hits", l.cacheHits())
                    .param("prompt", l.promptTokens())
                    .param("completion", l.completionTokens())
                    .param("cost", l.cost())
                    .update();
        }
    }

    private Invoice header(ResultSet rs, int n) throws SQLException {
        Timestamp issued = rs.getTimestamp("issued_at");
        return new Invoice(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                BillingPeriod.parse(rs.getString("period").trim()), Invoice.Status.valueOf(rs.getString("status")),
                List.of(), rs.getBigDecimal("total_usd"), rs.getTimestamp("generated_at").toInstant(),
                issued == null ? null : issued.toInstant());
    }

    private Invoice withLines(Invoice h) {
        List<InvoiceLine> lines = jdbc.sql("""
                        SELECT model, provider, requests, cache_hits, prompt_tokens, completion_tokens, cost_usd
                        FROM invoice_lines WHERE invoice_id = :id ORDER BY line_no""")
                .param("id", h.id())
                .query((rs, n) -> new InvoiceLine(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getBigDecimal(7)))
                .list();
        return new Invoice(h.id(), h.tenantId(), h.period(), h.status(), lines, h.total(), h.generatedAt(),
                h.issuedAt());
    }
}
