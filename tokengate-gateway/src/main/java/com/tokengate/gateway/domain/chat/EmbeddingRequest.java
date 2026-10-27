package com.tokengate.gateway.domain.chat;

import java.util.List;

public record EmbeddingRequest(String model, List<String> input) {

    public EmbeddingRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("input must not be empty");
        }
        input = List.copyOf(input);
    }
}
