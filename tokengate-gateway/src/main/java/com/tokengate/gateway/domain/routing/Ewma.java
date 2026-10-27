package com.tokengate.gateway.domain.routing;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Exponentially weighted moving average, lock-free. The double lives in an AtomicLong as raw bits so
 * concurrent samples never lose an update.
 */
public final class Ewma {

    private static final long EMPTY = Double.doubleToRawLongBits(Double.NaN);

    private final double alpha;
    private final AtomicLong bits = new AtomicLong(EMPTY);

    public Ewma(double alpha) {
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be in (0, 1]");
        }
        this.alpha = alpha;
    }

    public void record(double sample) {
        while (true) {
            long current = bits.get();
            double prev = Double.longBitsToDouble(current);
            double next = Double.isNaN(prev) ? sample : prev + alpha * (sample - prev);
            if (bits.compareAndSet(current, Double.doubleToRawLongBits(next))) {
                return;
            }
        }
    }

    public boolean hasSamples() {
        return !Double.isNaN(Double.longBitsToDouble(bits.get()));
    }

    public double valueOr(double fallback) {
        double v = Double.longBitsToDouble(bits.get());
        return Double.isNaN(v) ? fallback : v;
    }
}
