package com.tokengate.gateway.domain.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LruTtlCacheTest {

    private final AtomicLong now = new AtomicLong();
    private final LruTtlCache<String, Integer> cache = new LruTtlCache<>(3, Duration.ofSeconds(10), now::get);

    @Test
    void evictsLeastRecentlyUsed() {
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("a");
        cache.put("d", 4);

        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("a")).contains(1);
        assertThat(cache.get("c")).contains(3);
        assertThat(cache.get("d")).contains(4);
        assertThat(cache.stats().evictions()).isEqualTo(1);
    }

    @Test
    void entriesExpireAfterTtl() {
        cache.put("a", 1);
        now.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(cache.get("a")).contains(1);
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void putOnExistingKeyRefreshesValueTtlAndRecency() {
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        cache.put("a", 10);
        cache.put("d", 4);

        assertThat(cache.get("a")).contains(10);
        assertThat(cache.get("b")).isEmpty();
        now.addAndGet(Duration.ofSeconds(6).toNanos());
        assertThat(cache.get("a")).contains(10);
        assertThat(cache.get("c")).isEmpty();
    }

    @Test
    void prefersEvictingAnExpiredEntryOverTheLruOne() {
        cache.put("x", 1);
        now.addAndGet(Duration.ofSeconds(5).toNanos());
        cache.put("y", 2);
        now.addAndGet(Duration.ofSeconds(4).toNanos());
        cache.get("x");
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        cache.put("z", 3);
        cache.put("w", 4);

        assertThat(cache.get("y")).contains(2);
        assertThat(cache.get("x")).isEmpty();
        assertThat(cache.get("z")).contains(3);
        assertThat(cache.get("w")).contains(4);
    }

    @Test
    void removeAndClear() {
        cache.put("a", 1);
        cache.put("b", 2);
        assertThat(cache.remove("a")).isTrue();
        assertThat(cache.remove("a")).isFalse();
        cache.clear();
        assertThat(cache.size()).isZero();
        cache.put("c", 3);
        assertThat(cache.get("c")).contains(3);
    }

    @Test
    void countsHitsAndMisses() {
        cache.put("a", 1);
        cache.get("a");
        cache.get("a");
        cache.get("zzz");
        assertThat(cache.stats()).isEqualTo(new LruTtlCache.Stats(2, 1, 0, 1));
    }

    @Test
    void capacityOneStillWorks() {
        LruTtlCache<String, String> tiny = new LruTtlCache<>(1, Duration.ofMinutes(1), now::get);
        tiny.put("a", "1");
        tiny.put("b", "2");
        assertThat(tiny.get("a")).isEmpty();
        assertThat(tiny.get("b")).contains("2");
    }

    @Test
    void survivesConcurrentUse() throws Exception {
        LruTtlCache<Integer, Integer> shared = LruTtlCache.create(100, Duration.ofMinutes(1));
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            int seed = t;
            threads[t] = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 5_000; i++) {
                    int k = (i * 31 + seed) % 300;
                    shared.put(k, k);
                    shared.get((k + 7) % 300).ifPresent(v -> assertThat(v).isEqualTo((k + 7) % 300));
                }
            });
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(shared.size()).isLessThanOrEqualTo(100);
    }
}
