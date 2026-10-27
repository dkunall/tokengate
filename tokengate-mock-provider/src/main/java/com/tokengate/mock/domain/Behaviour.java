package com.tokengate.mock.domain;

/**
 * How one fake upstream misbehaves. errorRate is a probability in [0, 1]; failMidStreamAfterChunks below zero
 * means never. completionTokens of zero means "count the words of the reply".
 */
public record Behaviour(
        long latencyMs,
        long jitterMs,
        double errorRate,
        int errorStatus,
        boolean outage,
        long chunkDelayMs,
        int failMidStreamAfterChunks,
        int completionTokens) {

    public Behaviour {
        if (latencyMs < 0 || jitterMs < 0 || chunkDelayMs < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("durations and token counts cannot be negative");
        }
        if (errorRate < 0 || errorRate > 1) {
            throw new IllegalArgumentException("errorRate must be within [0, 1]");
        }
        if (errorStatus < 400 || errorStatus > 599) {
            throw new IllegalArgumentException("errorStatus must be a 4xx or 5xx code");
        }
    }

    public static Behaviour healthy(long latencyMs) {
        return new Behaviour(latencyMs, 0, 0, 500, false, 20, -1, 0);
    }
}
