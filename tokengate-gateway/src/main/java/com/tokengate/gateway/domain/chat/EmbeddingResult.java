package com.tokengate.gateway.domain.chat;

import java.util.List;

public record EmbeddingResult(List<float[]> vectors, TokenUsage usage, String providerModel) {
}
