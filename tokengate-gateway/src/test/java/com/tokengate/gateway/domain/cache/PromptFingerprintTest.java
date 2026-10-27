package com.tokengate.gateway.domain.cache;

import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.chat.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptFingerprintTest {

    private static ChatRequest req(double temp, ChatMessage... msgs) {
        return new ChatRequest("tg-small", List.of(msgs), temp, null, null, null, false, null);
    }

    @Test
    void whitespaceAndUnicodeFormsDoNotChangeTheKey() {
        String a = PromptFingerprint.exactKey("t", req(0, ChatMessage.user("  What is   the\ncapital of France? ")));
        String b = PromptFingerprint.exactKey("t", req(0, ChatMessage.user("What is the capital of France?")));
        String nfd = PromptFingerprint.exactKey("t", req(0, ChatMessage.user("Cafe\u0301")));
        String nfc = PromptFingerprint.exactKey("t", req(0, ChatMessage.user("Caf\u00e9")));
        assertThat(a).isEqualTo(b);
        assertThat(nfd).isEqualTo(nfc);
    }

    @Test
    void caseTenantParamsAndRolesDoChangeIt() {
        ChatRequest base = req(0, ChatMessage.user("hello"));
        String k = PromptFingerprint.exactKey("t", base);
        assertThat(PromptFingerprint.exactKey("t", req(0, ChatMessage.user("Hello")))).isNotEqualTo(k);
        assertThat(PromptFingerprint.exactKey("other", base)).isNotEqualTo(k);
        assertThat(PromptFingerprint.exactKey("t", req(0.5, ChatMessage.user("hello")))).isNotEqualTo(k);
        assertThat(PromptFingerprint.exactKey("t", req(0, ChatMessage.system("hello"), ChatMessage.user("x"))))
                .isNotEqualTo(PromptFingerprint.exactKey("t", req(0, ChatMessage.user("hello"), ChatMessage.user("x"))));
    }

    @Test
    void messageBoundariesCannotCollide() {
        String a = PromptFingerprint.exactKey("t", req(0, ChatMessage.user("ab"), ChatMessage.user("c")));
        String b = PromptFingerprint.exactKey("t", req(0, ChatMessage.user("a"), ChatMessage.user("bc")));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void contextKeyIgnoresOnlyTheLastUserMessage() {
        ChatMessage sys = ChatMessage.system("You are terse.");
        String c1 = PromptFingerprint.contextKey("t", req(0, sys, ChatMessage.user("capital of France?")));
        String c2 = PromptFingerprint.contextKey("t", req(0, sys, ChatMessage.user("France's capital city?")));
        String c3 = PromptFingerprint.contextKey("t", req(0, ChatMessage.system("You are verbose."), ChatMessage.user("capital of France?")));
        assertThat(c1).isEqualTo(c2).isNotEqualTo(c3);
        assertThat(PromptFingerprint.semanticText(req(0, sys, new ChatMessage(Role.USER, "  a  b "))))
                .isEqualTo("a b");
    }
}
