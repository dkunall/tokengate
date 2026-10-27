package com.tokengate.metering.application;

import com.tokengate.common.UsageEvent;
import com.tokengate.metering.domain.BillingPeriod;
import com.tokengate.metering.domain.Invoice;
import com.tokengate.metering.domain.PriceVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeteringServicesTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final BillingPeriod OCT = BillingPeriod.parse("2026-10");

    private final InMemoryStores.Ledger ledger = new InMemoryStores.Ledger();
    private final InMemoryStores.Prices catalog = new InMemoryStores.Prices();
    private final InMemoryStores.Invoices store = new InMemoryStores.Invoices();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private PriceBookCache prices;
    private UsageIngestion ingestion;
    private InvoiceService invoices;

    @BeforeEach
    void setUp() {
        catalog.append(new PriceVersion("alpha", "alpha-small", new BigDecimal("1"), new BigDecimal("1"),
                Instant.parse("2026-01-01T00:00:00Z")));
        prices = new PriceBookCache(catalog, clock, Duration.ofSeconds(30));
        ingestion = new UsageIngestion(ledger, prices);
        invoices = new InvoiceService(ledger, store, clock);
    }

    @Test
    void redeliveredEventIsCountedOnce() {
        UsageEvent e = event(UUID.randomUUID(), 1);
        assertThat(ingestion.ingest(e)).isEqualTo(UsageIngestion.Result.APPLIED);
        assertThat(ingestion.ingest(e)).isEqualTo(UsageIngestion.Result.DUPLICATE);
        assertThat(ledger.rows.values()).singleElement().satisfies(d -> assertThat(d.requests()).isEqualTo(1));
    }

    @Test
    void eventsFromANewerSchemaAreNotBilled() {
        assertThat(ingestion.ingest(event(UUID.randomUUID(), 2))).isEqualTo(UsageIngestion.Result.UNSUPPORTED_SCHEMA);
        assertThat(ledger.rows).isEmpty();
    }

    @Test
    void priceBookIsReadOncePerTtlNotPerEvent() {
        for (int i = 0; i < 50; i++) {
            ingestion.ingest(event(UUID.randomUUID(), 1));
        }
        assertThat(catalog.reads).isEqualTo(1);
    }

    @Test
    void newPriceMustStartAfterTheLatestVersion() {
        assertThatThrownBy(() -> prices.append(new PriceVersion("alpha", "alpha-small", BigDecimal.TEN,
                BigDecimal.TEN, Instant.parse("2025-12-31T00:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("past prices are not rewritten");

        prices.append(new PriceVersion("alpha", "alpha-small", BigDecimal.TEN, BigDecimal.TEN,
                Instant.parse("2026-11-01T00:00:00Z")));
        assertThat(prices.current().all()).hasSize(2);
    }

    @Test
    void regeneratingADraftKeepsItsIdAndPicksUpNewUsage() {
        ingestion.ingest(event(UUID.randomUUID(), 1));
        Invoice first = invoices.generate("acme", OCT);
        ingestion.ingest(event(UUID.randomUUID(), 1));
        Invoice second = invoices.generate("acme", OCT);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.lines()).singleElement().satisfies(l -> assertThat(l.requests()).isEqualTo(2));
        assertThat(second.total()).isEqualByComparingTo(first.total().multiply(BigDecimal.TWO));
    }

    @Test
    void issuedInvoiceIsFrozen() {
        ingestion.ingest(event(UUID.randomUUID(), 1));
        Invoice issued = invoices.issue("acme", OCT);
        assertThat(issued.status()).isEqualTo(Invoice.Status.ISSUED);
        assertThatThrownBy(() -> invoices.generate("acme", OCT)).isInstanceOf(InvoiceAlreadyIssued.class);
        assertThatThrownBy(() -> invoices.issue("acme", OCT)).isInstanceOf(InvoiceAlreadyIssued.class);
    }

    private static UsageEvent event(UUID id, int schema) {
        return new UsageEvent(schema, id, NOW, "acme", "key", "req", "gpt-4o-mini", "alpha", "alpha-small", 500,
                500, UsageEvent.CacheOutcome.MISS, false, 10, UsageEvent.Outcome.OK);
    }
}
