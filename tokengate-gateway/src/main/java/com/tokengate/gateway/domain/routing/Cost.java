package com.tokengate.gateway.domain.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money in micro-dollars (1 USD = 1,000,000). Integer arithmetic so Redis counters and sums stay exact. */
public record Cost(long micros) implements Comparable<Cost> {

    public static final Cost ZERO = new Cost(0);

    public static Cost ofUsd(String usd) {
        return new Cost(new BigDecimal(usd).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    public Cost plus(Cost other) {
        return new Cost(Math.addExact(micros, other.micros));
    }

    public BigDecimal usd() {
        return BigDecimal.valueOf(micros).movePointLeft(6);
    }

    @Override
    public int compareTo(Cost o) {
        return Long.compare(micros, o.micros);
    }
}
