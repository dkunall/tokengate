package com.tokengate.gateway.adapter.in.web.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tokengate.gateway.domain.chat.TokenUsage;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage) {

    public record Choice(int index, Message message, Delta delta,
                         @JsonProperty("finish_reason") @JsonInclude(JsonInclude.Include.ALWAYS) String finishReason) {
    }

    public record Message(String role, String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {
    }

    public record Usage(@JsonProperty("prompt_tokens") long promptTokens,
                        @JsonProperty("completion_tokens") long completionTokens,
                        @JsonProperty("total_tokens") long totalTokens) {

        static Usage of(TokenUsage u) {
            return u == null ? null : new Usage(u.promptTokens(), u.completionTokens(), u.total());
        }
    }

    static ChatCompletionResponse completion(String id, long created, String model, String content,
                                             String finishReason, TokenUsage usage) {
        return new ChatCompletionResponse(id, "chat.completion", created, model,
                List.of(new Choice(0, new Message("assistant", content), null,
                        finishReason == null ? "stop" : finishReason)),
                Usage.of(usage));
    }

    static ChatCompletionResponse chunk(String id, long created, String model, Delta delta, String finishReason,
                                        TokenUsage usage) {
        return new ChatCompletionResponse(id, "chat.completion.chunk", created, model,
                List.of(new Choice(0, null, delta, finishReason)), Usage.of(usage));
    }
}
