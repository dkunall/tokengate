package com.tokengate.metering.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of every price version, indexed by provider model and sorted by effective date, so a
 * lookup is a hash probe plus a binary search for the last version that started at or before the event.
 */
public final class PriceBook {

    private final Map<String, List<PriceVersion>> byModel;

    private PriceBook(Map<String, List<PriceVersion>> byModel) {
        this.byModel = byModel;
    }

    public static PriceBook of(Collection<PriceVersion> versions) {
        Map<String, List<PriceVersion>> grouped = new HashMap<>();
        for (PriceVersion v : versions) {
            grouped.computeIfAbsent(key(v.provider(), v.providerModel()), k -> new ArrayList<>()).add(v);
        }
        grouped.replaceAll((k, list) -> list.stream()
                .sorted(Comparator.comparing(PriceVersion::effectiveFrom))
                .toList());
        return new PriceBook(Map.copyOf(grouped));
    }

    public Optional<PriceVersion> priceAt(String provider, String providerModel, Instant at) {
        if (provider == null || providerModel == null) {
            return Optional.empty();
        }
        List<PriceVersion> versions = byModel.get(key(provider, providerModel));
        if (versions == null) {
            return Optional.empty();
        }
        int lo = 0;
        int hi = versions.size() - 1;
        PriceVersion found = null;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            PriceVersion v = versions.get(mid);
            if (v.effectiveFrom().isAfter(at)) {
                hi = mid - 1;
            } else {
                found = v;
                lo = mid + 1;
            }
        }
        return Optional.ofNullable(found);
    }

    /** The newest version for that model, used to stop a new price from being slipped in before it. */
    public Optional<PriceVersion> latest(String provider, String providerModel) {
        List<PriceVersion> versions = byModel.get(key(provider, providerModel));
        return versions == null ? Optional.empty() : Optional.of(versions.get(versions.size() - 1));
    }

    public List<PriceVersion> all() {
        return byModel.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(PriceVersion::provider)
                        .thenComparing(PriceVersion::providerModel)
                        .thenComparing(PriceVersion::effectiveFrom))
                .toList();
    }

    private static String key(String provider, String providerModel) {
        return provider + '\u0000' + providerModel;
    }
}
