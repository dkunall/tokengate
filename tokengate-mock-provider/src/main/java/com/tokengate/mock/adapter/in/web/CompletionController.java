package com.tokengate.mock.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokengate.mock.domain.Behaviour;
import com.tokengate.mock.domain.FakeUpstream;
import com.tokengate.mock.domain.RecordedRequest;
import com.tokengate.mock.domain.Replies;
import com.tokengate.mock.domain.UpstreamRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * OpenAI wire format, mounted under /{upstream}/v1 so one process can pretend to be several providers. Every
 * response goes out as a StreamingResponseBody because whether it is SSE or JSON depends on the request body,
 * and Spring picks the return value handler from the declared type.
 */
@RestController
class CompletionController {

    private static final int EMBEDDING_DIMS = 384;

    private final UpstreamRegistry registry;
    private final RandomGenerator random;
    private final ObjectMapper json;
    private final Clock clock = Clock.systemUTC();

    CompletionController(UpstreamRegistry registry, RandomGenerator random, ObjectMapper json) {
        this.registry = registry;
        this.random = random;
        this.json = json;
    }

    @PostMapping(path = "/{upstream}/v1/chat/completions", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    ResponseEntity<StreamingResponseBody> chat(@PathVariable String upstream, @RequestBody JsonNode body) {
        FakeUpstream up = registry.find(upstream).orElse(null);
        if (up == null) {
            return error(HttpStatus.NOT_FOUND, "unknown upstream " + upstream, "not_found");
        }
        String model = body.path("model").asText("unknown");
        boolean stream = body.path("stream").asBoolean(false);
        List<String> contents = new ArrayList<>();
        String lastUser = "";
        for (JsonNode m : body.path("messages")) {
            String c = m.path("content").asText("");
            contents.add(m.path("role").asText("user") + ": " + c);
            if ("user".equals(m.path("role").asText())) {
                lastUser = c;
            }
        }
        if (contents.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "messages must not be empty", "invalid_request_error");
        }
        ResponseEntity<StreamingResponseBody> failure = admit(up, new RecordedRequest(clock.instant(), "chat", model, contents, stream));
        if (failure != null) {
            return failure;
        }
        String answer = Replies.answer(upstream, model, lastUser);
        int promptTokens = contents.stream().mapToInt(Replies::estimateTokens).sum();
        Behaviour b = up.behaviour();
        int completionTokens = b.completionTokens() > 0 ? b.completionTokens() : Math.max(1, Replies.countWords(answer));
        String id = "chatcmpl-" + UUID.randomUUID();
        long created = clock.instant().getEpochSecond();

        if (!stream) {
            ObjectNode res = json.createObjectNode();
            res.put("id", id).put("object", "chat.completion").put("created", created).put("model", model);
            ObjectNode choice = res.putArray("choices").addObject();
            choice.put("index", 0).put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", answer);
            usage(res.putObject("usage"), promptTokens, completionTokens);
            return json(HttpStatus.OK, res);
        }
        boolean includeUsage = body.path("stream_options").path("include_usage").asBoolean(false);
        StreamingResponseBody sse = out -> {
            List<String> chunks = Replies.chunks(answer);
            for (int i = 0; i < chunks.size(); i++) {
                if (b.failMidStreamAfterChunks() >= 0 && i == b.failMidStreamAfterChunks()) {
                    // ends the body without [DONE] or a finish_reason, which is what a dropped upstream looks like
                    return;
                }
                ObjectNode chunk = chunkFrame(id, created, model);
                ObjectNode choice = (ObjectNode) chunk.get("choices").get(0);
                ObjectNode delta = choice.putObject("delta");
                if (i == 0) {
                    delta.put("role", "assistant");
                }
                delta.put("content", chunks.get(i));
                choice.putNull("finish_reason");
                write(out, chunk);
                sleep(b.chunkDelayMs());
            }
            ObjectNode last = chunkFrame(id, created, model);
            ObjectNode choice = (ObjectNode) last.get("choices").get(0);
            choice.putObject("delta");
            choice.put("finish_reason", "stop");
            write(out, last);
            if (includeUsage) {
                ObjectNode u = chunkFrame(id, created, model);
                u.putArray("choices");
                usage(u.putObject("usage"), promptTokens, completionTokens);
                write(out, u);
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache").body(sse);
    }

    @PostMapping(path = "/{upstream}/v1/embeddings", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StreamingResponseBody> embeddings(@PathVariable String upstream, @RequestBody JsonNode body) {
        FakeUpstream up = registry.find(upstream).orElse(null);
        if (up == null) {
            return error(HttpStatus.NOT_FOUND, "unknown upstream " + upstream, "not_found");
        }
        List<String> inputs = new ArrayList<>();
        JsonNode input = body.path("input");
        if (input.isArray()) {
            input.forEach(n -> inputs.add(n.asText()));
        } else if (input.isTextual()) {
            inputs.add(input.asText());
        }
        if (inputs.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "input must not be empty", "invalid_request_error");
        }
        String model = body.path("model").asText("unknown");
        ResponseEntity<StreamingResponseBody> failure = admit(up, new RecordedRequest(clock.instant(), "embeddings", model, inputs, false));
        if (failure != null) {
            return failure;
        }
        ObjectNode res = json.createObjectNode();
        res.put("object", "list").put("model", model);
        ArrayNode data = res.putArray("data");
        int tokens = 0;
        for (int i = 0; i < inputs.size(); i++) {
            ObjectNode d = data.addObject();
            d.put("object", "embedding").put("index", i);
            ArrayNode vec = d.putArray("embedding");
            for (float f : Replies.embedding(inputs.get(i), EMBEDDING_DIMS)) {
                vec.add(f);
            }
            tokens += Replies.estimateTokens(inputs.get(i));
        }
        res.putObject("usage").put("prompt_tokens", tokens).put("total_tokens", tokens);
        return json(HttpStatus.OK, res);
    }

    private ResponseEntity<StreamingResponseBody> admit(FakeUpstream up, RecordedRequest req) {
        FakeUpstream.Verdict verdict = up.admit(req, random);
        if (verdict == FakeUpstream.Verdict.OUTAGE) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, up.name() + " is down (simulated outage)", "server_error");
        }
        sleep(up.delayMillis(random));
        if (verdict == FakeUpstream.Verdict.INJECTED_ERROR) {
            HttpStatus status = HttpStatus.valueOf(up.behaviour().errorStatus());
            return error(status, "injected failure", status.value() == 429 ? "rate_limit_exceeded" : "server_error");
        }
        return null;
    }

    private ObjectNode chunkFrame(String id, long created, String model) {
        ObjectNode chunk = json.createObjectNode();
        chunk.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model);
        chunk.putArray("choices").addObject().put("index", 0);
        return chunk;
    }

    private void write(OutputStream out, JsonNode frame) throws IOException {
        out.write(("data: " + json.writeValueAsString(frame) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void usage(ObjectNode u, int prompt, int completion) {
        u.put("prompt_tokens", prompt).put("completion_tokens", completion).put("total_tokens", prompt + completion);
    }

    private ResponseEntity<StreamingResponseBody> error(HttpStatus status, String message, String type) {
        ObjectNode err = json.createObjectNode();
        err.putObject("error").put("message", message).put("type", type).put("code", status.value());
        return json(status, err);
    }

    private ResponseEntity<StreamingResponseBody> json(HttpStatus status, JsonNode node) {
        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).contentLength(bytes.length)
                .body(out -> out.write(bytes));
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
