package com.tokengate.gateway.application.port.out;

import com.tokengate.gateway.domain.chat.ChatCompletion;
import com.tokengate.gateway.domain.chat.EmbeddingResult;
import com.tokengate.gateway.domain.routing.ProviderFailure;
import com.tokengate.gateway.domain.routing.ProviderId;

import java.time.Duration;
import java.util.List;

/** Every method throws {@link ProviderFailure} for anything that is not a usable answer. */
public interface ChatProvider {

    ProviderId id();

    ChatCompletion complete(ProviderCall call);

    /** Returns once the upstream accepted the request (2xx headers seen); chunks are pulled from the stream. */
    ChunkStream openStream(ProviderCall call);

    default boolean supportsEmbeddings() {
        return false;
    }

    default EmbeddingResult embed(String nativeModel, List<String> input, Duration timeout) {
        throw new UnsupportedOperationException(id() + " does not serve embeddings");
    }
}
