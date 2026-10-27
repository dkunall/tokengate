package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.chat.StreamDelta;

/**
 * Pull-based view of an upstream SSE stream. The caller pulls at the pace it can write to its own client, so
 * a slow client slows the upstream read instead of filling a buffer.
 */
public interface ChunkStream extends AutoCloseable {

    /** @return the next delta, or null once the upstream finished cleanly */
    StreamDelta next();

    /** Aborts the upstream connection if it is still open. Safe to call more than once. */
    @Override
    void close();
}
