package com.tokengate.metering.domain;

import java.math.BigDecimal;

public record InvoiceLine(String model, String provider, long requests, long cacheHits, long promptTokens,
                          long completionTokens, BigDecimal cost) {

    InvoiceLine plus(DailyUsage d) {
        return new InvoiceLine(model, provider, requests + d.requests(), cacheHits + d.cacheHits(),
                promptTokens + d.promptTokens(), completionTokens + d.completionTokens(), cost.add(d.cost()));
    }
}
