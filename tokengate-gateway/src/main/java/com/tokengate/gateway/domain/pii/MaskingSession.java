package com.tokengate.gateway.domain.pii;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lives for one request. Remembers which token stands for which original value so the response can be
 * unmasked for this caller only. A token the session does not know (for example one that came back from a
 * semantic cache hit created by someone else's prompt) is left masked.
 */
public final class MaskingSession {

    static final Pattern TOKEN = Pattern.compile("<(EMAIL|PHONE|AADHAAR|PAN|CARD|TERM)_[0-9a-f]{8}>");
    static final int MAX_TOKEN_LENGTH = "<AADHAAR_00000000>".length();

    private final Map<String, String> originals = new LinkedHashMap<>();
    private final Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);

    void remember(String token, String original, PiiType type) {
        originals.putIfAbsent(token, original);
        counts.merge(type, 1, Integer::sum);
    }

    void count(PiiType type) {
        counts.merge(type, 1, Integer::sum);
    }

    public String unmask(String text) {
        if (originals.isEmpty() || text == null || text.indexOf('<') < 0) {
            return text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            String original = originals.get(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(original != null ? original : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public Map<PiiType, Integer> counts() {
        return Map.copyOf(counts);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public StreamingUnmasker streaming() {
        return new StreamingUnmasker(this);
    }
}
