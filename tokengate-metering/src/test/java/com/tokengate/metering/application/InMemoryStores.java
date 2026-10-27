package com.tokengate.metering.application;

import com.tokengate.metering.application.port.InvoiceStore;
import com.tokengate.metering.application.port.PriceCatalog;
import com.tokengate.metering.application.port.UsageLedger;
import com.tokengate.metering.domain.BillingPeriod;
import com.tokengate.metering.domain.DailyUsage;
import com.tokengate.metering.domain.Invoice;
import com.tokengate.metering.domain.PriceVersion;
import com.tokengate.metering.domain.UsageCharge;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class InMemoryStores {

    static final class Ledger implements UsageLedger {
        final Set<UUID> seen = new HashSet<>();
        final Map<String, DailyUsage> rows = new HashMap<>();

        @Override
        public synchronized boolean applyOnce(UUID eventId, Instant occurredAt, UsageCharge c) {
            if (!seen.add(eventId)) {
                return false;
            }
            rows.merge(c.tenantId() + c.day() + c.model() + c.provider(),
                    new DailyUsage(c.tenantId(), c.day(), c.model(), c.provider(), 1, c.cacheHit() ? 1 : 0,
                            c.priced() ? 0 : 1, c.promptTokens(), c.completionTokens(), c.cost()),
                    (a, b) -> new DailyUsage(a.tenantId(), a.day(), a.model(), a.provider(),
                            a.requests() + b.requests(), a.cacheHits() + b.cacheHits(),
                            a.unpricedRequests() + b.unpricedRequests(), a.promptTokens() + b.promptTokens(),
                            a.completionTokens() + b.completionTokens(), a.cost().add(b.cost())));
            return true;
        }

        @Override
        public synchronized List<DailyUsage> daily(String tenantId, LocalDate from, LocalDate to) {
            return rows.values().stream()
                    .filter(d -> d.tenantId().equals(tenantId) && !d.day().isBefore(from) && !d.day().isAfter(to))
                    .toList();
        }
    }

    static final class Prices implements PriceCatalog {
        final List<PriceVersion> versions = new ArrayList<>();
        int reads;

        @Override
        public List<PriceVersion> all() {
            reads++;
            return List.copyOf(versions);
        }

        @Override
        public void append(PriceVersion version) {
            versions.add(version);
        }
    }

    static final class Invoices implements InvoiceStore {
        final Map<String, Invoice> byKey = new HashMap<>();

        @Override
        public Optional<Invoice> find(String tenantId, BillingPeriod period) {
            return Optional.ofNullable(byKey.get(tenantId + period));
        }

        @Override
        public void saveDraft(Invoice draft) {
            byKey.put(draft.tenantId() + draft.period(), draft);
        }

        @Override
        public boolean markIssued(Invoice issued) {
            Invoice current = byKey.get(issued.tenantId() + issued.period());
            if (current == null || current.status() != Invoice.Status.DRAFT) {
                return false;
            }
            byKey.put(issued.tenantId() + issued.period(), issued);
            return true;
        }

        @Override
        public List<Invoice> forTenant(String tenantId) {
            return byKey.values().stream().filter(i -> i.tenantId().equals(tenantId)).toList();
        }
    }

    private InMemoryStores() {
    }
}
