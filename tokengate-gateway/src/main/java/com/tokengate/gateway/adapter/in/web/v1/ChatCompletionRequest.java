package com.tokengate.gateway.adapter.in.web.v1;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.chat.Role;

import java.util.ArrayList;
import java.util.List;

/** The subset of the OpenAI request body the gateway understands. Unknown fields are ignored. */
public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        JsonNode stop,
        Boolean stream,
        String user) {

    public record Message(String role, JsonNode content) {
    }

    ChatRequest toDomain() {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        List<ChatMessage> converted = messages.stream()
                .map(m -> new ChatMessage(Role.parse(m.role()), contentText(m.content())))
                .toList();
        return new ChatRequest(model, converted, temperature, topP,
                maxCompletionTokens != null ? maxCompletionTokens : maxTokens, stops(), Boolean.TRUE.equals(stream),
                user);
    }

    // content can be a string or an array of parts; only text parts are supported
    private static String contentText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (!"text".equals(part.path("type").asText())) {
                    throw new IllegalArgumentException("only text content parts are supported");
                }
                sb.append(part.path("text").asText());
            }
            return sb.toString();
        }
        throw new IllegalArgumentException("message content must be a string or an array of parts");
    }

    private List<String> stops() {
        if (stop == null || stop.isNull()) {
            return List.of();
        }
        if (stop.isTextual()) {
            return List.of(stop.asText());
        }
        List<String> out = new ArrayList<>();
        stop.forEach(s -> out.add(s.asText()));
        return out;
    }
}
