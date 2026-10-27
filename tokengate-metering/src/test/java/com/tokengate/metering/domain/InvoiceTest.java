package com.tokengate.metering.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceTest {

    private static final BillingPeriod OCT = BillingPeriod.parse("2026-10");
    private static final Instant NOW = Instant.parse("2026-11-01T00:05:00Z");

    @Test
    void sumsDailyRowsIntoOneLinePerModelAndProvider() {
        Invoice invoice = Invoice.draft(UUID.randomUUID(), "acme", OCT, List.of(
                day(1, "gpt-4o-mini", "alpha", 10, "0.5"),
                day(2, "gpt-4o-mini", "alpha", 5, "0.25"),
                day(2, "gpt-4o-mini", "beta", 1, "0.125"),
                day(3, "claude", "anthropic", 2, "1")), NOW);

        assertThat(invoice.lines()).extracting(InvoiceLine::model, InvoiceLine::provider, InvoiceLine::requests)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("claude", "anthropic", 2L),
                        org.assertj.core.groups.Tuple.tuple("gpt-4o-mini", "alpha", 15L),
                        org.assertj.core.groups.Tuple.tuple("gpt-4o-mini", "beta", 1L));
        assertThat(invoice.total()).isEqualByComparingTo("1.875");
        assertThat(invoice.amountDue()).isEqualByComparingTo("1.88");
        assertThat(invoice.status()).isEqualTo(Invoice.Status.DRAFT);
    }

    @Test
    void emptyMonthGivesAZeroInvoice() {
        Invoice invoice = Invoice.draft(UUID.randomUUID(), "acme", OCT, List.of(), NOW);
        assertThat(invoice.lines()).isEmpty();
        assertThat(invoice.total()).isZero();
    }

    @Test
    void refusesRowsFromAnotherTenantOrMonth() {
        DailyUsage otherMonth = new DailyUsage("acme", LocalDate.of(2026, 11, 1), "m", "p", 1, 0, 0, 1, 1,
                BigDecimal.ONE);
        assertThatThrownBy(() -> Invoice.draft(UUID.randomUUID(), "acme", OCT, List.of(otherMonth), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Invoice.draft(UUID.randomUUID(), "globex", OCT, List.of(day(1, "m", "p", 1, "1")),
                NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuingTwiceFails() {
        Invoice issued = Invoice.draft(UUID.randomUUID(), "acme", OCT, List.of(), NOW).issue(NOW);
        assertThat(issued.issuedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> issued.issue(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void periodCoversTheWholeMonth() {
        assertThat(OCT.firstDay()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(OCT.lastDay()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(BillingPeriod.parse("2028-02").lastDay()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThatThrownBy(() -> BillingPeriod.parse("10-2026")).isInstanceOf(IllegalArgumentException.class);
    }

    private static DailyUsage day(int dayOfMonth, String model, String provider, long requests, String cost) {
        return new DailyUsage("acme", LocalDate.of(2026, 10, dayOfMonth), model, provider, requests, 0, 0,
                requests * 10, requests * 5, new BigDecimal(cost));
    }
}
