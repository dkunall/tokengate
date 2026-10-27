package com.tokengate.gateway.adapter.out.embedding;

import com.tokengate.gateway.application.port.out.EmbeddingProvider;
import com.tokengate.gateway.domain.cache.Vectors;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Fallback when the ONNX model is not on disk. Hashes word unigrams and character trigrams into a fixed number
 * of buckets (signed, so collisions partly cancel). It catches rewordings that share most words, not
 * paraphrases; the semantic threshold needs to be higher with this one.
 */
public class HashingEmbeddingProvider implements EmbeddingProvider {

    private final int dimensions;

    public HashingEmbeddingProvider(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public String modelName() {
        return "hashing-" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dimensions];
        String norm = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (norm.isEmpty()) {
            return v;
        }
        for (String word : norm.split(" ")) {
            add(v, "w:" + word, 1.0f);
        }
        String padded = " " + norm + " ";
        for (int i = 0; i + 3 <= padded.length(); i++) {
            add(v, "c:" + padded.substring(i, i + 3), 0.5f);
        }
        return Vectors.normalise(v);
    }

    private void add(float[] v, String feature, float weight) {
        int h = fnvMix(feature.getBytes(StandardCharsets.UTF_8));
        int bucket = Math.floorMod(h, dimensions);
        v[bucket] += (h & 0x80000000) == 0 ? weight : -weight;
    }

    // FNV-1a followed by the murmur3 finaliser; plain FNV clusters short strings into nearby buckets
    private static int fnvMix(byte[] data) {
        int h = 0x811c9dc5;
        for (byte b : data) {
            h ^= b;
            h *= 0x01000193;
        }
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
