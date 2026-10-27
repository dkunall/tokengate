package com.tokengate.metering.application.port;

import com.tokengate.metering.domain.BillingPeriod;
import com.tokengate.metering.domain.Invoice;

import java.util.List;
import java.util.Optional;

public interface InvoiceStore {

    Optional<Invoice> find(String tenantId, BillingPeriod period);

    /** Inserts the draft or replaces an existing draft for the same tenant and period, lines included. */
    void saveDraft(Invoice draft);

    /** @return false if the invoice was not a draft any more (another caller issued it first) */
    boolean markIssued(Invoice issued);

    List<Invoice> forTenant(String tenantId);
}
