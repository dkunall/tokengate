package com.tokengate.gateway.domain.routing;

public record ProviderId(String value) implements Comparable<ProviderId> {

    public ProviderId {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,39}")) {
            throw new IllegalArgumentException("invalid provider id: " + value);
        }
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }

    @Override
    public int compareTo(ProviderId o) {
        return value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
