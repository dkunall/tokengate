package com.tokengate.gateway.domain.pii;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumTest {

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111111", "5555555555554444", "378282246310005", "6011111111111117", "79927398713"})
    void luhnAcceptsKnownTestNumbers(String n) {
        assertThat(Luhn.isValid(n)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4111111111111112", "79927398710", "", "0", "41111111x1111111"})
    void luhnRejectsBadInput(String n) {
        assertThat(Luhn.isValid(n)).isFalse();
    }

    @Test
    void verhoeffMatchesTheTextbookExample() {
        assertThat(Verhoeff.checkDigit("236")).isEqualTo(3);
        assertThat(Verhoeff.isValid("2363")).isTrue();
        assertThat(Verhoeff.isValid("2364")).isFalse();
    }

    @Test
    void verhoeffCatchesEverySingleDigitErrorAndAdjacentTransposition() {
        String body = "23412341234";
        String valid = body + Verhoeff.checkDigit(body);
        assertThat(Verhoeff.isValid(valid)).isTrue();
        for (int i = 0; i < valid.length(); i++) {
            for (char d = '0'; d <= '9'; d++) {
                if (d == valid.charAt(i)) {
                    continue;
                }
                String typo = valid.substring(0, i) + d + valid.substring(i + 1);
                assertThat(Verhoeff.isValid(typo)).as(typo).isFalse();
            }
        }
        for (int i = 0; i + 1 < valid.length(); i++) {
            if (valid.charAt(i) == valid.charAt(i + 1)) {
                continue;
            }
            char[] c = valid.toCharArray();
            char tmp = c[i];
            c[i] = c[i + 1];
            c[i + 1] = tmp;
            assertThat(Verhoeff.isValid(new String(c))).isFalse();
        }
    }
}
