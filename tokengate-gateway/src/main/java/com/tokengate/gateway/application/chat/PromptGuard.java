package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.application.port.out.GatewayMetrics;
import com.tokengate.gateway.domain.cache.LruTtlCache;
import com.tokengate.gateway.domain.chat.ChatRequest;
import com.tokengate.gateway.domain.pii.MaskingSession;
import com.tokengate.gateway.domain.pii.PiiMasker;
import com.tokengate.gateway.domain.pii.PiiScanner;
import com.tokengate.gateway.domain.tenant.PiiSettings;
import com.tokengate.gateway.domain.tenant.Tenant;

import java.time.Duration;
import java.util.List;

/** Masks PII in every message before anything (cache, provider, logs) sees the prompt. */
public final class PromptGuard {

    private final PiiMasker masker;
    private final GatewayMetrics metrics;
    private final PiiScanner standard = PiiScanner.standard();
    private final LruTtlCache<List<String>, PiiScanner> scanners = LruTtlCache.create(1_000, Duration.ofMinutes(10));

    public PromptGuard(PiiMasker masker, GatewayMetrics metrics) {
        this.masker = masker;
        this.metrics = metrics;
    }

    public Guarded mask(Tenant tenant, ChatRequest request) {
        MaskingSession session = new MaskingSession();
        PiiSettings pii = tenant.pii();
        PiiScanner scanner = scanner(pii);
        ChatRequest masked = request.mapContents(
                text -> masker.mask(text, scanner, pii.mode(), tenant.id().value(), session));
        session.counts().forEach((type, n) -> metrics.piiMasked(type.label(), n));
        return new Guarded(masked, session);
    }

    public List<String> maskAll(Tenant tenant, List<String> texts, MaskingSession session) {
        PiiScanner scanner = scanner(tenant.pii());
        return texts.stream()
                .map(t -> masker.mask(t, scanner, tenant.pii().mode(), tenant.id().value(), session))
                .toList();
    }

    private PiiScanner scanner(PiiSettings pii) {
        if (pii.customTerms().isEmpty()) {
            return standard;
        }
        return scanners.get(pii.customTerms()).orElseGet(() -> {
            PiiScanner built = pii.scanner();
            scanners.put(pii.customTerms(), built);
            return built;
        });
    }

    public record Guarded(ChatRequest request, MaskingSession session) {
    }
}
