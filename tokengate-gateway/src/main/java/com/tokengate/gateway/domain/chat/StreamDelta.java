package com.tokengate.gateway.domain.chat;

/**
 * One piece of a streamed completion. Providers send usage either on the last chunk (OpenAI with
 * include_usage, Gemini) or split across events (Anthropic), so {@code usage} is optional on every delta.
 */
public record StreamDelta(String content, String finishReason, TokenUsage usage) {

    public static StreamDelta text(String content) {
        return new StreamDelta(content, null, null);
    }

    public boolean hasContent() {
        return content != null && !content.isEmpty();
    }
}
