package com.tokengate.gateway.adapter.in.web;

import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import org.springframework.http.HttpHeaders;

/** RateLimit-* follow draft-ietf-httpapi-ratelimit-headers; X-TokenGate-* say how the request was served. */
public final class GatewayHeaders {

    public static final String PROVIDER = "X-TokenGate-Provider";
    public static final String CACHE = "X-TokenGate-Cache";
    public static final String SIMILARITY = "X-TokenGate-Similarity";
    public static final String ATTEMPTS = "X-TokenGate-Attempts";
    public static final String PII_MASKED = "X-TokenGate-Pii-Masked";

    private GatewayHeaders() {
    }

    public static void rateLimit(HttpHeaders h, RateLimitDecision d) {
        if (d == null) {
            return;
        }
        h.set("RateLimit-Limit", Long.toString(d.limit()));
        h.set("RateLimit-Remaining", Long.toString(Math.max(0, d.remaining())));
        h.set("RateLimit-Reset", Long.toString(d.resetSeconds()));
    }
}
