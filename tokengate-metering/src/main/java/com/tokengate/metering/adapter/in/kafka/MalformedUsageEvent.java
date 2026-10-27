package com.tokengate.metering.adapter.in.kafka;

/** A record that can never be processed. The error handler logs and skips these instead of retrying. */
public final class MalformedUsageEvent extends RuntimeException {

    public MalformedUsageEvent(String message, Throwable cause) {
        super(message, cause);
    }
}
