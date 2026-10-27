package com.tokengate.gateway.adapter.out.embedding;

import com.tokengate.gateway.domain.cache.Vectors;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class EmbeddingProvidersTest {

    private static WordPieceTokenizer tiny() {
        List<String> words = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "un", "##aff", "##able", "hello", ",",
                "world", "!", "cafe");
        Map<String, Integer> vocab = new HashMap<>();
        for (int i = 0; i < words.size(); i++) {
            vocab.put(words.get(i), i);
        }
        return new WordPieceTokenizer(vocab);
    }

    @Test
    void wordPieceSplitsGreedilyLongestFirst() {
        assertThat(tiny().wordPiece("unaffable")).containsExactly(4, 5, 6);
        assertThat(tiny().wordPiece("unknownword")).containsExactly(1);
    }

    @Test
    void basicSplitLowercasesStripsAccentsAndIsolatesPunctuation() {
        assertThat(tiny().basicSplit("Hello,  WORLD! Café")).containsExactly("hello", ",", "world", "!", "cafe");
    }

    @Test
    void encodeWrapsInClsSepAndTruncates() {
        assertThat(tiny().encode("hello world", 16)).containsExactly(2, 7, 9, 3);
        assertThat(tiny().encode("hello world hello world", 4)).containsExactly(2, 7, 9, 3);
    }

    @Test
    void hashingEmbeddingIsDeterministicNormalisedAndRoughlyLexical() {
        HashingEmbeddingProvider h = new HashingEmbeddingProvider(384);
        float[] a = h.embed("How do I reset my password?");
        float[] b = h.embed("how do i reset my password");
        float[] c = h.embed("Best biryani in Hyderabad");

        assertThat(a).containsExactly(h.embed("How do I reset my password?"));
        assertThat(Vectors.cosine(a, a)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(Vectors.cosine(a, b)).isGreaterThan(0.95);
        assertThat(Vectors.cosine(a, c)).isLessThan(0.3);
        assertThat(h.embed("  !!  ")).containsOnly(0f);
    }

    /** Runs only where the MiniLM files are present (scripts/fetch-model.sh); CI without them skips it. */
    @Test
    void miniLmPutsParaphrasesCloserThanUnrelatedText() throws Exception {
        Path dir = Path.of(System.getProperty("tokengate.model-dir", "../models/all-MiniLM-L6-v2"));
        assumeThat(Files.exists(dir.resolve("model.onnx"))).as("MiniLM model present").isTrue();
        try (OnnxMiniLmEmbeddingProvider m = new OnnxMiniLmEmbeddingProvider(dir, 128, 1)) {
            float[] q1 = m.embed("How can I change my account password?");
            float[] q2 = m.embed("What are the steps to reset my password?");
            float[] other = m.embed("Recommend a good restaurant for dinner tonight");

            assertThat(m.dimensions()).isEqualTo(384);
            assertThat(q1).hasSize(384);
            assertThat(Vectors.cosine(q1, q2)).isGreaterThan(Vectors.cosine(q1, other) + 0.3);
        }
    }
}
