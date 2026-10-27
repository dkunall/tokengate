package com.tokengate.gateway.domain.pii;

public enum PiiType {
    EMAIL("EMAIL"), PHONE_IN("PHONE"), AADHAAR("AADHAAR"), PAN("PAN"), CARD("CARD"), CUSTOM_TERM("TERM");

    private final String label;

    PiiType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
