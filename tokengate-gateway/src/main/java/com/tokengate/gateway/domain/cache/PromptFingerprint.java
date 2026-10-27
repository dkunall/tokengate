package com.tokengate.gateway.domain.cache;

import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.ChatRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cache keys for a chat request.
 *
 * <ul>
 *   <li>{@code exactKey}: tenant, model, every generation parameter and every message after normalisation
 *       (Unicode NFC, trimmed, runs of whitespace collapsed). Case is kept: "Paris" and "paris" can deserve
 *       different answers.</li>
 *   <li>{@code contextKey}: the same minus the last user message. The semantic cache only compares the last
 *       user message, and only against entries with the same context, so a near-duplicate question inside a
 *       different conversation is not a hit.</li>
 * </ul>
 */
public final class PromptFingerprint {

    private static final Pattern WS = Pattern.compile("\\s+");

    private PromptFingerprint() {
    }

    public static String normalise(String text) {
        return WS.matcher(Normalizer.normalize(text, Normalizer.Form.NFC).strip()).replaceAll(" ");
    }

    public static String exactKey(String tenantId, ChatRequest r) {
        StringBuilder sb = params(tenantId, r);
        r.messages().forEach(m -> append(sb, m));
        return sha256(sb.toString());
    }

    public static String contextKey(String tenantId, ChatRequest r) {
        StringBuilder sb = params(tenantId, r);
        List<ChatMessage> messages = r.messages();
        ChatMessage last = r.lastUserMessage();
        int lastIdx = messages.lastIndexOf(last);
        for (int i = 0; i < messages.size(); i++) {
            if (i != lastIdx) {
                append(sb, messages.get(i));
            }
        }
        return sha256(sb.toString());
    }

    public static String semanticText(ChatRequest r) {
        return normalise(r.lastUserMessage().content());
    }

    private static StringBuilder params(String tenantId, ChatRequest r) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(tenantId).append('\u0000').append(r.model()).append('\u0000')
                .append(r.temperature()).append('\u0000').append(r.topP()).append('\u0000')
                .append(r.maxTokens()).append('\u0000').append(String.join("\u0001", r.stop())).append('\u0000');
        return sb;
    }

    private static void append(StringBuilder sb, ChatMessage m) {
        // length prefix so ["ab","c"] and ["a","bc"] can't collide
        String content = normalise(m.content());
        sb.append(m.role().wireName()).append(':').append(content.length()).append(':').append(content).append('\u0000');
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
