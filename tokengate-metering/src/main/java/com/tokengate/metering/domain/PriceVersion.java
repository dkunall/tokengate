package com.tokengate.metering.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/** One row of the append-only price list: what a provider model cost per 1k tokens from a point in time. */
public record PriceVersion(String provider, String providerModel, BigDecimal inputPer1k, BigDecimal outputPer1k,
                           Instant effectiveFrom) {

    static final int COST_SCALE = 8;
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    public PriceVersion {
        requireText(provider, "provider");
        requireText(providerModel, "providerModel");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (inputPer1k == null || inputPer1k.signum() < 0 || outputPer1k == null || outputPer1k.signum() < 0) {
            throw new IllegalArgumentException("prices must be zero or positive");
        }
    }

    public BigDecimal cost(long promptTokens, long completionTokens) {
        BigDecimal in = inputPer1k.multiply(BigDecimal.valueOf(promptTokens));
        BigDecimal out = outputPer1k.multiply(BigDecimal.valueOf(completionTokens));
        return in.add(out).divide(THOUSAND, COST_SCALE, RoundingMode.HALF_UP);
    }

    boolean sameModel(PriceVersion other) {
        return provider.equals(other.provider) && providerModel.equals(other.providerModel);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
