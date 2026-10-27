package com.tokengate.gateway.adapter.in.web.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.tokengate.gateway.adapter.in.web.RequestIdFilter;
import com.tokengate.gateway.application.chat.Caller;
import com.tokengate.gateway.application.embedding.EmbeddingsService;
import com.tokengate.gateway.domain.chat.EmbeddingRequest;
import com.tokengate.gateway.domain.chat.EmbeddingResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/v1")
@Tag(name = "Embeddings")
@SecurityRequirement(name = "apiKey")
public class EmbeddingsController {

    private final EmbeddingsService embeddings;

    public EmbeddingsController(EmbeddingsService embeddings) {
        this.embeddings = embeddings;
    }

    public record Request(String model, JsonNode input) {
    }

    public record Item(String object, int index, float[] embedding) {
    }

    public record Usage(@JsonProperty("prompt_tokens") long promptTokens,
                        @JsonProperty("total_tokens") long totalTokens) {
    }

    public record Response(String object, List<Item> data, String model, Usage usage) {
    }

    @Operation(summary = "OpenAI-compatible embeddings; the local model runs in-process")
    @PostMapping(path = "/embeddings", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Response embed(@AuthenticationPrincipal Caller caller, @RequestBody Request body, HttpServletRequest http) {
        String model = body.model() == null || body.model().isBlank() ? embeddings.localModel() : body.model();
        EmbeddingResult r = embeddings.embed(caller, RequestIdFilter.of(http), new EmbeddingRequest(model,
                inputs(body.input())));
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < r.vectors().size(); i++) {
            items.add(new Item("embedding", i, r.vectors().get(i)));
        }
        long prompt = r.usage().promptTokens();
        return new Response("list", items, r.providerModel(), new Usage(prompt, prompt));
    }

    private static List<String> inputs(JsonNode input) {
        if (input == null || input.isNull()) {
            throw new IllegalArgumentException("input is required");
        }
        if (input.isTextual()) {
            return List.of(input.asText());
        }
        if (!input.isArray()) {
            throw new IllegalArgumentException("input must be a string or an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : input) {
            if (!n.isTextual()) {
                throw new IllegalArgumentException("token-id input is not supported, send text");
            }
            out.add(n.asText());
        }
        return out;
    }
}
