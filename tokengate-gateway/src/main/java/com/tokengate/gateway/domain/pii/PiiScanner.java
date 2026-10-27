package com.tokengate.gateway.domain.pii;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds PII spans. Each structured type is a precompiled regex for the shape plus, where one exists, a checksum
 * that throws out look-alikes: Aadhaar must pass Verhoeff, card numbers must pass Luhn. Overlaps resolve to the
 * leftmost, then longest, then most specific match, so "4111 1111 1111 1111" is one CARD and not a PHONE
 * inside it.
 */
public final class PiiScanner {

    private record Rule(PiiType type, Pattern pattern, Predicate<String> validator) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(PiiType.EMAIL, Pattern.compile(
                    "(?<![A-Za-z0-9._%+-])[A-Za-z0-9](?:[A-Za-z0-9._%+-]{0,62}[A-Za-z0-9_%+-])?@(?:[A-Za-z0-9-]{1,63}\\.)+[A-Za-z]{2,24}(?![A-Za-z0-9-])"),
                    s -> true),
            new Rule(PiiType.CARD, Pattern.compile("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])"),
                    s -> Luhn.isValid(digits(s))),
            new Rule(PiiType.AADHAAR, Pattern.compile("(?<![\\d-])[2-9]\\d{3}([ -]?)\\d{4}\\1\\d{4}(?![\\d-])"),
                    s -> Verhoeff.isValid(digits(s))),
            new Rule(PiiType.PAN, Pattern.compile("(?<![A-Za-z0-9])[A-Za-z]{3}[ABCFGHLJPTabcfghljpt][A-Za-z]\\d{4}[A-Za-z](?![A-Za-z0-9])"),
                    s -> true),
            new Rule(PiiType.PHONE_IN, Pattern.compile("(?<![\\d+])(?:\\+91[ -]?|0091[ -]?|0)?[6-9]\\d{4}[ -]?\\d{5}(?![\\d])"),
                    s -> true));

    private static final Comparator<PiiMatch> PREFERENCE = Comparator.comparingInt(PiiMatch::start)
            .thenComparing(Comparator.comparingInt(PiiMatch::length).reversed())
            .thenComparingInt(m -> m.type().ordinal());

    private final AhoCorasick customTerms;

    public PiiScanner(AhoCorasick customTerms) {
        this.customTerms = customTerms;
    }

    public static PiiScanner standard() {
        return new PiiScanner(new AhoCorasick(List.of()));
    }

    public List<PiiMatch> scan(String text) {
        List<PiiMatch> found = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                if (rule.validator().test(m.group())) {
                    found.add(new PiiMatch(m.start(), m.end(), rule.type(), m.group()));
                }
            }
        }
        if (!customTerms.isEmpty()) {
            for (AhoCorasick.Hit hit : customTerms.findAll(text)) {
                if (wordBoundary(text, hit.start(), hit.end())) {
                    found.add(new PiiMatch(hit.start(), hit.end(), PiiType.CUSTOM_TERM, text.substring(hit.start(), hit.end())));
                }
            }
        }
        return resolveOverlaps(found);
    }

    static List<PiiMatch> resolveOverlaps(List<PiiMatch> found) {
        found.sort(PREFERENCE);
        List<PiiMatch> kept = new ArrayList<>(found.size());
        for (PiiMatch m : found) {
            if (kept.isEmpty() || !kept.getLast().overlaps(m)) {
                kept.add(m);
            }
        }
        return kept;
    }

    private static boolean wordBoundary(String text, int start, int end) {
        boolean leftOk = start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1));
        boolean rightOk = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
        return leftOk && rightOk;
    }

    static String digits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
