package com.tokengate.gateway.domain.tenant;

import com.tokengate.gateway.domain.pii.AhoCorasick;
import com.tokengate.gateway.domain.pii.PiiMode;
import com.tokengate.gateway.domain.pii.PiiScanner;

import java.util.List;

public record PiiSettings(PiiMode mode, List<String> customTerms) {

    public PiiSettings {
        customTerms = customTerms == null ? List.of() : List.copyOf(customTerms);
    }

    public static PiiSettings defaults() {
        return new PiiSettings(PiiMode.TOKENIZE, List.of());
    }

    public PiiScanner scanner() {
        return customTerms.isEmpty() ? PiiScanner.standard() : new PiiScanner(new AhoCorasick(customTerms));
    }
}
