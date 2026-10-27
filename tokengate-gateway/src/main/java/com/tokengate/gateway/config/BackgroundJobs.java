package com.tokengate.gateway.config;

import com.tokengate.gateway.adapter.out.persistence.ConfiguredProviderCatalog;
import com.tokengate.gateway.adapter.out.persistence.PgVectorSemanticCache;
import com.tokengate.gateway.application.outbox.OutboxRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Outbox relay, semantic-cache purge and the provider override refresh. With several gateway replicas the relay
 * is still safe: rows are claimed with SKIP LOCKED, so two instances never publish the same batch at once.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class BackgroundJobs {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);

    private final OutboxRelay relay;
    private final PgVectorSemanticCache semantic;
    private final ConfiguredProviderCatalog catalog;

    BackgroundJobs(OutboxRelay relay, PgVectorSemanticCache semantic, ConfiguredProviderCatalog catalog) {
        this.relay = relay;
        this.semantic = semantic;
        this.catalog = catalog;
    }

    @Scheduled(fixedDelayString = "${tokengate.usage.outbox-interval:PT1S}")
    void relayOutbox() {
        int published;
        do {
            published = relay.relayOnce();
        } while (published > 0 && !Thread.currentThread().isInterrupted());
    }

    @Scheduled(fixedDelayString = "${tokengate.cache.semantic-purge-interval:PT10M}", initialDelayString = "PT1M")
    void purgeSemanticCache() {
        try {
            int removed = semantic.purgeExpired();
            if (removed > 0) {
                log.info("purged {} expired semantic cache entries", removed);
            }
        } catch (RuntimeException e) {
            log.warn("semantic cache purge failed: {}", e.toString());
        }
    }

    // other replicas may have changed weights/enabled through the admin API
    @Scheduled(fixedDelayString = "${tokengate.routing.catalog-refresh:PT15S}", initialDelayString = "PT15S")
    void refreshProviderOverrides() {
        try {
            catalog.refresh();
        } catch (RuntimeException e) {
            log.warn("provider override refresh failed, keeping the previous view: {}", e.toString());
        }
    }
}
