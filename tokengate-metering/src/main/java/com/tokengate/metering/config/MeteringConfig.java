package com.tokengate.metering.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.metering.adapter.in.kafka.MalformedUsageEvent;
import com.tokengate.metering.adapter.in.kafka.UsageEventListener;
import com.tokengate.metering.adapter.out.jdbc.JdbcInvoiceStore;
import com.tokengate.metering.adapter.out.jdbc.JdbcPriceCatalog;
import com.tokengate.metering.adapter.out.jdbc.JdbcUsageLedger;
import com.tokengate.metering.application.InvoiceService;
import com.tokengate.metering.application.PriceBookCache;
import com.tokengate.metering.application.UsageIngestion;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class MeteringConfig {

    private static final Logger log = LoggerFactory.getLogger(MeteringConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JdbcUsageLedger usageLedger(JdbcClient jdbc, TransactionTemplate tx) {
        return new JdbcUsageLedger(jdbc, tx);
    }

    @Bean
    JdbcInvoiceStore invoiceStore(JdbcClient jdbc, TransactionTemplate tx) {
        return new JdbcInvoiceStore(jdbc, tx);
    }

    @Bean
    PriceBookCache priceBook(JdbcClient jdbc, Clock clock, MeteringProperties props) {
        return new PriceBookCache(new JdbcPriceCatalog(jdbc), clock, props.priceCacheTtl());
    }

    @Bean
    UsageIngestion usageIngestion(JdbcUsageLedger ledger, PriceBookCache prices) {
        return new UsageIngestion(ledger, prices);
    }

    @Bean
    InvoiceService invoiceService(JdbcUsageLedger ledger, JdbcInvoiceStore store, Clock clock) {
        return new InvoiceService(ledger, store, clock);
    }

    @Bean
    UsageEventListener usageEventListener(UsageIngestion ingestion, ObjectMapper json, MeterRegistry meters) {
        return new UsageEventListener(ingestion, json, meters);
    }

    /**
     * Database outages are retried forever, so the consumer stalls on the failing offset instead of
     * skipping usage; a poison record is logged with its offset and skipped, since retrying it cannot help.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(MeteringProperties props) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, e) -> log.error("skipping usage record {}-{}@{}: {}", record.topic(), record.partition(),
                        record.offset(), e.getMessage()),
                new FixedBackOff(props.retryBackoff().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(MalformedUsageEvent.class);
        return handler;
    }
}
