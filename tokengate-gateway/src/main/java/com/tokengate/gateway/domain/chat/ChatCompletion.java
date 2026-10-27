package com.tokengate.gateway.domain.chat;

public record ChatCompletion(String content, String finishReason, TokenUsage usage, String providerModel) {

    public ChatCompletion withContent(String newContent) {
        return new ChatCompletion(newContent, finishReason, usage, providerModel);
    }
}
