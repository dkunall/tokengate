package com.tokengate.gateway.domain.pii;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker("test-only-secret-0123456789".getBytes(StandardCharsets.UTF_8));
    private final PiiScanner scanner = PiiScanner.standard();

    @Test
    void tokenizeRoundTrips() {
        MaskingSession s = new MaskingSession();
        String masked = masker.mask("Email kunal@example.com or call +91 98765 43210", scanner, PiiMode.TOKENIZE, "acme", s);

        assertThat(masked).doesNotContain("kunal@example.com").doesNotContain("98765")
                .matches("Email <EMAIL_[0-9a-f]{8}> or call <PHONE_[0-9a-f]{8}>");
        assertThat(s.unmask("Sure, I'll write to " + masked.substring(6, 22) + "."))
                .isEqualTo("Sure, I'll write to kunal@example.com.");
        assertThat(s.total()).isEqualTo(2);
        assertThat(s.counts()).containsEntry(PiiType.EMAIL, 1).containsEntry(PiiType.PHONE_IN, 1);
    }

    @Test
    void tokensAreStablePerTenantAndCanonicalValue() {
        String a = masker.mask("Kunal@Example.com", scanner, PiiMode.TOKENIZE, "acme", new MaskingSession());
        String b = masker.mask("kunal@example.com", scanner, PiiMode.TOKENIZE, "acme", new MaskingSession());
        String c = masker.mask("kunal@example.com", scanner, PiiMode.TOKENIZE, "globex", new MaskingSession());
        String p1 = masker.mask("+91 98765 43210", scanner, PiiMode.TOKENIZE, "acme", new MaskingSession());
        String p2 = masker.mask("09876543210", scanner, PiiMode.TOKENIZE, "acme", new MaskingSession());
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
        assertThat(p1).isEqualTo(p2);
    }

    @Test
    void redactAndOff() {
        MaskingSession s = new MaskingSession();
        assertThat(masker.mask("card 4111 1111 1111 1111", scanner, PiiMode.REDACT, "acme", s)).isEqualTo("card [CARD]");
        assertThat(s.total()).isEqualTo(1);
        assertThat(s.unmask("card [CARD]")).isEqualTo("card [CARD]");
        assertThat(masker.mask("a@b.io", scanner, PiiMode.OFF, "acme", new MaskingSession())).isEqualTo("a@b.io");
    }

    @Test
    void unknownTokensStayMasked() {
        MaskingSession s = new MaskingSession();
        masker.mask("x@y.io", scanner, PiiMode.TOKENIZE, "acme", s);
        assertThat(s.unmask("someone else: <EMAIL_deadbeef>")).isEqualTo("someone else: <EMAIL_deadbeef>");
    }

    @Test
    void replacementTextIsNotInterpretedAsRegex() {
        MaskingSession s = new MaskingSession();
        PiiScanner custom = new PiiScanner(new AhoCorasick(List.of("price$1\\x")));
        String masked = masker.mask("the price$1\\x deal", custom, PiiMode.TOKENIZE, "acme", s);
        assertThat(masked).matches("the <TERM_[0-9a-f]{8}> deal");
        assertThat(s.unmask(masked)).isEqualTo("the price$1\\x deal");
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new PiiMasker(new byte[8])).isInstanceOf(IllegalArgumentException.class);
    }
}
