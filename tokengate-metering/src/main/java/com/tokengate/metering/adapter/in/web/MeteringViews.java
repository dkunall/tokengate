package com.tokengate.metering.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tokengate.metering.domain.DailyUsage;
import com.tokengate.metering.domain.Invoice;
import com.tokengate.metering.domain.InvoiceLine;
import com.tokengate.metering.domain.PriceVersion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class MeteringViews {

    private MeteringViews() {
    }

    record DailyUsageView(LocalDate day, String model, String provider, long requests,
                          @JsonProperty("cache_hits") long cacheHits,
                          @JsonProperty("unpriced_requests") long unpricedRequests,
                          @JsonProperty("prompt_tokens") long promptTokens,
                          @JsonProperty("completion_tokens") long completionTokens,
                          @JsonProperty("cost_usd") BigDecimal cost) {

        static DailyUsageView of(DailyUsage d) {
            return new DailyUsageView(d.day(), d.model(), d.provider(), d.requests(), d.cacheHits(),
                    d.unpricedRequests(), d.promptTokens(), d.completionTokens(), d.cost());
        }
    }

    record UsageReport(@JsonProperty("tenant_id") String tenantId, LocalDate from, LocalDate to,
                       List<DailyUsageView> days) {
    }

    record PriceView(String provider, @JsonProperty("provider_model") String providerModel,
                     @JsonProperty("input_per_1k_usd") BigDecimal inputPer1k,
                     @JsonProperty("output_per_1k_usd") BigDecimal outputPer1k,
                     @JsonProperty("effective_from") Instant effectiveFrom) {

        static PriceView of(PriceVersion p) {
            return new PriceView(p.provider(), p.providerModel(), p.inputPer1k(), p.outputPer1k(),
                    p.effectiveFrom());
        }

        PriceVersion toDomain() {
            return new PriceVersion(provider, providerModel, inputPer1k, outputPer1k, effectiveFrom);
        }
    }

    record InvoiceRequest(@JsonProperty("tenant_id") String tenantId, String period) {
    }

    record LineView(String model, String provider, long requests, @JsonProperty("cache_hits") long cacheHits,
                    @JsonProperty("prompt_tokens") long promptTokens,
                    @JsonProperty("completion_tokens") long completionTokens,
                    @JsonProperty("cost_usd") BigDecimal cost) {

        static LineView of(InvoiceLine l) {
            return new LineView(l.model(), l.provider(), l.requests(), l.cacheHits(), l.promptTokens(),
                    l.completionTokens(), l.cost());
        }
    }

    record InvoiceView(String id, @JsonProperty("tenant_id") String tenantId, String period, String status,
                       List<LineView> lines, @JsonProperty("total_usd") BigDecimal total,
                       @JsonProperty("amount_due_usd") BigDecimal amountDue,
                       @JsonProperty("generated_at") Instant generatedAt,
                       @JsonProperty("issued_at") Instant issuedAt) {

        static InvoiceView of(Invoice i) {
            return new InvoiceView(i.id().toString(), i.tenantId(), i.period().toString(), i.status().name(),
                    i.lines().stream().map(LineView::of).toList(), i.total(), i.amountDue(), i.generatedAt(),
                    i.issuedAt());
        }
    }
}
