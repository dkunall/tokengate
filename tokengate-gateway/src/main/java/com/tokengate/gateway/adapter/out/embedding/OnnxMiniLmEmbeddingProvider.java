package com.tokengate.gateway.adapter.out.embedding;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.tokengate.gateway.application.port.out.EmbeddingProvider;
import com.tokengate.gateway.domain.cache.Vectors;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * sentence-transformers/all-MiniLM-L6-v2 on CPU via ONNX Runtime: WordPiece, the transformer, attention-masked
 * mean pooling, then L2 normalisation, which is what the sentence-transformers pipeline does.
 */
public class OnnxMiniLmEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

    private static final int DIMENSIONS = 384;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;
    private final int maxLength;
    private final boolean wantsTokenTypes;

    public OnnxMiniLmEmbeddingProvider(Path modelDir, int maxLength, int threads) throws IOException, OrtException {
        this.tokenizer = WordPieceTokenizer.load(modelDir.resolve("vocab.txt"));
        this.maxLength = maxLength;
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(threads);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelDir.resolve("model.onnx").toString(), options);
        this.wantsTokenTypes = session.getInputNames().contains("token_type_ids");
    }

    @Override
    public String modelName() {
        return "all-MiniLM-L6-v2";
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public float[] embed(String text) {
        long[] ids = tokenizer.encode(text, maxLength);
        int n = ids.length;
        long[] mask = new long[n];
        Arrays.fill(mask, 1L);
        long[] shape = {1, n};
        try (OnnxTensor inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
             OnnxTensor attention = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
             OnnxTensor types = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[n]), shape)) {
            Map<String, OnnxTensor> inputs = wantsTokenTypes
                    ? Map.of("input_ids", inputIds, "attention_mask", attention, "token_type_ids", types)
                    : Map.of("input_ids", inputIds, "attention_mask", attention);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                return Vectors.normalise(meanPool(hidden[0], mask));
            }
        } catch (OrtException e) {
            throw new IllegalStateException("embedding inference failed", e);
        }
    }

    private static float[] meanPool(float[][] tokens, long[] mask) {
        float[] out = new float[tokens[0].length];
        int counted = 0;
        for (int t = 0; t < tokens.length; t++) {
            if (mask[t] == 0) {
                continue;
            }
            counted++;
            for (int d = 0; d < out.length; d++) {
                out[d] += tokens[t][d];
            }
        }
        for (int d = 0; d < out.length; d++) {
            out[d] /= Math.max(1, counted);
        }
        return out;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
