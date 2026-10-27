package com.tokengate.gateway.application.port.out;

import com.tokengate.common.UsageEvent;

public interface UsageRecorder {

    /** Writes the request log row and the outbox row in one transaction, so an event exists iff the log does. */
    void record(RequestLogEntry entry, UsageEvent event);
}
