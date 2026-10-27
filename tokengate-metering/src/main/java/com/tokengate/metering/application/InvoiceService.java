package com.tokengate.metering.application;

import com.tokengate.metering.application.port.InvoiceStore;
import com.tokengate.metering.application.port.UsageLedger;
import com.tokengate.metering.domain.BillingPeriod;
import com.tokengate.metering.domain.Invoice;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Generating is idempotent: the same tenant and period always map to one invoice row, and regenerating a
 * draft rebuilds its lines from the current daily totals. Once issued, an invoice is frozen.
 */
public final class InvoiceService {

    private final UsageLedger ledger;
    private final InvoiceStore invoices;
    private final Clock clock;

    public InvoiceService(UsageLedger ledger, InvoiceStore invoices, Clock clock) {
        this.ledger = ledger;
        this.invoices = invoices;
        this.clock = clock;
    }

    public Invoice generate(String tenantId, BillingPeriod period) {
        Optional<Invoice> existing = invoices.find(tenantId, period);
        if (existing.isPresent() && existing.get().status() == Invoice.Status.ISSUED) {
            throw new InvoiceAlreadyIssued(tenantId, period.toString());
        }
        UUID id = existing.map(Invoice::id).orElseGet(UUID::randomUUID);
        Invoice draft = Invoice.draft(id, tenantId, period,
                ledger.daily(tenantId, period.firstDay(), period.lastDay()), clock.instant());
        invoices.saveDraft(draft);
        return draft;
    }

    public Invoice issue(String tenantId, BillingPeriod period) {
        Invoice issued = generate(tenantId, period).issue(clock.instant());
        if (!invoices.markIssued(issued)) {
            throw new InvoiceAlreadyIssued(tenantId, period.toString());
        }
        return issued;
    }

    public Optional<Invoice> find(String tenantId, BillingPeriod period) {
        return invoices.find(tenantId, period);
    }

    public List<Invoice> forTenant(String tenantId) {
        return invoices.forTenant(tenantId);
    }
}
