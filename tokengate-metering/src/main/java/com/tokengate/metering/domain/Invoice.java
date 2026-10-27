package com.tokengate.metering.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A tenant's bill for one month: one line per model and provider, summed from the daily rows. Totals keep
 * eight decimals; {@link #amountDue()} is the rounded figure a customer would see.
 */
public record Invoice(UUID id, String tenantId, BillingPeriod period, Status status, List<InvoiceLine> lines,
                      BigDecimal total, Instant generatedAt, Instant issuedAt) {

    public enum Status { DRAFT, ISSUED }

    public Invoice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(status, "status");
        lines = List.copyOf(lines);
    }

    public static Invoice draft(UUID id, String tenantId, BillingPeriod period, List<DailyUsage> days,
                                Instant now) {
        Map<String, InvoiceLine> byModel = new LinkedHashMap<>();
        for (DailyUsage d : days) {
            if (!d.tenantId().equals(tenantId) || !period.equals(BillingPeriod.containing(d.day()))) {
                throw new IllegalArgumentException("daily row " + d + " does not belong to " + tenantId + " " + period);
            }
            byModel.merge(d.model() + '\u0000' + d.provider(),
                    new InvoiceLine(d.model(), d.provider(), d.requests(), d.cacheHits(), d.promptTokens(),
                            d.completionTokens(), d.cost()),
                    (line, ignored) -> line.plus(d));
        }
        List<InvoiceLine> lines = byModel.values().stream()
                .sorted(Comparator.comparing(InvoiceLine::model).thenComparing(InvoiceLine::provider))
                .toList();
        BigDecimal total = lines.stream().map(InvoiceLine::cost).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PriceVersion.COST_SCALE, RoundingMode.HALF_UP);
        return new Invoice(id, tenantId, period, Status.DRAFT, lines, total, now, null);
    }

    public Invoice issue(Instant at) {
        if (status == Status.ISSUED) {
            throw new IllegalStateException("invoice " + id + " is already issued");
        }
        return new Invoice(id, tenantId, period, Status.ISSUED, lines, total, generatedAt, at);
    }

    public BigDecimal amountDue() {
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
