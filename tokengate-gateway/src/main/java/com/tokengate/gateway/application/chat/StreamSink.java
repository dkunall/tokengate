package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.domain.chat.TokenUsage;

import java.io.IOException;

/** Where a stream is written. An IOException from any method means the client is gone. */
public interface StreamSink {

    void delta(String content) throws IOException;

    void finish(String finishReason, TokenUsage usage) throws IOException;

    /** Sent instead of {@link #finish} when the stream cannot be completed; the client sees no [DONE]. */
    void error(String type, String message) throws IOException;
}
