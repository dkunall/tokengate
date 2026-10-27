package com.tokengate.metering.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceBookTest {

    private static final Instant JAN = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant JUN = Instant.parse("2026-06-01T00:00:00Z");

    private final PriceBook book = PriceBook.of(List.of(
            price("p", "m", "0.002", JUN),
            price("p", "m", "0.001", JAN),
            price("p", "other", "0.5", JAN)));

    @Test
    void picksTheVersionInForceAtTheEventTime() {
        assertThat(book.priceAt("p", "m", Instant.parse("2026-03-15T10:00:00Z")).orElseThrow().inputPer1k())
                .isEqualByComparingTo("0.001");
        assertThat(book.priceAt("p", "m", Instant.parse("2026-07-01T00:00:00Z")).orElseThrow().inputPer1k())
                .isEqualByComparingTo("0.002");
    }

    @Test
    void aVersionAppliesFromItsExactStartInstant() {
        assertThat(book.priceAt("p", "m", JUN).orElseThrow().inputPer1k()).isEqualByComparingTo("0.002");
        assertThat(book.priceAt("p", "m", JUN.minusNanos(1)).orElseThrow().inputPer1k())
                .isEqualByComparingTo("0.001");
    }

    @Test
    void nothingBeforeTheFirstVersionOrForUnknownModels() {
        assertThat(book.priceAt("p", "m", JAN.minusSeconds(1))).isEmpty();
        assertThat(book.priceAt("p", "missing", JUN)).isEmpty();
        assertThat(book.priceAt(null, "m", JUN)).isEmpty();
    }

    @Test
    void latestIsTheNewestVersion() {
        assertThat(book.latest("p", "m").orElseThrow().effectiveFrom()).isEqualTo(JUN);
    }

    @Test
    void costIsPerThousandTokensRoundedToEightDecimals() {
        PriceVersion v = new PriceVersion("p", "m", new BigDecimal("0.00015"), new BigDecimal("0.0006"), JAN);
        // 11 * 0.00015 / 1000 + 10 * 0.0006 / 1000 = 0.00000165 + 0.000006
        assertThat(v.cost(11, 10)).isEqualByComparingTo("0.00000765");
        assertThat(v.cost(0, 0)).isEqualByComparingTo("0");
        assertThat(v.cost(1, 0).scale()).isEqualTo(8);
    }

    @Test
    void rejectsNegativePrices() {
        assertThatThrownBy(() -> price("p", "m", "-0.1", JAN)).isInstanceOf(IllegalArgumentException.class);
    }

    private static PriceVersion price(String provider, String model, String in, Instant from) {
        return new PriceVersion(provider, model, new BigDecimal(in), new BigDecimal(in), from);
    }
}
