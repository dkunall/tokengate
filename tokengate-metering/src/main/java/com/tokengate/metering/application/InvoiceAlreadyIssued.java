package com.tokengate.metering.application;

public final class InvoiceAlreadyIssued extends RuntimeException {

    public InvoiceAlreadyIssued(String tenantId, String period) {
        super("invoice for " + tenantId + " " + period + " is already issued and cannot be regenerated");
    }
}
