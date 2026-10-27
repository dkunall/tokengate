package com.tokengate.mock.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Deterministic fake model output, so tests and demos can assert on it. */
public final class Replies {

    private static final int ECHO_LIMIT = 120;

    private Replies() {
    }

    public static String answer(String upstream, String model, String lastUserMessage) {
        String echo = lastUserMessage.length() > ECHO_LIMIT ? lastUserMessage.substring(0, ECHO_LIMIT) + "..." : lastUserMessage;
        return "[" + upstream + "/" + model + "] You asked: " + echo;
    }

    /** Splits on spaces but keeps them, so concatenating the chunks gives back the exact text. */
    public static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == ' ') {
                out.add(text.substring(start, i));
                start = i;
            }
        }
        return out;
    }

    /** Roughly the 4-characters-per-token rule of thumb; real tokenisation happens in the gateway. */
    public static int estimateTokens(String text) {
        return Math.max(1, (text.length() + 3) / 4);
    }

    public static int countWords(String text) {
        String t = text.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    public static float[] embedding(String text, int dims) {
        float[] v = new float[dims];
        long h = 1125899906842597L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h = 31 * h + b;
        }
        SplittableRandom r = new SplittableRandom(h);
        double norm = 0;
        for (int i = 0; i < dims; i++) {
            v[i] = (float) r.nextGaussian();
            norm += v[i] * v[i];
        }
        float inv = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < dims; i++) {
            v[i] *= inv;
        }
        return v;
    }
}
