package com.tokengate.gateway.domain.cache;

public final class Vectors {

    private Vectors() {
    }

    public static double dot(float[] a, float[] b) {
        requireSameLength(a, b);
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    public static double norm(float[] a) {
        return Math.sqrt(dot(a, a));
    }

    /** Returns 0 for a zero vector rather than NaN, so an empty prompt never matches anything. */
    public static double cosine(float[] a, float[] b) {
        double na = norm(a);
        double nb = norm(b);
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot(a, b) / (na * nb);
    }

    public static float[] normalise(float[] a) {
        double n = norm(a);
        float[] out = new float[a.length];
        if (n == 0) {
            return out;
        }
        for (int i = 0; i < a.length; i++) {
            out[i] = (float) (a[i] / n);
        }
        return out;
    }

    private static void requireSameLength(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dimension mismatch: " + a.length + " vs " + b.length);
        }
    }
}
