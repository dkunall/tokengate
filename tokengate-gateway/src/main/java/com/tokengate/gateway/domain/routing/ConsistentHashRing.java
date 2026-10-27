package com.tokengate.gateway.domain.routing;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Hash ring with virtual nodes on a TreeMap. Lookups take the first node clockwise from the key's hash
 * (ceilingEntry, wrapping to firstEntry); with a predicate they keep walking until an eligible node, so an
 * unhealthy provider only moves its own keys.
 */
public final class ConsistentHashRing<T> {

    private final NavigableMap<Long, T> ring = new TreeMap<>();
    private final int virtualNodes;
    private final Function<T, String> nodeName;

    public ConsistentHashRing(int virtualNodes, Function<T, String> nodeName) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be >= 1");
        }
        this.virtualNodes = virtualNodes;
        this.nodeName = nodeName;
    }

    public synchronized void add(T node) {
        for (int i = 0; i < virtualNodes; i++) {
            ring.put(hash(nodeName.apply(node) + "#" + i), node);
        }
    }

    public synchronized void remove(T node) {
        for (int i = 0; i < virtualNodes; i++) {
            ring.remove(hash(nodeName.apply(node) + "#" + i), node);
        }
    }

    public Optional<T> get(String key) {
        return get(key, n -> true);
    }

    public synchronized Optional<T> get(String key, Predicate<T> eligible) {
        if (ring.isEmpty()) {
            return Optional.empty();
        }
        long h = hash(key);
        for (Map.Entry<Long, T> e : ring.tailMap(h, true).entrySet()) {
            if (eligible.test(e.getValue())) {
                return Optional.of(e.getValue());
            }
        }
        for (Map.Entry<Long, T> e : ring.headMap(h, false).entrySet()) {
            if (eligible.test(e.getValue())) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    public synchronized int size() {
        return ring.size();
    }

    /** FNV-1a 64 with murmur3's fmix64 on top; plain FNV clusters badly for short, similar keys like "a#1". */
    static long hash(String key) {
        long h = 0xcbf29ce484222325L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            h ^= b;
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
