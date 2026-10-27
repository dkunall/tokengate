package com.tokengate.metering.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyUsage(String tenantId, LocalDate day, String model, String provider, long requests,
                         long cacheHits, long unpricedRequests, long promptTokens, long completionTokens,
                         BigDecimal cost) {
}
