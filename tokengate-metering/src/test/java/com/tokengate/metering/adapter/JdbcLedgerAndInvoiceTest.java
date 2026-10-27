package com.tokengate.metering.adapter;

import com.tokengate.metering.adapter.out.jdbc.JdbcInvoiceStore;
import com.tokengate.metering.adapter.out.jdbc.JdbcUsageLedger;
import com.tokengate.metering.domain.BillingPeriod;
import com.tokengate.metering.domain.DailyUsage;
import com.tokengate.metering.domain.Invoice;
import com.tokengate.metering.domain.UsageCharge;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLedgerAndInvoiceTest {

    private static PGSimpleDataSource dataSource;
    private JdbcClient jdbc;
    private JdbcUsageLedger ledger;
    private JdbcInvoiceStore invoices;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(TestInfra.POSTGRES.getJdbcUrl());
        dataSource.setUser(TestInfra.POSTGRES.getUsername());
        dataSource.setPassword(TestInfra.POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ledger = new JdbcUsageLedger(jdbc, tx);
        invoices = new JdbcInvoiceStore(jdbc, tx);
        jdbc.sql("TRUNCATE processed_events, daily_usage, invoices, invoice_lines").update();
    }

    @Test
    void concurrentDeliveriesOfTheSameEventCountOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        UsageCharge charge = charge("0.00000765", false);
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            calls.add(() -> {
                start.await();
                return ledger.applyOnce(eventId, Instant.now(), charge);
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Boolean>> futures = calls.stream().map(pool::submit).toList();
            start.countDown();
            long applied = 0;
            for (Future<Boolean> f : futures) {
                applied += f.get() ? 1 : 0;
            }
            assertThat(applied).isEqualTo(1);
        }
        assertThat(ledger.daily("acme", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.requests()).isEqualTo(1);
                    assertThat(d.cost()).isEqualByComparingTo("0.00000765");
                });
    }

    @Test
    void distinctEventsAccumulateIntoOneDailyRowUnderConcurrency() throws Exception {
        int events = 200;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < events; i++) {
                boolean hit = i % 4 == 0;
                futures.add(pool.submit(() -> ledger.applyOnce(UUID.randomUUID(), Instant.now(),
                        charge("0.001", hit))));
            }
            for (Future<Boolean> f : futures) {
                assertThat(f.get()).isTrue();
            }
        }
        DailyUsage row = ledger.daily("acme", LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 27)).get(0);
        assertThat(row.requests()).isEqualTo(events);
        assertThat(row.cacheHits()).isEqualTo(events / 4);
        assertThat(row.promptTokens()).isEqualTo(events * 11L);
        assertThat(row.cost()).isEqualByComparingTo("0.2");
    }

    @Test
    void draftIsReplacedInPlaceAndIssuedInvoiceIsLeftAlone() {
        BillingPeriod oct = BillingPeriod.parse("2026-10");
        ledger.applyOnce(UUID.randomUUID(), Instant.now(), charge("0.5", false));
        Invoice first = Invoice.draft(UUID.randomUUID(), "acme", oct,
                ledger.daily("acme", oct.firstDay(), oct.lastDay()), Instant.now());
        invoices.saveDraft(first);

        ledger.applyOnce(UUID.randomUUID(), Instant.now(), charge("0.25", false));
        Invoice second = Invoice.draft(UUID.randomUUID(), "acme", oct,
                ledger.daily("acme", oct.firstDay(), oct.lastDay()), Instant.now());
        invoices.saveDraft(second);

        Invoice stored = invoices.find("acme", oct).orElseThrow();
        assertThat(stored.id()).isEqualTo(first.id());
        assertThat(stored.total()).isEqualByComparingTo("0.75");
        assertThat(stored.lines()).singleElement().satisfies(l -> assertThat(l.requests()).isEqualTo(2));

        assertThat(invoices.markIssued(second.issue(Instant.now()))).isTrue();
        assertThat(invoices.markIssued(second.issue(Instant.now()))).isFalse();
        invoices.saveDraft(Invoice.draft(UUID.randomUUID(), "acme", oct, List.of(), Instant.now()));
        Invoice afterIssue = invoices.find("acme", oct).orElseThrow();
        assertThat(afterIssue.status()).isEqualTo(Invoice.Status.ISSUED);
        assertThat(afterIssue.total()).isEqualByComparingTo("0.75");
        assertThat(afterIssue.lines()).hasSize(1);
    }

    private static UsageCharge charge(String cost, boolean hit) {
        return new UsageCharge("acme", LocalDate.of(2026, 10, 27), "gpt-4o-mini", "alpha", 11, 10,
                new BigDecimal(cost), hit, true);
    }
}
