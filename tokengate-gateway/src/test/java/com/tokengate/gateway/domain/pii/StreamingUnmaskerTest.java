package com.tokengate.gateway.domain.pii;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingUnmaskerTest {

    private final PiiMasker masker = new PiiMasker("test-only-secret-0123456789".getBytes(StandardCharsets.UTF_8));

    private MaskingSession sessionFor(String text) {
        MaskingSession s = new MaskingSession();
        String masked = masker.mask(text, PiiScanner.standard(), PiiMode.TOKENIZE, "acme", s);
        assertThat(masked).isNotEqualTo(text);
        return s;
    }

    private String tokenOf(String value) {
        return masker.mask(value, PiiScanner.standard(), PiiMode.TOKENIZE, "acme", new MaskingSession());
    }

    @Test
    void tokenSplitAcrossChunksIsRestored() {
        MaskingSession s = sessionFor("kunal@example.com");
        String token = tokenOf("kunal@example.com");
        StreamingUnmasker u = s.streaming();
        StringBuilder out = new StringBuilder();
        out.append(u.feed("Write to " + token.substring(0, 4)));
        assertThat(out.toString()).isEqualTo("Write to ");
        out.append(u.feed(token.substring(4, 10)));
        out.append(u.feed(token.substring(10) + " today."));
        out.append(u.finish());
        assertThat(out).hasToString("Write to kunal@example.com today.");
    }

    @Test
    void characterByCharacterStreamGivesTheSameResult() {
        MaskingSession s = sessionFor("kunal@example.com and 9876543210");
        String text = "A " + tokenOf("kunal@example.com") + " < 3 and " + tokenOf("9876543210") + "<";
        StringBuilder out = new StringBuilder();
        StreamingUnmasker u = s.streaming();
        for (char c : text.toCharArray()) {
            out.append(u.feed(String.valueOf(c)));
        }
        out.append(u.finish());
        assertThat(out).hasToString("A kunal@example.com < 3 and 9876543210<");
    }

    @Test
    void strayAngleBracketIsReleasedOnceItCannotBeAToken() {
        MaskingSession s = sessionFor("a@b.io");
        StreamingUnmasker u = s.streaming();
        assertThat(u.feed("x < y")).isEqualTo("x ");
        assertThat(u.feed(" and this is long enough")).isEqualTo("< y and this is long enough");
        assertThat(u.finish()).isEmpty();
    }
}
