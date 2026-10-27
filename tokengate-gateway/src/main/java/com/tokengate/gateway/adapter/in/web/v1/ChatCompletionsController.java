package com.tokengate.gateway.adapter.in.web.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.common.UsageEvent;
import com.tokengate.gateway.adapter.in.web.GatewayHeaders;
import com.tokengate.gateway.adapter.in.web.RequestIdFilter;
import com.tokengate.gateway.application.chat.Attempt;
import com.tokengate.gateway.application.chat.Caller;
import com.tokengate.gateway.application.chat.ChatResult;
import com.tokengate.gateway.application.chat.ChatService;
import com.tokengate.gateway.application.chat.StreamSession;
import com.tokengate.gateway.domain.chat.ChatCompletion;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.ratelimit.RateLimitDecision;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/v1")
@Tag(name = "Chat")
@SecurityRequirement(name = "apiKey")
public class ChatCompletionsController {

    private final ChatService chat;
    private final ObjectMapper json;
    private final Clock clock;

    public ChatCompletionsController(ChatService chat, ObjectMapper json, Clock clock) {
        this.chat = chat;
        this.json = json;
        this.clock = clock;
    }

    @Operation(summary = "OpenAI-compatible chat completion; set stream=true for server-sent events")
    @PostMapping(path = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<?> complete(@AuthenticationPrincipal Caller caller,
                                      @RequestBody ChatCompletionRequest body,
                                      HttpServletRequest http) {
        String requestId = RequestIdFilter.of(http);
        ChatRequest request = body.toDomain();
        long created = clock.instant().getEpochSecond();
        String id = "chatcmpl-" + requestId;

        if (request.stream()) {
            StreamSession session = chat.openStream(caller, requestId, request);
            HttpHeaders h = served(session.provider(), session.cache(), 0, session.attempts(), session.rateLimit(),
                    session.piiMasked());
            h.setCacheControl("no-cache");
            h.set("X-Accel-Buffering", "no");
            StreamingResponseBody stream = out ->
                    session.pump(new SseStreamSink(out, json, id, created, session.model()));
            return ResponseEntity.ok().headers(h).contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
        }

        ChatResult r = chat.complete(caller, requestId, request);
        ChatCompletion c = r.completion();
        HttpHeaders h = served(r.provider(), r.cache(), r.similarity(), r.attempts(), r.rateLimit(), r.piiMasked());
        return ResponseEntity.ok().headers(h).contentType(MediaType.APPLICATION_JSON)
                .body(ChatCompletionResponse.completion(id, created, request.model(), c.content(), c.finishReason(),
                        c.usage()));
    }

    private static HttpHeaders served(String provider, UsageEvent.CacheOutcome cache, double similarity,
                                      List<Attempt> attempts, RateLimitDecision rl,
                                      int piiMasked) {
        HttpHeaders h = new HttpHeaders();
        GatewayHeaders.rateLimit(h, rl);
        h.set(GatewayHeaders.PROVIDER, provider == null ? "cache" : provider);
        h.set(GatewayHeaders.CACHE, cache.name().toLowerCase(Locale.ROOT));
        if (cache == UsageEvent.CacheOutcome.SEMANTIC_HIT) {
            h.set(GatewayHeaders.SIMILARITY, String.format(Locale.ROOT, "%.4f", similarity));
        }
        h.set(GatewayHeaders.ATTEMPTS, Integer.toString(attempts == null ? 0 : attempts.size()));
        h.set(GatewayHeaders.PII_MASKED, Integer.toString(piiMasked));
        return h;
    }
}
