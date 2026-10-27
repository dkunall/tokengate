package com.tokengate.gateway.adapter.out.tokenizer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.tokengate.gateway.application.port.out.TokenCounter;
import com.tokengate.gateway.domain.chat.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * cl100k_base for every model. It is exact for gpt-3.5/gpt-4 class models and only an estimate for Gemini and
 * Claude, which use their own tokenizers; the estimate is used for admission and replaced by the provider's
 * reported usage afterwards.
 */
@Component
public class JtokkitTokenCounter implements TokenCounter {

    // per-message framing tokens from OpenAI's counting guide for the chat format
    private static final int PER_MESSAGE = 3;
    private static final int REPLY_PRIMING = 3;

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    @Override
    public String encoding() {
        return "cl100k_base";
    }

    @Override
    public long countText(String text) {
        return text == null || text.isEmpty() ? 0 : encoding.countTokensOrdinary(text);
    }

    @Override
    public long countMessages(List<ChatMessage> messages) {
        long total = REPLY_PRIMING;
        for (ChatMessage m : messages) {
            total += PER_MESSAGE + countText(m.role().wireName()) + countText(m.content());
        }
        return total;
    }
}
