package com.tokengate.gateway.domain.tenant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Key format {@code tg_<prefix>_<secret>}: an 8 char lookup prefix and 32 chars (~190 bits) of secret, both
 * base62. The secret is high-entropy random, so a fast keyed hash (HMAC-SHA256 with a server-side pepper) is
 * enough; bcrypt/argon2 exist to slow down guessing low-entropy passwords and would only add latency to every
 * request here.
 */
public final class ApiKeySecret {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern FORMAT = Pattern.compile("tg_([0-9A-Za-z]{8})_([0-9A-Za-z]{32})");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Parsed(String prefix, String secret) {
    }

    public record Issued(String plaintext, String prefix, String secret) {
    }

    private ApiKeySecret() {
    }

    public static Issued generate() {
        String prefix = random(8);
        String secret = random(32);
        return new Issued("tg_" + prefix + "_" + secret, prefix, secret);
    }

    public static Optional<Parsed> parse(String presented) {
        if (presented == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(presented.strip());
        return m.matches() ? Optional.of(new Parsed(m.group(1), m.group(2))) : Optional.empty();
    }

    public static String hash(String secret, byte[] pepper) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String secret, String storedHash, byte[] pepper) {
        return MessageDigest.isEqual(hash(secret, pepper).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static String random(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
