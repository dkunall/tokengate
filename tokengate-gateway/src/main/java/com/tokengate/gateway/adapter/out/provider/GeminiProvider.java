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
import com.tokengate.gateway.domain.chat.Role;
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
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gemini generateContent / streamGenerateContent (alt=sse). Gemini has no [DONE] marker, so a stream counts as
 * finished cleanly only if a candidate carried a finishReason before the connection closed.
 */
public class GeminiProvider extends HttpChatProvider {

    private final String apiKey;

    public GeminiProvider(ProviderId id, URI baseUrl, String apiKey, HttpClient http, ObjectMapper json,
                          Duration streamIdleTimeout) {
        super(id, baseUrl, http, json, streamIdleTimeout);
        this.apiKey = apiKey;
    }

    @Override
    protected Map<String, String> authHeaders() {
        return Map.of("x-goog-api-key", apiKey);
    }

    @Override
    public ChatCompletion complete(ProviderCall call) {
        JsonNode body = postJson(resolve("v1beta/models/" + call.nativeModel() + ":generateContent"),
                body(call.request()), call.timeout(), call.requestId());
        JsonNode candidate = body.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            String blocked = body.path("promptFeedback").path("blockReason").asText("no candidates");
            throw new ProviderFailure(FailureKind.BAD_REQUEST, 400, id + " returned nothing: " + blocked, null, null);
        }
        return new ChatCompletion(partsText(candidate), finishReason(text(candidate, "finishReason")),
                usage(body.path("usageMetadata")), body.path("modelVersion").asText(call.nativeModel()));
    }

    @Override
    public ChunkStream openStream(ProviderCall call) {
        SseReader reader = openSse(resolve("v1beta/models/" + call.nativeModel() + ":streamGenerateContent?alt=sse"),
                body(call.request()), call.timeout(), call.requestId());
        boolean[] finished = {false};
        ChunkStream inner = chunkStream(reader, event -> {
            if (finished[0]) {
                return Step.SKIP;
            }
            JsonNode chunk = json.readTree(event.data());
            if (chunk.has("error")) {
                throw new ProviderFailure(FailureKind.MIDSTREAM,
                        id + " reported an error mid-stream: " + chunk.path("error").path("status").asText());
            }
            JsonNode candidate = chunk.path("candidates").path(0);
            String content = partsText(candidate);
            String finish = finishReason(text(candidate, "finishReason"));
            TokenUsage usage = chunk.has("usageMetadata") ? usage(chunk.get("usageMetadata")) : null;
            if (finish != null) {
                finished[0] = true;
            }
            if (content.isEmpty() && finish == null && usage == null) {
                return Step.SKIP;
            }
            return Step.emit(new StreamDelta(content.isEmpty() ? null : content, finish, usage));
        }, true);
        return new ChunkStream() {
            @Override
            public StreamDelta next() {
                StreamDelta d = inner.next();
                if (d == null && !finished[0]) {
                    throw new ProviderFailure(FailureKind.MIDSTREAM, id + " closed the stream without a finishReason");
                }
                return d;
            }

            @Override
            public void close() {
                inner.close();
            }
        };
    }

    @Override
    public boolean supportsEmbeddings() {
        return true;
    }

    @Override
    public EmbeddingResult embed(String nativeModel, List<String> input, Duration timeout) {
        ObjectNode req = json.createObjectNode();
        ArrayNode requests = req.putArray("requests");
        for (String text : input) {
            ObjectNode r = requests.addObject().put("model", "models/" + nativeModel);
            r.putObject("content").putArray("parts").addObject().put("text", text);
        }
        JsonNode body = postJson(resolve("v1beta/models/" + nativeModel + ":batchEmbedContents"), req, timeout, null);
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode e : body.path("embeddings")) {
            JsonNode values = e.path("values");
            float[] v = new float[values.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) values.get(i).asDouble();
            }
            vectors.add(v);
        }
        // batchEmbedContents does not report usage; the gateway falls back to its own count
        return new EmbeddingResult(vectors, TokenUsage.ZERO, nativeModel);
    }

    private ObjectNode body(ChatRequest r) {
        ObjectNode b = json.createObjectNode();
        String system = r.messages().stream().filter(m -> m.role() == Role.SYSTEM).map(ChatMessage::content)
                .collect(Collectors.joining("\n\n"));
        if (!system.isEmpty()) {
            b.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        }
        ArrayNode contents = b.putArray("contents");
        for (ChatMessage m : r.messages()) {
            if (m.role() == Role.SYSTEM) {
                continue;
            }
            ObjectNode c = contents.addObject().put("role", m.role() == Role.ASSISTANT ? "model" : "user");
            c.putArray("parts").addObject().put("text", m.content());
        }
        ObjectNode config = json.createObjectNode();
        if (r.temperature() != null) {
            config.put("temperature", r.temperature());
        }
        if (r.topP() != null) {
            config.put("topP", r.topP());
        }
        if (r.maxTokens() != null) {
            config.put("maxOutputTokens", r.maxTokens());
        }
        if (!r.stop().isEmpty()) {
            ArrayNode stop = config.putArray("stopSequences");
            r.stop().forEach(stop::add);
        }
        if (!config.isEmpty()) {
            b.set("generationConfig", config);
        }
        return b;
    }

    private static String partsText(JsonNode candidate) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text")) {
                sb.append(part.get("text").asText());
            }
        }
        return sb.toString();
    }

    private static TokenUsage usage(JsonNode u) {
        if (u == null || u.isMissingNode()) {
            return null;
        }
        return new TokenUsage(u.path("promptTokenCount").asLong(0), u.path("candidatesTokenCount").asLong(0));
    }

    static String finishReason(String reason) {
        if (reason == null || reason.isEmpty() || "FINISH_REASON_UNSPECIFIED".equals(reason)) {
            return null;
        }
        return switch (reason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> "content_filter";
            default -> reason.toLowerCase(Locale.ROOT);
        };
    }
}
