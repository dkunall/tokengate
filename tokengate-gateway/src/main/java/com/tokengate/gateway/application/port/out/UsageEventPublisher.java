package com.tokengate.gateway.application.port.out;

public interface UsageEventPublisher {

    /** Blocks until the broker acknowledged the write. */
    void publish(String key, String payload) throws Exception;
}
