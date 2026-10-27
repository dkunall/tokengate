package com.tokengate.gateway.domain.routing;

import com.tokengate.gateway.domain.chat.TokenUsage;

/** List price per 1,000 tokens, in micro-dollars. Rounds up: a 1-token call still costs something. */
public record Price(long inputMicrosPer1k, long outputMicrosPer1k) {

    public static final Price FREE = new Price(0, 0);

    public Price {
        if (inputMicrosPer1k < 0 || outputMicrosPer1k < 0) {
            throw new IllegalArgumentException("price cannot be negative");
        }
    }

    public Cost cost(TokenUsage usage) {
        return cost(usage.promptTokens(), usage.completionTokens());
    }

    public Cost cost(long promptTokens, long completionTokens) {
        long scaled = Math.addExact(Math.multiplyExact(promptTokens, inputMicrosPer1k),
                Math.multiplyExact(completionTokens, outputMicrosPer1k));
        return new Cost(Math.ceilDiv(scaled, 1000));
    }
}
