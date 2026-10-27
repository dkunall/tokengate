package com.tokengate.gateway.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole gateway over HTTP, with real Postgres and Redis and WireMock standing in for both mock
 * providers. Kafka is left unreachable on purpose: usage stays in the outbox and requests are not affected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayEndToEndTest {

    private static final String JWT_SECRET = "test-only-jwt-secret-0123456789abcdef";
    private static final String ALPHA = "/alpha/v1/chat/completions";
    private static final String BETA = "/beta/v1/chat/completions";

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    private static final WireMockServer UPSTREAM = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PG.start();
        REDIS.start();
        UPSTREAM.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        r.add("tokengate.usage.outbox-interval", () -> "PT1H");
        r.add("TOKENGATE_MOCK_URL", UPSTREAM::baseUrl);
        r.add("tokengate.security.jwt.hmac-secret", () -> JWT_SECRET);
        r.add("tokengate.routing.backoff-base", () -> "5ms");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop();
    }

    @LocalServerPort
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetUpstream() {
        UPSTREAM.resetAll();
    }

    @Test
    void masksPiiOnTheWayOutAndServesTheRepeatFromCache() throws Exception {
        String key = tenantWithKey("{}");
        UPSTREAM.stubFor(post(ALPHA).willReturn(okJson(completion("Sure, I will write to <EMAIL_x>."))));

        HttpResponse<String> first = chat(key, "Email me at kunal.test@example.com about the invoice", false);
        HttpResponse<String> second = chat(key, "Email me at kunal.test@example.com about the invoice", false);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("X-TokenGate-Provider")).contains("mock-alpha");
        assertThat(first.headers().firstValue("X-TokenGate-Pii-Masked")).contains("1");
        assertThat(first.headers().firstValue("X-TokenGate-Cache")).contains("miss");
        assertThat(second.headers().firstValue("X-TokenGate-Cache")).hasValueSatisfying(v -> assertThat(v).endsWith("hit"));
        UPSTREAM.verify(1, postRequestedFor(urlEqualTo(ALPHA)));
        UPSTREAM.verify(0, postRequestedFor(urlEqualTo(ALPHA)).withRequestBody(containing("kunal.test@example.com")));
        assertThat(json.readTree(first.body()).path("usage").path("total_tokens").asLong()).isEqualTo(19);
    }

    @Test
    void failsOverToTheNextProviderWhenTheFirstReturns500() throws Exception {
        String key = tenantWithKey("{\"cache\":{\"enabled\":false}}");
        UPSTREAM.stubFor(post(ALPHA).willReturn(aResponse().withStatus(500).withBody("{\"error\":\"boom\"}")));
        UPSTREAM.stubFor(post(BETA).willReturn(okJson(completion("from beta"))));

        HttpResponse<String> r = chat(key, "what is a circuit breaker", false);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("X-TokenGate-Provider")).contains("mock-beta");
        assertThat(json.readTree(r.body()).path("choices").path(0).path("message").path("content").asText())
                .isEqualTo("from beta");
        // a 500 may have run the model, so it is not retried on alpha
        UPSTREAM.verify(1, postRequestedFor(urlEqualTo(ALPHA)));
    }

    @Test
    void allProvidersDownIsA502Problem() throws Exception {
        String key = tenantWithKey("{\"cache\":{\"enabled\":false}}");
        UPSTREAM.stubFor(post(ALPHA).willReturn(aResponse().withStatus(500)));
        UPSTREAM.stubFor(post(BETA).willReturn(aResponse().withStatus(500)));

        HttpResponse<String> r = chat(key, "anyone there", false);

        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/problem+json"));
    }

    @Test
    void secondRequestOverTheRateLimitGets429WithHeaders() throws Exception {
        String key = tenantWithKey("{\"requests_per_minute\":1,\"burst\":1}");
        UPSTREAM.stubFor(post(ALPHA).willReturn(okJson(completion("ok"))));

        HttpResponse<String> ok = chat(key, "one", false);
        HttpResponse<String> limited = chat(key, "two", false);

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("RateLimit-Limit")).contains("1");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
        assertThat(limited.headers().firstValue("RateLimit-Remaining")).contains("0");
    }

    @Test
    void exhaustedDailyBudgetIs429BeforeAnyProviderCall() throws Exception {
        String key = tenantWithKey("{\"daily_tokens\":50}");
        UPSTREAM.stubFor(post(ALPHA).willReturn(okJson(completion("ok"))));

        HttpResponse<String> r = chat(key, "this needs more tokens than the budget allows", false);

        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.body()).contains("budget");
        UPSTREAM.verify(0, postRequestedFor(urlEqualTo(ALPHA)));
    }

    @Test
    void streamsSseChunksAndEndsWithDone() throws Exception {
        String key = tenantWithKey("{}");
        UPSTREAM.stubFor(post(ALPHA).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"choices":[{"index":0,"delta":{"content":"Hel"}}]}

                        data: {"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"prompt_tokens":9,"completion_tokens":2}}

                        data: [DONE]

                        """)));

        HttpResponse<InputStream> r = http.send(chatRequest(key, "stream please", true),
                HttpResponse.BodyHandlers.ofInputStream());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("text/event-stream"));
        List<String> data = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.startsWith("data:")) {
                    data.add(line.substring(5).trim());
                }
            }
        }
        assertThat(data.getLast()).isEqualTo("[DONE]");
        StringBuilder text = new StringBuilder();
        JsonNode usage = null;
        for (String d : data.subList(0, data.size() - 1)) {
            JsonNode chunk = json.readTree(d);
            text.append(chunk.path("choices").path(0).path("delta").path("content").asText(""));
            if (chunk.hasNonNull("usage")) {
                usage = chunk.get("usage");
            }
        }
        assertThat(text).hasToString("Hello");
        assertThat(usage).isNotNull();
        assertThat(usage.path("completion_tokens").asLong()).isEqualTo(2);
    }

    @Test
    void v1RejectsMissingKeyAndAdminRejectsViewerWrites() throws Exception {
        HttpResponse<String> noKey = http.send(HttpRequest.newBuilder(uri("/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noKey.statusCode()).isEqualTo(401);

        HttpResponse<String> viewer = admin("POST", "/admin/tenants", "{\"id\":\"viewer-try\"}", "VIEWER");
        assertThat(viewer.statusCode()).isEqualTo(403);
    }

    private String tenantWithKey(String overrides) throws Exception {
        String id = "t-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode body = json.readTree(overrides);
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("id", id).put("name", "IT " + id).put("routing", "priority");
        HttpResponse<String> created = admin("POST", "/admin/tenants", body.toString(), "ADMIN");
        assertThat(created.statusCode()).as(created.body()).isBetween(200, 201);
        HttpResponse<String> issued = admin("POST", "/admin/tenants/" + id + "/keys", "{\"name\":\"it\"}", "ADMIN");
        assertThat(issued.statusCode()).as(issued.body()).isBetween(200, 201);
        return json.readTree(issued.body()).path("key").asText();
    }

    private HttpResponse<String> chat(String key, String prompt, boolean stream) throws Exception {
        return http.send(chatRequest(key, prompt, stream), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest chatRequest(String key, String prompt, boolean stream) {
        String body = json.createObjectNode()
                .put("model", "gpt-4o-mini")
                .put("temperature", 0)
                .put("stream", stream)
                .set("messages", json.createArrayNode().add(json.createObjectNode()
                        .put("role", "user").put("content", prompt)))
                .toString();
        return HttpRequest.newBuilder(uri("/v1/chat/completions"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> admin(String method, String path, String body, String role) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + jwt(role))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String completion(String content) {
        return """
                {"id":"c1","model":"mock","choices":[{"index":0,"message":{"role":"assistant","content":"%s"},
                 "finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":7}}""".formatted(content);
    }

    private static String jwt(String role) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        long now = Instant.now().getEpochSecond();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"iss\":\"tokengate-dev\",\"sub\":\"it\",\"roles\":[\"" + role
                + "\"],\"iat\":" + now + ",\"exp\":" + (now + 300) + "}").getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = b64.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + sig;
    }
}
