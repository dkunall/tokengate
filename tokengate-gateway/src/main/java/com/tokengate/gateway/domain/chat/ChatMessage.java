package com.tokengate.gateway.domain.chat;

import java.util.Objects;

public record ChatMessage(Role role, String content) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public ChatMessage withContent(String newContent) {
        return new ChatMessage(role, newContent);
    }
}
