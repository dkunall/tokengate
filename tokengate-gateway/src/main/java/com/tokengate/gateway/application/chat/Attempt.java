package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderId;

/** One try against one provider. {@code failure} is set only when {@code result} is FAILED. */
public record Attempt(ProviderId provider, long latencyMs, Result result, FailureKind failure, boolean hedge) {

    public enum Result { OK, FAILED, CANCELLED }

    public static Attempt ok(ProviderId p, long ms, boolean hedge) {
        return new Attempt(p, ms, Result.OK, null, hedge);
    }

    public static Attempt failed(ProviderId p, long ms, FailureKind kind, boolean hedge) {
        return new Attempt(p, ms, Result.FAILED, kind, hedge);
    }

    public static Attempt cancelled(ProviderId p, long ms, boolean hedge) {
        return new Attempt(p, ms, Result.CANCELLED, null, hedge);
    }

    public String describe() {
        return provider + ":" + (result == Result.FAILED ? failure.name().toLowerCase() : result.name().toLowerCase())
                + (hedge ? "(hedge)" : "");
    }
}
