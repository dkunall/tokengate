package com.tokengate.gateway.domain.tenant;

public record TenantId(String value) {

    public TenantId {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{1,39}")) {
            throw new IllegalArgumentException("tenant id must be 2-40 chars of a-z, 0-9 and '-': " + value);
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
