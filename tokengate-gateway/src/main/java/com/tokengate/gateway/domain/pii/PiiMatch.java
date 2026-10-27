package com.tokengate.gateway.domain.pii;

public record PiiMatch(int start, int end, PiiType type, String value) {

    public int length() {
        return end - start;
    }

    public boolean overlaps(PiiMatch other) {
        return start < other.end && other.start < end;
    }
}
