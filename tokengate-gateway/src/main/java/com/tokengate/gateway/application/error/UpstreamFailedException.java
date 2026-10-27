package com.tokengate.gateway.application.error;

import com.tokengate.gateway.application.chat.Attempt;
import com.tokengate.gateway.domain.routing.ProviderFailure;

import java.util.List;

public final class UpstreamFailedException extends GatewayException {

    private final List<Attempt> attempts;
    private final ProviderFailure last;

    public UpstreamFailedException(List<Attempt> attempts, ProviderFailure last) {
        super("all providers failed; last: " + last.getMessage());
        this.attempts = List.copyOf(attempts);
        this.last = last;
    }

    public List<Attempt> attempts() {
        return attempts;
    }

    public ProviderFailure last() {
        return last;
    }
}
