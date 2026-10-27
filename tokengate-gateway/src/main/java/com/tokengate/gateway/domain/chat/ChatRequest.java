package com.tokengate.gateway.domain.chat;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Double topP,
        Integer maxTokens,
        List<String> stop,
        boolean stream,
        String user) {

    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw new IllegalArgumentException("max_tokens must be positive");
        }
        messages = List.copyOf(messages);
        stop = stop == null ? List.of() : List.copyOf(stop);
    }

    /** Providers default temperature to 1, so only an explicit 0 counts as deterministic. */
    public boolean deterministic() {
        return temperature != null && temperature == 0.0;
    }

    public ChatRequest mapContents(UnaryOperator<String> f) {
        return new ChatRequest(model, messages.stream().map(m -> m.withContent(f.apply(m.content()))).toList(),
                temperature, topP, maxTokens, stop, stream, user);
    }

    public ChatMessage lastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return messages.get(i);
            }
        }
        throw new IllegalArgumentException("at least one user message is required");
    }
}
