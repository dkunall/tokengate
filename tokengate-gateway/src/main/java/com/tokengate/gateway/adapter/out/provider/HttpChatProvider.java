package com.tokengate.gateway.adapter.out.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.application.port.out.ChatProvider;
import com.tokengate.gateway.application.port.out.ChunkStream;
import com.tokengate.gateway.domain.chat.StreamDelta;
import com.tokengate.gateway.domain.routing.FailureKind;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import com.tokengate.gateway.domain.routing.ProviderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** HTTP plumbing shared by the provider adapters; subclasses only map JSON in and out. */
abstract class HttpChatProvider implements ChatProvider {

    /**
     * Outbound bodies (already PII-masked) and upstream replies, at DEBUG. Only the path and body are logged,
     * never headers, so API keys sent as headers can't end up here.
     */
    protected static final Logger wire = LoggerFactory.getLogger("tokengate.provider.wire");
    private static final int WIRE_LOG_LIMIT = 4000;

    protected final ProviderId id;
    protected final URI baseUrl;
    protected final HttpClient http;
    protected final ObjectMapper json;
    private final Duration streamIdleTimeout;

    protected HttpChatProvider(ProviderId id, URI baseUrl, HttpClient http, ObjectMapper json,
                               Duration streamIdleTimeout) {
        this.id = id;
        this.baseUrl = baseUrl;
        this.http = http;
        this.json = json;
        this.streamIdleTimeout = streamIdleTimeout;
    }

    @Override
    public ProviderId id() {
        return id;
    }

    protected abstract Map<String, String> authHeaders();

    protected URI resolve(String path) {
        String base = baseUrl.toString();
        return URI.create(base.endsWith("/") ? base + path : base + "/" + path);
    }

    protected JsonNode postJson(URI uri, Object body, Duration timeout, String requestId) {
        String payload = write(body);
        HttpRequest request = request(uri, payload, timeout, requestId, "application/json");
        logOut(uri, payload);
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            // the whole exchange, body included, has to fit in the timeout
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ProviderFailure(FailureKind.TIMEOUT, 0, id + " timed out after " + timeout.toMillis() + "ms",
                    null, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderFailure(FailureKind.CONNECT, 0, "interrupted", null, e);
        } catch (ExecutionException e) {
            throw transport(e.getCause());
        }
        if (wire.isDebugEnabled()) {
            wire.debug("{} <- {} {}", id, response.statusCode(), clip(response.body()));
        }
        if (response.statusCode() >= 400) {
            throw httpFailure(response.statusCode(), response.body(), retryAfter(response));
        }
        try {
            return json.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new ProviderFailure(FailureKind.SERVER_ERROR, response.statusCode(),
                    id + " returned a body that is not JSON", null, e);
        }
    }

    protected SseReader openSse(URI uri, Object body, Duration timeout, String requestId) {
        String payload = write(body);
        HttpRequest request = request(uri, payload, timeout, requestId, "text/event-stream");
        logOut(uri, payload);
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderFailure(FailureKind.CONNECT, 0, "interrupted", null, e);
        } catch (IOException e) {
            throw transport(e);
        }
        if (response.statusCode() >= 400) {
            String err;
            try (InputStream in = response.body()) {
                err = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            } catch (IOException e) {
                err = "";
            }
            if (wire.isDebugEnabled()) {
                wire.debug("{} <- {} {}", id, response.statusCode(), clip(err));
            }
            throw httpFailure(response.statusCode(), err, retryAfter(response));
        }
        return new SseReader(response.body());
    }

    /** Maps one provider event to a delta to emit, nothing, or the provider's end-of-stream marker. */
    @FunctionalInterface
    protected interface EventMapper {
        Step map(SseReader.Event event) throws IOException;
    }

    protected record Step(StreamDelta delta, boolean done) {
        static final Step SKIP = new Step(null, false);
        static final Step DONE = new Step(null, true);

        static Step emit(StreamDelta d) {
            return new Step(d, false);
        }
    }

    protected ChunkStream chunkStream(SseReader reader, EventMapper mapper, boolean endOfStreamIsClean) {
        IdleWatchdog watchdog = new IdleWatchdog(streamIdleTimeout, reader::close);
        return new ChunkStream() {
            private boolean finished;
            private boolean closed;

            @Override
            public StreamDelta next() {
                if (finished || closed) {
                    return null;
                }
                try {
                    while (true) {
                        watchdog.arm();
                        SseReader.Event event;
                        try {
                            event = reader.next();
                        } finally {
                            watchdog.disarm();
                        }
                        if (event == null) {
                            finished = true;
                            if (endOfStreamIsClean) {
                                return null;
                            }
                            throw new ProviderFailure(FailureKind.MIDSTREAM, id + " closed the stream before it finished");
                        }
                        if (wire.isTraceEnabled()) {
                            wire.trace("{} <- event {}", id, clip(event.data()));
                        }
                        Step step = mapper.map(event);
                        if (step.done()) {
                            finished = true;
                            return step.delta();
                        }
                        if (step.delta() != null) {
                            return step.delta();
                        }
                    }
                } catch (IOException e) {
                    finished = true;
                    String why = watchdog.fired()
                            ? id + " sent nothing for " + watchdog.idle().toMillis() + "ms"
                            : id + " stream broke: " + e.getMessage();
                    throw new ProviderFailure(FailureKind.MIDSTREAM, 0, why, null, e);
                }
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    reader.close();
                }
            }
        };
    }

    /**
     * Builds the failure for an HTTP error. Most providers wrap errors as {"error":{"message":...}}; that message
     * is what goes in the problem detail, on one line, instead of the raw (often pretty-printed) body.
     */
    protected ProviderFailure httpFailure(int status, String body, Duration retryAfter) {
        String message = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode err = json.readTree(body).path("error");
                message = err.isTextual() ? err.asText() : text(err, "message");
            } catch (JsonProcessingException | RuntimeException e) {
                message = null;
            }
        }
        if (message == null) {
            return ProviderFailure.http(status, body, retryAfter);
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        if (flat.length() > 300) {
            flat = flat.substring(0, 300) + "...";
        }
        return new ProviderFailure(FailureKind.fromHttpStatus(status), status,
                id + " returned " + status + ": " + flat, retryAfter, null);
    }

    private void logOut(URI uri, String payload) {
        if (wire.isDebugEnabled()) {
            // path only: a query string could carry a key with some providers
            wire.debug("{} -> POST {} {}", id, uri.getPath(), clip(payload));
        }
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() > WIRE_LOG_LIMIT ? one.substring(0, WIRE_LOG_LIMIT) + "...(" + one.length() + " chars)" : one;
    }

    protected String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpRequest request(URI uri, String payload, Duration timeout, String requestId, String accept) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (requestId != null) {
            b.header("X-Request-Id", requestId);
        }
        authHeaders().forEach(b::header);
        return b.build();
    }

    private ProviderFailure transport(Throwable e) {
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException) {
            return new ProviderFailure(FailureKind.CONNECT, 0, id + " unreachable: " + e, null, e);
        }
        if (e instanceof HttpTimeoutException) {
            return new ProviderFailure(FailureKind.TIMEOUT, 0, id + " timed out", null, e);
        }
        // reset or broken pipe after the request went out: the upstream may have done the work, so this is not
        // retried on the same provider
        return new ProviderFailure(FailureKind.SERVER_ERROR, 0, id + " transport error: " + e, null, e);
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    protected static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
