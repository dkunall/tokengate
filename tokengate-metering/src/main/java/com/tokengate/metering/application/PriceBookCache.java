package com.tokengate.metering.application;

import com.tokengate.metering.application.port.PriceCatalog;
import com.tokengate.metering.domain.PriceBook;
import com.tokengate.metering.domain.PriceVersion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Prices change rarely and every event needs one, so ingestion reads a snapshot refreshed every
 * {@code ttl}. A price appended through this class invalidates the snapshot immediately; one appended
 * straight into the table by another instance shows up within the TTL.
 */
public final class PriceBookCache {

    private record Snapshot(PriceBook book, Instant loadedAt) {
    }

    private final PriceCatalog catalog;
    private final Clock clock;
    private final Duration ttl;
    private volatile Snapshot snapshot;

    public PriceBookCache(PriceCatalog catalog, Clock clock, Duration ttl) {
        this.catalog = catalog;
        this.clock = clock;
        this.ttl = ttl;
    }

    public PriceBook current() {
        Snapshot s = snapshot;
        Instant now = clock.instant();
        if (s == null || s.loadedAt().plus(ttl).isBefore(now)) {
            s = new Snapshot(PriceBook.of(catalog.all()), now);
            snapshot = s;
        }
        return s.book();
    }

    public void append(PriceVersion version) {
        PriceBook book = PriceBook.of(catalog.all());
        book.latest(version.provider(), version.providerModel()).ifPresent(latest -> {
            if (!version.effectiveFrom().isAfter(latest.effectiveFrom())) {
                throw new IllegalArgumentException("a new price for " + version.provider() + "/"
                        + version.providerModel() + " must start after " + latest.effectiveFrom()
                        + "; past prices are not rewritten");
            }
        });
        catalog.append(version);
        snapshot = null;
    }
}
