package com.tokengate.gateway.domain.pii;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Masks the spans the scanner finds. In TOKENIZE mode the token is an HMAC of (tenant, type, canonical value)
 * under a gateway secret: the same email always becomes the same token for a tenant, which keeps the exact
 * and semantic caches useful, and tokens differ between tenants. The cache only ever stores masked text.
 */
public final class PiiMasker {

    private final byte[] secret;

    public PiiMasker(byte[] secret) {
        if (secret.length < 16) {
            throw new IllegalArgumentException("PII tokenisation secret must be at least 16 bytes");
        }
        this.secret = secret.clone();
    }

    public String mask(String text, PiiScanner scanner, PiiMode mode, String tenantId, MaskingSession session) {
        if (mode == PiiMode.OFF || text.isEmpty()) {
            return text;
        }
        List<PiiMatch> matches = scanner.scan(text);
        if (matches.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int cursor = 0;
        for (PiiMatch m : matches) {
            sb.append(text, cursor, m.start());
            if (mode == PiiMode.REDACT) {
                sb.append('[').append(m.type().label()).append(']');
                session.count(m.type());
            } else {
                String token = token(tenantId, m);
                sb.append(token);
                session.remember(token, m.value(), m.type());
            }
            cursor = m.end();
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    String token(String tenantId, PiiMatch m) {
        String canonical = switch (m.type()) {
            case EMAIL, PAN, CUSTOM_TERM -> m.value().toLowerCase(Locale.ROOT);
            case PHONE_IN -> {
                String d = PiiScanner.digits(m.value());
                yield d.substring(d.length() - 10);
            }
            case AADHAAR, CARD -> PiiScanner.digits(m.value());
        };
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal((tenantId + '\u0000' + m.type() + '\u0000' + canonical).getBytes(StandardCharsets.UTF_8));
            return "<" + m.type().label() + "_" + HexFormat.of().formatHex(h, 0, 4) + ">";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
