package com.tokengate.gateway.adapter.out.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokengate.gateway.application.port.out.ChunkStream;
import com.tokengate.gateway.application.port.out.ProviderCall;
import com.tokengate.gateway.domain.chat.ChatCompletion;
import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.chat.Role;
import com.tokengate.gateway.domain.chat.StreamDelta;
import com.tokengate.gateway.domain.chat.TokenUsage;
import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import com.tokengate.gateway.domain.routing.ProviderId;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** Anthropic Messages API. System messages move to the top-level "system" field; max_tokens is mandatory there. */
public class AnthropicProvider extends HttpChatProvider {

    static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final String apiKey;

    public AnthropicProvider(ProviderId id, URI baseUrl, String apiKey, HttpClient http, ObjectMapper json,
                             Duration streamIdleTimeout) {
        super(id, baseUrl, http, json, streamIdleTimeout);
        this.apiKey = apiKey;
    }

    @Override
    protected Map<String, String> authHeaders() {
        return Map.of("x-api-key", apiKey, "anthropic-version", API_VERSION);
    }

    @Override
    public ChatCompletion complete(ProviderCall call) {
        JsonNode body = postJson(resolve("v1/messages"), body(call, false), call.timeout(), call.requestId());
        StringBuilder text = new StringBuilder();
        for (JsonNode block : body.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        JsonNode u = body.path("usage");
        return new ChatCompletion(text.toString(), finishReason(text(body, "stop_reason")),
                new TokenUsage(u.path("input_tokens").asLong(0), u.path("output_tokens").asLong(0)),
                body.path("model").asText(call.nativeModel()));
    }

    @Override
    public ChunkStream openStream(ProviderCall call) {
        SseReader reader = openSse(resolve("v1/messages"), body(call, true), call.timeout(), call.requestId());
        long[] inputTokens = {0};
        return chunkStream(reader, event -> {
            JsonNode e = json.readTree(event.data());
            String type = e.path("type").asText(event.name() == null ? "" : event.name());
            return switch (type) {
                case "message_start" -> {
                    inputTokens[0] = e.path("message").path("usage").path("input_tokens").asLong(0);
                    yield Step.SKIP;
                }
                case "content_block_delta" -> "text_delta".equals(e.path("delta").path("type").asText())
                        ? Step.emit(StreamDelta.text(e.path("delta").path("text").asText()))
                        : Step.SKIP;
                case "message_delta" -> Step.emit(new StreamDelta(null,
                        finishReason(text(e.path("delta"), "stop_reason")),
                        new TokenUsage(inputTokens[0], e.path("usage").path("output_tokens").asLong(0))));
                case "message_stop" -> Step.DONE;
                case "error" -> throw new ProviderFailure(FailureKind.MIDSTREAM,
                        id + " reported an error mid-stream: " + e.path("error").path("type").asText());
                default -> Step.SKIP;
            };
        }, false);
    }

    private ObjectNode body(ProviderCall call, boolean stream) {
        ChatRequest r = call.request();
        ObjectNode b = json.createObjectNode()
                .put("model", call.nativeModel())
                .put("max_tokens", r.maxTokens() == null ? DEFAULT_MAX_TOKENS : r.maxTokens());
        String system = r.messages().stream().filter(m -> m.role() == Role.SYSTEM).map(ChatMessage::content)
                .collect(Collectors.joining("\n\n"));
        if (!system.isEmpty()) {
            b.put("system", system);
        }
        ArrayNode messages = b.putArray("messages");
        for (ChatMessage m : r.messages()) {
            if (m.role() != Role.SYSTEM) {
                messages.addObject().put("role", m.role().wireName()).put("content", m.content());
            }
        }
        if (r.temperature() != null) {
            // Anthropic's range is 0..1, OpenAI's is 0..2
            b.put("temperature", Math.min(1.0, r.temperature()));
        }
        if (r.topP() != null) {
            b.put("top_p", r.topP());
        }
        if (!r.stop().isEmpty()) {
            ArrayNode stop = b.putArray("stop_sequences");
            r.stop().forEach(stop::add);
        }
        if (stream) {
            b.put("stream", true);
        }
        return b;
    }

    static String finishReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "max_tokens" -> "length";
            case "end_turn", "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            default -> stopReason;
        };
    }
}
