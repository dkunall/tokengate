package com.tokengate.gateway.domain.chat;

import java.util.Locale;

public enum Role {
    SYSTEM, USER, ASSISTANT;

    public static Role parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("message role is required");
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "system", "developer" -> SYSTEM;
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            default -> throw new IllegalArgumentException("unsupported message role: " + raw);
        };
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
