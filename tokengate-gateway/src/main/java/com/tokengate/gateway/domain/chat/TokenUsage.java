package com.tokengate.gateway.domain.chat;

public record TokenUsage(long promptTokens, long completionTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public TokenUsage {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
    }

    public long total() {
        return promptTokens + completionTokens;
    }
}
