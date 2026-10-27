package com.tokengate.gateway.domain.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRequestTest {

    @Test
    void validatesInput() {
        assertThatThrownBy(() -> new ChatRequest("m", List.of(), null, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatRequest(" ", List.of(ChatMessage.user("x")), null, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatRequest("m", List.of(ChatMessage.user("x")), 3.0, null, null, null, false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyExplicitZeroTemperatureIsDeterministic() {
        assertThat(new ChatRequest("m", List.of(ChatMessage.user("x")), 0.0, null, null, null, false, null).deterministic()).isTrue();
        assertThat(new ChatRequest("m", List.of(ChatMessage.user("x")), null, null, null, null, false, null).deterministic()).isFalse();
    }

    @Test
    void mapContentsTouchesEveryMessageAndRoleParsingAcceptsDeveloper() {
        ChatRequest r = new ChatRequest("m", List.of(ChatMessage.system("a"), ChatMessage.user("b")), 0.0, null, null, null, false, null);
        assertThat(r.mapContents(String::toUpperCase).messages()).extracting(ChatMessage::content).containsExactly("A", "B");
        assertThat(r.lastUserMessage().content()).isEqualTo("b");
        assertThat(Role.parse("developer")).isEqualTo(Role.SYSTEM);
        assertThat(Role.parse("assistant")).isEqualTo(Role.ASSISTANT);
    }
}
