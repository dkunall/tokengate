package com.tokengate.gateway.domain.pii;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiScannerTest {

    private final PiiScanner scanner = PiiScanner.standard();

    private static String validAadhaar() {
        String body = "23412341234";
        return body + Verhoeff.checkDigit(body);
    }

    private List<PiiType> types(String text) {
        return scanner.scan(text).stream().map(PiiMatch::type).toList();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "mail me at kunal.k+tg@example.co.in please|kunal.k+tg@example.co.in",
            "<a@b.io>|a@b.io",
            "A_B@sub.domain.org.|A_B@sub.domain.org"})
    void emails(String text, String expected) {
        assertThat(scanner.scan(text)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo(PiiType.EMAIL);
            assertThat(m.value()).isEqualTo(expected);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"call +91 98765 43210 now", "call +91-9876543210", "call 09876543210", "call 9876543210.",
            "call 0091 9876543210"})
    void indianMobileNumbers(String text) {
        assertThat(types(text)).containsExactly(PiiType.PHONE_IN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order 5876543210123", "pin 1234567890", "id 98765432101"})
    void numbersThatAreNotMobiles(String text) {
        assertThat(types(text)).doesNotContain(PiiType.PHONE_IN);
    }

    @Test
    void aadhaarNeedsAValidVerhoeffDigitAndConsistentSeparators() {
        String a = validAadhaar();
        String spaced = a.substring(0, 4) + " " + a.substring(4, 8) + " " + a.substring(8);
        assertThat(types("aadhaar " + a)).containsExactly(PiiType.AADHAAR);
        assertThat(types("aadhaar " + spaced)).containsExactly(PiiType.AADHAAR);

        String wrongCheck = a.substring(0, 11) + ((a.charAt(11) - '0' + 1) % 10);
        assertThat(types("aadhaar " + wrongCheck)).isEmpty();
        String mixed = a.substring(0, 4) + " " + a.substring(4, 8) + "-" + a.substring(8);
        assertThat(types("aadhaar " + mixed)).doesNotContain(PiiType.AADHAAR);
        assertThat(types("aadhaar 1" + a.substring(1))).doesNotContain(PiiType.AADHAAR);
    }

    @Test
    void panUsesTheHolderTypeLetter() {
        assertThat(types("PAN ABCPE1234F")).containsExactly(PiiType.PAN);
        assertThat(types("pan abcpe1234f")).containsExactly(PiiType.PAN);
        assertThat(types("PAN ABCXE1234F")).isEmpty();
        assertThat(types("PAN ABCPE1234FX")).isEmpty();
    }

    @Test
    void cardsMustPassLuhnAndWinOverShorterOverlaps() {
        assertThat(types("card 4111 1111 1111 1111 exp 12/29")).containsExactly(PiiType.CARD);
        assertThat(types("card 4111-1111-1111-1111")).containsExactly(PiiType.CARD);
        assertThat(types("card 4111111111111112")).isEmpty();
    }

    @Test
    void customTermsRespectWordBoundaries() {
        PiiScanner custom = new PiiScanner(new AhoCorasick(List.of("falcon")));
        assertThat(custom.scan("Project Falcon launch")).extracting(PiiMatch::type).containsExactly(PiiType.CUSTOM_TERM);
        assertThat(custom.scan("falconry is old")).isEmpty();
    }

    @Test
    void mixedTextReturnsMatchesInOrderWithoutOverlap() {
        String text = "I'm kunal@example.com, +91 9876543210, PAN ABCPE1234F, aadhaar " + validAadhaar();
        List<PiiMatch> m = scanner.scan(text);
        assertThat(m).extracting(PiiMatch::type)
                .containsExactly(PiiType.EMAIL, PiiType.PHONE_IN, PiiType.PAN, PiiType.AADHAAR);
        for (int i = 1; i < m.size(); i++) {
            assertThat(m.get(i).start()).isGreaterThanOrEqualTo(m.get(i - 1).end());
        }
    }

    @Test
    void plainTextHasNoMatches() {
        assertThat(scanner.scan("What is the capital of France? Answer in 1 word.")).isEmpty();
        assertThat(scanner.scan("")).isEmpty();
    }
}
