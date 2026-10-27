package com.tokengate.gateway.domain.tenant;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeySecretTest {

    private final byte[] pepper = "test-pepper-not-a-secret".getBytes(StandardCharsets.UTF_8);

    @Test
    void generatedKeysParseAndVerify() {
        ApiKeySecret.Issued issued = ApiKeySecret.generate();
        assertThat(issued.plaintext()).matches("tg_[0-9A-Za-z]{8}_[0-9A-Za-z]{32}");
        ApiKeySecret.Parsed parsed = ApiKeySecret.parse(issued.plaintext()).orElseThrow();
        assertThat(parsed.prefix()).isEqualTo(issued.prefix());
        String stored = ApiKeySecret.hash(issued.secret(), pepper);
        assertThat(stored).hasSize(64).doesNotContain(issued.secret());
        assertThat(ApiKeySecret.matches(parsed.secret(), stored, pepper)).isTrue();
        assertThat(ApiKeySecret.matches(parsed.secret() + "x", stored, pepper)).isFalse();
        assertThat(ApiKeySecret.matches(parsed.secret(), stored, "other-pepper-value-xx".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void rejectsMalformedKeys() {
        assertThat(ApiKeySecret.parse(null)).isEmpty();
        assertThat(ApiKeySecret.parse("sk-abc")).isEmpty();
        assertThat(ApiKeySecret.parse("tg_short_abc")).isEmpty();
        assertThat(ApiKeySecret.parse("tg_ABCDEFGH_" + "a".repeat(31) + "!")).isEmpty();
    }

    @Test
    void keysAreUnique() {
        assertThat(ApiKeySecret.generate().plaintext()).isNotEqualTo(ApiKeySecret.generate().plaintext());
    }
}
