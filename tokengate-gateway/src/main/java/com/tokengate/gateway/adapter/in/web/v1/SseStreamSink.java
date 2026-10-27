package com.tokengate.gateway.adapter.in.web.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.application.chat.StreamSink;
import com.tokengate.gateway.domain.chat.TokenUsage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Writes OpenAI-style chunks and flushes each one. The flush is what gives backpressure: if the client reads
 * slowly the socket buffer fills, flush blocks, and the session stops pulling from the provider.
 */
final class SseStreamSink implements StreamSink {

    private final OutputStream out;
    private final ObjectMapper json;
    private final String id;
    private final long created;
    private final String model;
    private boolean first = true;

    SseStreamSink(OutputStream out, ObjectMapper json, String id, long created, String model) {
        this.out = out;
        this.json = json;
        this.id = id;
        this.created = created;
        this.model = model;
    }

    @Override
    public void delta(String content) throws IOException {
        ChatCompletionResponse.Delta delta = new ChatCompletionResponse.Delta(first ? "assistant" : null, content);
        first = false;
        write(ChatCompletionResponse.chunk(id, created, model, delta, null, null));
    }

    @Override
    public void finish(String finishReason, TokenUsage usage) throws IOException {
        write(ChatCompletionResponse.chunk(id, created, model, new ChatCompletionResponse.Delta(null, null),
                finishReason == null ? "stop" : finishReason, usage));
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void error(String type, String message) throws IOException {
        write(Map.of("error", Map.of("type", type, "message", message)));
    }

    private void write(Object payload) throws IOException {
        out.write(("data: " + json.writeValueAsString(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
