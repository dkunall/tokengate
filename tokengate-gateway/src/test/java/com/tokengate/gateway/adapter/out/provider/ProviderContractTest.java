package com.tokengate.gateway.adapter.out.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tokengate.gateway.application.port.out.ChunkStream;
import com.tokengate.gateway.application.port.out.ProviderCall;
import com.tokengate.gateway.domain.chat.ChatCompletion;
import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.chat.EmbeddingResult;
import com.tokengate.gateway.domain.chat.StreamDelta;
import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import com.tokengate.gateway.domain.routing.ProviderId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the wire format each adapter sends and the responses it understands, using the shapes from the
 * providers' public API docs. A fake key is used; nothing here talks to a real provider.
 */
class ProviderContractTest {

    private static final String FAKE_KEY = "test-key-not-real";
    private static WireMockServer wm;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    private static URI base() {
        return URI.create(wm.baseUrl());
    }

    private static ProviderCall call(boolean stream) {
        ChatRequest r = new ChatRequest("gpt-4o-mini",
                List.of(ChatMessage.system("be brief"), ChatMessage.user("hello")), 0.0, null, 64,
                List.of("END"), stream, null);
        return new ProviderCall(r, "native-model", Duration.ofSeconds(2), "req-1");
    }

    private static List<StreamDelta> drain(ChunkStream s) {
        List<StreamDelta> out = new ArrayList<>();
        try (s) {
            for (StreamDelta d = s.next(); d != null; d = s.next()) {
                out.add(d);
            }
        }
        return out;
    }

    private static String sse(String... events) {
        StringBuilder b = new StringBuilder();
        for (String e : events) {
            b.append(e).append("\n\n");
        }
        return b.toString();
    }

    @Nested
    class OpenAi {
        private final OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(ProviderId.of("openai"),
                base(), FAKE_KEY, true, HTTP, JSON, Duration.ofSeconds(2));

        @Test
        void sendsChatCompletionsShapeAndReadsUsage() {
            wm.stubFor(post("/chat/completions").willReturn(okJson("""
                    {"id":"chatcmpl-1","model":"native-model-0613",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"Hi."},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":12,"completion_tokens":2,"total_tokens":14}}""")));

            ChatCompletion c = provider.complete(call(false));

            assertThat(c.content()).isEqualTo("Hi.");
            assertThat(c.finishReason()).isEqualTo("stop");
            assertThat(c.usage().promptTokens()).isEqualTo(12);
            assertThat(c.usage().completionTokens()).isEqualTo(2);
            wm.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                    .withHeader("Authorization", equalTo("Bearer " + FAKE_KEY))
                    .withHeader("X-Request-Id", equalTo("req-1"))
                    .withRequestBody(equalToJson("""
                            {"model":"native-model","temperature":0.0,"max_tokens":64,"stop":["END"],
                             "messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hello"}]}
                            """)));
        }

        @Test
        void streamsDeltasAndTheTrailingUsageChunk() {
            wm.stubFor(post("/chat/completions").willReturn(aResponse()
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(sse(
                            "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}",
                            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}",
                            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}",
                            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2}}",
                            "data: [DONE]"))));

            List<StreamDelta> deltas = drain(provider.openStream(call(true)));

            assertThat(deltas).extracting(StreamDelta::content).containsExactly("Hel", "lo", null);
            assertThat(deltas.get(2).usage().promptTokens()).isEqualTo(9);
            wm.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                    .withRequestBody(matchingJsonPath("$.stream", equalTo("true")))
                    .withRequestBody(matchingJsonPath("$.stream_options.include_usage", equalTo("true"))));
        }

        @Test
        void streamCutBeforeDoneIsAMidStreamFailure() {
            wm.stubFor(post("/chat/completions").willReturn(aResponse()
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(sse("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}"))));

            ChunkStream s = provider.openStream(call(true));
            assertThat(s.next().content()).isEqualTo("Hel");
            assertThatThrownBy(s::next).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> assertThat(f.kind()).isEqualTo(FailureKind.MIDSTREAM));
        }

        @Test
        void mapsStatusCodesToFailureKinds() {
            wm.stubFor(post("/chat/completions").willReturn(aResponse().withStatus(429)
                    .withHeader("Retry-After", "7").withBody("{\"error\":{\"message\":\"slow down\"}}")));
            assertThatThrownBy(() -> provider.complete(call(false))).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> {
                        assertThat(f.kind()).isEqualTo(FailureKind.RATE_LIMITED);
                        assertThat(f.retryAfter()).contains(Duration.ofSeconds(7));
                    });

            wm.stubFor(post("/chat/completions").willReturn(aResponse().withStatus(401)));
            assertThatThrownBy(() -> provider.complete(call(false))).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> assertThat(f.kind()).isEqualTo(FailureKind.AUTH));

            wm.stubFor(post("/chat/completions").willReturn(aResponse().withStatus(400)));
            assertThatThrownBy(() -> provider.complete(call(false))).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> assertThat(f.kind()).isEqualTo(FailureKind.BAD_REQUEST));
        }

        @Test
        void slowUpstreamIsATimeout() {
            wm.stubFor(post("/chat/completions").willReturn(okJson("{}").withFixedDelay(1500)));
            ProviderCall c = call(false);
            ProviderCall shortCall = new ProviderCall(c.request(), c.nativeModel(), Duration.ofMillis(300), "req-2");
            assertThatThrownBy(() -> provider.complete(shortCall)).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> assertThat(f.kind()).isEqualTo(FailureKind.TIMEOUT));
        }

        @Test
        void embeddings() {
            wm.stubFor(post("/embeddings").willReturn(okJson("""
                    {"data":[{"index":0,"embedding":[0.1,0.2]},{"index":1,"embedding":[0.3,0.4]}],
                     "model":"text-embedding-3-small","usage":{"prompt_tokens":5,"total_tokens":5}}""")));

            EmbeddingResult r = provider.embed("text-embedding-3-small", List.of("a", "b"), Duration.ofSeconds(2));

            assertThat(r.vectors()).hasSize(2);
            assertThat(r.vectors().get(1)[1]).isEqualTo(0.4f);
            assertThat(r.usage().promptTokens()).isEqualTo(5);
        }
    }

    @Nested
    class Anthropic {
        private final AnthropicProvider provider = new AnthropicProvider(ProviderId.of("anthropic"), base(),
                FAKE_KEY, HTTP, JSON, Duration.ofSeconds(2));

        @Test
        void movesSystemPromptOutOfMessagesAndMapsStopReason() {
            wm.stubFor(post("/v1/messages").willReturn(okJson("""
                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-x",
                     "content":[{"type":"text","text":"Hi."}],"stop_reason":"max_tokens",
                     "usage":{"input_tokens":10,"output_tokens":64}}""")));

            ChatCompletion c = provider.complete(call(false));

            assertThat(c.content()).isEqualTo("Hi.");
            assertThat(c.finishReason()).isEqualTo("length");
            assertThat(c.usage().promptTokens()).isEqualTo(10);
            wm.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                    .withHeader("x-api-key", equalTo(FAKE_KEY))
                    .withHeader("anthropic-version", equalTo("2023-06-01"))
                    .withRequestBody(equalToJson("""
                            {"model":"native-model","max_tokens":64,"system":"be brief","temperature":0.0,
                             "stop_sequences":["END"],"messages":[{"role":"user","content":"hello"}]}""")));
        }

        @Test
        void streamsTextDeltasAndCombinesUsageFromStartAndDelta() {
            wm.stubFor(post("/v1/messages").willReturn(aResponse()
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(sse(
                            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":11,\"output_tokens\":1}}}",
                            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                            "event: ping\ndata: {\"type\":\"ping\"}",
                            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
                            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
                            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}",
                            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}",
                            "event: message_stop\ndata: {\"type\":\"message_stop\"}"))));

            List<StreamDelta> deltas = drain(provider.openStream(call(true)));

            assertThat(deltas).extracting(StreamDelta::content).containsExactly("Hel", "lo", null);
            StreamDelta last = deltas.get(2);
            assertThat(last.finishReason()).isEqualTo("stop");
            assertThat(last.usage().promptTokens()).isEqualTo(11);
            assertThat(last.usage().completionTokens()).isEqualTo(3);
        }

        @Test
        void overloadedErrorEventMidStream() {
            wm.stubFor(post("/v1/messages").willReturn(aResponse()
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(sse(
                            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
                            "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"))));

            ChunkStream s = provider.openStream(call(true));
            s.next();
            assertThatThrownBy(s::next).isInstanceOfSatisfying(ProviderFailure.class, f -> {
                assertThat(f.kind()).isEqualTo(FailureKind.MIDSTREAM);
                assertThat(f.getMessage()).contains("overloaded_error");
            });
        }

        @Test
        void overloaded529IsNotRetriedOnTheSameProvider() {
            wm.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(529)
                    .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}")));
            assertThatThrownBy(() -> provider.complete(call(false))).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> {
                        assertThat(f.upstreamStatus()).isEqualTo(529);
                        assertThat(f.kind().failover()).isTrue();
                        assertThat(f.kind().retrySameProvider()).isFalse();
                    });
        }
    }

    @Nested
    class Gemini {
        private final GeminiProvider provider = new GeminiProvider(ProviderId.of("gemini"), base(), FAKE_KEY, HTTP,
                JSON, Duration.ofSeconds(2));

        @Test
        void sendsContentsAndSystemInstructionAndReadsUsageMetadata() {
            wm.stubFor(post("/v1beta/models/native-model:generateContent").willReturn(okJson("""
                    {"candidates":[{"content":{"role":"model","parts":[{"text":"Hi"},{"text":"."}]},
                     "finishReason":"STOP"}],
                     "usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":2,"totalTokenCount":10},
                     "modelVersion":"gemini-x-001"}""")));

            ChatCompletion c = provider.complete(call(false));

            assertThat(c.content()).isEqualTo("Hi.");
            assertThat(c.finishReason()).isEqualTo("stop");
            assertThat(c.usage().promptTokens()).isEqualTo(8);
            assertThat(c.usage().completionTokens()).isEqualTo(2);
            wm.verify(postRequestedFor(urlEqualTo("/v1beta/models/native-model:generateContent"))
                    .withHeader("x-goog-api-key", equalTo(FAKE_KEY))
                    .withRequestBody(equalToJson("""
                            {"systemInstruction":{"parts":[{"text":"be brief"}]},
                             "contents":[{"role":"user","parts":[{"text":"hello"}]}],
                             "generationConfig":{"temperature":0.0,"maxOutputTokens":64,"stopSequences":["END"]}}""")));
        }

        @Test
        void blockedPromptIsABadRequestNotAnOutage() {
            wm.stubFor(post("/v1beta/models/native-model:generateContent").willReturn(okJson("""
                    {"promptFeedback":{"blockReason":"SAFETY"}}""")));
            assertThatThrownBy(() -> provider.complete(call(false))).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> {
                        assertThat(f.kind()).isEqualTo(FailureKind.BAD_REQUEST);
                        assertThat(f.getMessage()).contains("SAFETY");
                    });
        }

        @Test
        void streamsUntilFinishReason() {
            wm.stubFor(post(urlPathEqualTo("/v1beta/models/native-model:streamGenerateContent")).willReturn(aResponse()
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(sse(
                            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}}]}",
                            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"lo\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":8,\"candidatesTokenCount\":2}}"))));

            List<StreamDelta> deltas = drain(provider.openStream(call(true)));

            assertThat(deltas).extracting(StreamDelta::content).containsExactly("Hel", "lo");
            assertThat(deltas.get(1).finishReason()).isEqualTo("stop");
            assertThat(deltas.get(1).usage().completionTokens()).isEqualTo(2);
        }

        @Test
        void streamEndingWithoutFinishReasonIsMidStream() {
            wm.stubFor(post(urlPathEqualTo("/v1beta/models/native-model:streamGenerateContent")).willReturn(aResponse()
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(sse("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hel\"}]}}]}"))));

            ChunkStream s = provider.openStream(call(true));
            s.next();
            assertThatThrownBy(s::next).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> assertThat(f.kind()).isEqualTo(FailureKind.MIDSTREAM));
        }

        @Test
        void batchEmbeddings() {
            wm.stubFor(post("/v1beta/models/text-embedding-004:batchEmbedContents").willReturn(okJson("""
                    {"embeddings":[{"values":[0.5,0.25]},{"values":[0.1,0.9]}]}""")));
            EmbeddingResult r = provider.embed("text-embedding-004", List.of("a", "b"), Duration.ofSeconds(2));
            assertThat(r.vectors()).hasSize(2);
            assertThat(r.vectors().get(0)[0]).isEqualTo(0.5f);
            wm.verify(postRequestedFor(urlEqualTo("/v1beta/models/text-embedding-004:batchEmbedContents"))
                    .withRequestBody(matchingJsonPath("$.requests[1].model", equalTo("models/text-embedding-004"))));
        }
    }
}
