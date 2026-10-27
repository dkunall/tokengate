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
        void errorBodyMessageIsUsedWhenThereIsOneAndTheRawBodyWhenNot() {
            wm.stubFor(post("/chat/completions").willReturn(aResponse().withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":{\"message\":\"bad\\n  temperature\",\"type\":\"invalid_request_error\"}}")));
            assertThatThrownBy(() -> provider.complete(call(false))).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> assertThat(f.getMessage()).isEqualTo("openai returned 400: bad temperature"));

            wm.stubFor(post("/chat/completions").willReturn(aResponse().withStatus(502).withBody("<html>bad gateway</html>")));
            assertThatThrownBy(() -> provider.complete(call(false))).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> {
                        assertThat(f.kind()).isEqualTo(FailureKind.SERVER_ERROR);
                        assertThat(f.getMessage()).contains("502").contains("bad gateway");
                    });
        }

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

        // Fixtures below follow what generativelanguage.googleapis.com returned for gemini-3.5-flash-lite on
        // 2026-10-27 (see docs/real-providers.md); only the thought signatures and response ids are shortened.
        @Test
        void sendsContentsAndSystemInstructionAndReadsUsageMetadata() {
            wm.stubFor(post("/v1beta/models/native-model:generateContent").willReturn(okJson("""
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [ { "text": "Paris", "thoughtSignature": "EmAKXgFpFH0T" } ],
                            "role": "model"
                          },
                          "finishReason": "STOP",
                          "index": 0
                        }
                      ],
                      "usageMetadata": {
                        "promptTokenCount": 14,
                        "candidatesTokenCount": 1,
                        "totalTokenCount": 15,
                        "promptTokensDetails": [ { "modality": "TEXT", "tokenCount": 14 } ],
                        "serviceTier": "standard"
                      },
                      "modelVersion": "gemini-3.5-flash-lite",
                      "responseId": "uuzgatu6HvOC"
                    }""")));

            ChatCompletion c = provider.complete(call(false));

            assertThat(c.content()).isEqualTo("Paris");
            assertThat(c.finishReason()).isEqualTo("stop");
            assertThat(c.usage().promptTokens()).isEqualTo(14);
            assertThat(c.usage().completionTokens()).isEqualTo(1);
            assertThat(c.providerModel()).isEqualTo("gemini-3.5-flash-lite");
            wm.verify(postRequestedFor(urlEqualTo("/v1beta/models/native-model:generateContent"))
                    .withHeader("x-goog-api-key", equalTo(FAKE_KEY))
                    .withRequestBody(equalToJson("""
                            {"systemInstruction":{"parts":[{"text":"be brief"}]},
                             "contents":[{"role":"user","parts":[{"text":"hello"}]}],
                             "generationConfig":{"temperature":0.0,"maxOutputTokens":64,"stopSequences":["END"]}}""")));
        }

        @Test
        void thinkingTokensAreBilledAsCompletionAndThoughtPartsAreNotContent() {
            wm.stubFor(post("/v1beta/models/native-model:generateContent").willReturn(okJson("""
                    {"candidates":[{"content":{"role":"model","parts":[
                        {"text":"let me think","thought":true},{"text":"42"}]},"finishReason":"STOP"}],
                     "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2,"thoughtsTokenCount":30,
                                      "totalTokenCount":42}}""")));

            ChatCompletion c = provider.complete(call(false));

            assertThat(c.content()).isEqualTo("42");
            assertThat(c.usage().completionTokens()).isEqualTo(32);
        }

        @Test
        void maxTokensCutOffWithNoCandidateTokensIsLengthWithZeroCompletion() {
            // what a 1-token limit really returns: empty text and no candidatesTokenCount at all
            wm.stubFor(post("/v1beta/models/native-model:generateContent").willReturn(okJson("""
                    {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"MAX_TOKENS",
                      "index":0}],
                     "usageMetadata":{"promptTokenCount":9,"totalTokenCount":9,
                      "promptTokensDetails":[{"modality":"TEXT","tokenCount":9}],"serviceTier":"standard"},
                     "modelVersion":"gemini-3.5-flash-lite","responseId":"ae3gatS4"}""")));

            ChatCompletion c = provider.complete(call(false));

            assertThat(c.content()).isEmpty();
            assertThat(c.finishReason()).isEqualTo("length");
            assertThat(c.usage().promptTokens()).isEqualTo(9);
            assertThat(c.usage().completionTokens()).isZero();
        }

        @Test
        void retiredOrUnknownModelFailsOverWithGooglesMessageOnOneLine() {
            wm.stubFor(post("/v1beta/models/native-model:generateContent").willReturn(aResponse().withStatus(404)
                    .withHeader("Content-Type", "application/json").withBody("""
                    {
                      "error": {
                        "code": 404,
                        "message": "This model models/native-model is no longer available to new users.",
                        "status": "NOT_FOUND"
                      }
                    }
                    """)));

            assertThatThrownBy(() -> provider.complete(call(false))).isInstanceOfSatisfying(ProviderFailure.class,
                    f -> {
                        assertThat(f.kind()).isEqualTo(FailureKind.NOT_FOUND);
                        assertThat(f.kind().failover()).isTrue();
                        assertThat(f.getMessage()).isEqualTo(
                                "gemini returned 404: This model models/native-model is no longer available to new users.");
                    });
        }

        @Test
        void wireLogHasTheMaskedBodyButNeverTheKey() {
            ch.qos.logback.classic.Logger wire =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("tokengate.provider.wire");
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> seen =
                    new ch.qos.logback.core.read.ListAppender<>();
            seen.start();
            ch.qos.logback.classic.Level before = wire.getLevel();
            wire.setLevel(ch.qos.logback.classic.Level.TRACE);
            wire.addAppender(seen);
            try {
                wm.stubFor(post(urlPathEqualTo("/v1beta/models/native-model:streamGenerateContent"))
                        .willReturn(aResponse().withHeader("Content-Type", "text/event-stream").withBody(sse(
                                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"hi\"}],\"role\": \"model\"},\"finishReason\": \"STOP\",\"index\": 0}]}"))));
                drain(provider.openStream(call(true)));
            } finally {
                wire.detachAppender(seen);
                wire.setLevel(before);
            }

            List<String> lines = seen.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(lines).anySatisfy(l -> assertThat(l)
                    .startsWith("gemini -> POST /v1beta/models/native-model:streamGenerateContent {")
                    .contains("\"text\":\"hello\""));
            assertThat(lines).anySatisfy(l -> assertThat(l).startsWith("gemini <- event"));
            assertThat(lines).noneSatisfy(l -> assertThat(l).containsIgnoringCase(FAKE_KEY));
            assertThat(lines).noneSatisfy(l -> assertThat(l).containsIgnoringCase("x-goog-api-key"));
            assertThat(lines).noneSatisfy(l -> assertThat(l).contains("alt=sse"));
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
            // Gemini repeats cumulative usageMetadata on every chunk and closes with an empty text part that
            // carries the finishReason (and a thought signature)
            wm.stubFor(post(urlPathEqualTo("/v1beta/models/native-model:streamGenerateContent")).willReturn(aResponse()
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody(sse(
                            "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"1,\"}],\"role\": \"model\"},\"index\": 0}],\"usageMetadata\": {\"promptTokenCount\": 13,\"candidatesTokenCount\": 2,\"totalTokenCount\": 15,\"promptTokensDetails\": [{\"modality\": \"TEXT\",\"tokenCount\": 13}],\"serviceTier\": \"standard\"},\"modelVersion\": \"gemini-3.5-flash-lite\",\"responseId\": \"F-3gaovz\"}",
                            "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \" 2, 3, 4, 5\"}],\"role\": \"model\"},\"index\": 0}],\"usageMetadata\": {\"promptTokenCount\": 13,\"candidatesTokenCount\": 13,\"totalTokenCount\": 26,\"promptTokensDetails\": [{\"modality\": \"TEXT\",\"tokenCount\": 13}],\"serviceTier\": \"standard\"},\"modelVersion\": \"gemini-3.5-flash-lite\",\"responseId\": \"F-3gaovz\"}",
                            "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"\",\"thoughtSignature\": \"EmAKXgFpFH0T\"}],\"role\": \"model\"},\"finishReason\": \"STOP\",\"index\": 0}],\"usageMetadata\": {\"promptTokenCount\": 13,\"candidatesTokenCount\": 13,\"totalTokenCount\": 26,\"promptTokensDetails\": [{\"modality\": \"TEXT\",\"tokenCount\": 13}],\"serviceTier\": \"standard\"},\"modelVersion\": \"gemini-3.5-flash-lite\",\"responseId\": \"F-3gaovz\"}"))));

            List<StreamDelta> deltas = drain(provider.openStream(call(true)));

            assertThat(deltas).extracting(StreamDelta::content).containsExactly("1,", " 2, 3, 4, 5", null);
            StreamDelta last = deltas.get(deltas.size() - 1);
            assertThat(last.finishReason()).isEqualTo("stop");
            assertThat(last.usage().promptTokens()).isEqualTo(13);
            assertThat(last.usage().completionTokens()).isEqualTo(13);
            wm.verify(postRequestedFor(urlEqualTo("/v1beta/models/native-model:streamGenerateContent?alt=sse")));
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
            // gemini-embedding-2 answers batchEmbedContents with 3072 values per input and no usage block
            wm.stubFor(post("/v1beta/models/gemini-embedding-2:batchEmbedContents").willReturn(okJson("""
                    {
                      "embeddings": [
                        { "values": [ -0.007190489, 0.016610544, 0.023724955 ] },
                        { "values": [ 0.1, 0.9, -0.2 ] }
                      ]
                    }""")));
            EmbeddingResult r = provider.embed("gemini-embedding-2", List.of("a", "b"), Duration.ofSeconds(2));
            assertThat(r.vectors()).hasSize(2);
            assertThat(r.vectors().get(0)[0]).isEqualTo(-0.007190489f);
            assertThat(r.usage().total()).isZero();
            wm.verify(postRequestedFor(urlEqualTo("/v1beta/models/gemini-embedding-2:batchEmbedContents"))
                    .withHeader("x-goog-api-key", equalTo(FAKE_KEY))
                    .withRequestBody(matchingJsonPath("$.requests[1].model", equalTo("models/gemini-embedding-2"))));
        }
    }
}
