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
import com.tokengate.gateway.domain.chat.EmbeddingResult;
import com.tokengate.gateway.domain.chat.StreamDelta;
import com.tokengate.gateway.domain.chat.TokenUsage;
import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import com.tokengate.gateway.domain.routing.ProviderId;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions and Embeddings. The mock provider speaks the same protocol, so it uses this adapter
 * too, without an API key.
 */
public class OpenAiCompatibleProvider extends HttpChatProvider {

    private final String apiKey;
    private final boolean embeddings;

    public OpenAiCompatibleProvider(ProviderId id, URI baseUrl, String apiKey, boolean embeddings, HttpClient http,
                                    ObjectMapper json, Duration streamIdleTimeout) {
        super(id, baseUrl, http, json, streamIdleTimeout);
        this.apiKey = apiKey;
        this.embeddings = embeddings;
    }

    @Override
    protected Map<String, String> authHeaders() {
        return apiKey == null || apiKey.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
    }

    @Override
    public ChatCompletion complete(ProviderCall call) {
        JsonNode body = postJson(resolve("chat/completions"), body(call, false), call.timeout(), call.requestId());
        JsonNode choice = body.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new ProviderFailure(FailureKind.SERVER_ERROR, id + " returned no choices");
        }
        return new ChatCompletion(
                choice.path("message").path("content").asText(""),
                text(choice, "finish_reason"),
                usage(body.path("usage")),
                body.path("model").asText(call.nativeModel()));
    }

    @Override
    public ChunkStream openStream(ProviderCall call) {
        SseReader reader = openSse(resolve("chat/completions"), body(call, true), call.timeout(), call.requestId());
        return chunkStream(reader, event -> {
            if ("[DONE]".equals(event.data())) {
                return Step.DONE;
            }
            JsonNode chunk = json.readTree(event.data());
            if (chunk.has("error")) {
                throw new ProviderFailure(FailureKind.MIDSTREAM,
                        id + " reported an error mid-stream: " + chunk.path("error").path("message").asText());
            }
            JsonNode choice = chunk.path("choices").path(0);
            String content = choice.path("delta").path("content").asText(null);
            String finish = text(choice, "finish_reason");
            TokenUsage usage = chunk.hasNonNull("usage") ? usage(chunk.get("usage")) : null;
            if (content == null && finish == null && usage == null) {
                return Step.SKIP;
            }
            return Step.emit(new StreamDelta(content, finish, usage));
        }, false);
    }

    @Override
    public boolean supportsEmbeddings() {
        return embeddings;
    }

    @Override
    public EmbeddingResult embed(String nativeModel, List<String> input, Duration timeout) {
        ObjectNode req = json.createObjectNode().put("model", nativeModel);
        ArrayNode in = req.putArray("input");
        input.forEach(in::add);
        JsonNode body = postJson(resolve("embeddings"), req, timeout, null);
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : body.path("data")) {
            JsonNode e = item.path("embedding");
            float[] v = new float[e.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) e.get(i).asDouble();
            }
            vectors.add(v);
        }
        long prompt = body.path("usage").path("prompt_tokens").asLong(0);
        return new EmbeddingResult(vectors, new TokenUsage(prompt, 0), body.path("model").asText(nativeModel));
    }

    private ObjectNode body(ProviderCall call, boolean stream) {
        ChatRequest r = call.request();
        ObjectNode b = json.createObjectNode().put("model", call.nativeModel());
        ArrayNode messages = b.putArray("messages");
        for (ChatMessage m : r.messages()) {
            messages.addObject().put("role", m.role().wireName()).put("content", m.content());
        }
        if (r.temperature() != null) {
            b.put("temperature", r.temperature());
        }
        if (r.topP() != null) {
            b.put("top_p", r.topP());
        }
        if (r.maxTokens() != null) {
            b.put("max_tokens", r.maxTokens());
        }
        if (!r.stop().isEmpty()) {
            ArrayNode stop = b.putArray("stop");
            r.stop().forEach(stop::add);
        }
        if (r.user() != null) {
            b.put("user", r.user());
        }
        if (stream) {
            b.put("stream", true);
            b.putObject("stream_options").put("include_usage", true);
        }
        return b;
    }

    private static TokenUsage usage(JsonNode u) {
        if (u == null || u.isMissingNode() || u.isNull()) {
            return null;
        }
        return new TokenUsage(u.path("prompt_tokens").asLong(0), u.path("completion_tokens").asLong(0));
    }
}
