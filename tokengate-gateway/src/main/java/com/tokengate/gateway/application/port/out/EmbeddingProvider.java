package com.tokengate.gateway.application.port.out;

import java.util.List;

/** Local text embeddings used for the semantic cache and the local embedding model. Output is L2-normalised. */
public interface EmbeddingProvider {

    String modelName();

    int dimensions();

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
