package com.tokengate.gateway.adapter.out.embedding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT uncased tokenisation (basic split, then greedy longest-match WordPiece) as used by all-MiniLM-L6-v2.
 * Written by hand to avoid pulling in the DJL tokenizers native library for one model.
 */
final class WordPieceTokenizer {

    private static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final int cls;
    private final int sep;
    private final int unk;

    WordPieceTokenizer(Map<String, Integer> vocab) {
        this.vocab = vocab;
        this.cls = require("[CLS]");
        this.sep = require("[SEP]");
        this.unk = require("[UNK]");
    }

    static WordPieceTokenizer load(Path vocabFile) throws IOException {
        Map<String, Integer> vocab = new HashMap<>();
        List<String> lines = Files.readAllLines(vocabFile, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            vocab.put(lines.get(i), i);
        }
        return new WordPieceTokenizer(vocab);
    }

    /** Ids including [CLS] and [SEP], truncated to {@code maxLength}. */
    long[] encode(String text, int maxLength) {
        List<Integer> ids = new ArrayList<>();
        ids.add(cls);
        outer:
        for (String word : basicSplit(text)) {
            for (int id : wordPiece(word)) {
                if (ids.size() == maxLength - 1) {
                    break outer;
                }
                ids.add(id);
            }
        }
        ids.add(sep);
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ids.get(i);
        }
        return out;
    }

    List<String> basicSplit(String text) {
        String s = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK || cp == 0 || cp == 0xFFFD
                    || (Character.isISOControl(cp) && !Character.isWhitespace(cp))) {
                continue;
            }
            if (Character.isWhitespace(cp)) {
                flush(cur, words);
            } else if (isPunctuation(cp) || isCjk(cp)) {
                flush(cur, words);
                words.add(new String(Character.toChars(cp)));
            } else {
                cur.appendCodePoint(cp);
            }
        }
        flush(cur, words);
        return words;
    }

    List<Integer> wordPiece(String word) {
        if (word.length() > MAX_CHARS_PER_WORD) {
            return List.of(unk);
        }
        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer found = null;
            while (start < end) {
                String sub = (start > 0 ? "##" : "") + word.substring(start, end);
                found = vocab.get(sub);
                if (found != null) {
                    break;
                }
                end--;
            }
            if (found == null) {
                return List.of(unk);
            }
            pieces.add(found);
            start = end;
        }
        return pieces;
    }

    private static void flush(StringBuilder cur, List<String> words) {
        if (!cur.isEmpty()) {
            words.add(cur.toString());
            cur.setLength(0);
        }
    }

    // BERT treats all non-alphanumeric ASCII as punctuation, plus the Unicode P* categories
    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        int t = Character.getType(cp);
        return t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION
                || t == Character.START_PUNCTUATION || t == Character.END_PUNCTUATION
                || t == Character.INITIAL_QUOTE_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION
                || t == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    private int require(String token) {
        Integer id = vocab.get(token);
        if (id == null) {
            throw new IllegalArgumentException("vocab has no " + token);
        }
        return id;
    }
}
