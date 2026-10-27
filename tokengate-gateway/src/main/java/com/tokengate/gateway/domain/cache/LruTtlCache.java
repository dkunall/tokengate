package com.tokengate.gateway.domain.cache;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * LRU with a per-entry TTL: a HashMap for O(1) lookup plus a doubly linked list in recency order
 * (head = most recent, tail = eviction candidate). Expired entries are dropped lazily on read and
 * preferentially on eviction.
 *
 * <p>One lock around everything. A get moves a node to the head, so even reads mutate; striping or a
 * concurrent skip structure would only pay off far above the L1 sizes this is used with (a few thousand).
 */
public final class LruTtlCache<K, V> {

    private static final class Node<K, V> {
        final K key;
        V value;
        long expiresAtNanos;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value, long expiresAtNanos) {
            this.key = key;
            this.value = value;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier nanoTime;
    private final Map<K, Node<K, V>> index;
    private Node<K, V> head;
    private Node<K, V> tail;
    private long hits;
    private long misses;
    private long evictions;

    public LruTtlCache(int capacity, Duration ttl, LongSupplier nanoTime) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.ttlNanos = ttl.toNanos();
        this.nanoTime = nanoTime;
        this.index = new HashMap<>(capacity * 4 / 3 + 1);
    }

    public static <K, V> LruTtlCache<K, V> create(int capacity, Duration ttl) {
        return new LruTtlCache<>(capacity, ttl, System::nanoTime);
    }

    public synchronized Optional<V> get(K key) {
        Node<K, V> node = index.get(key);
        if (node == null) {
            misses++;
            return Optional.empty();
        }
        if (expired(node)) {
            unlink(node);
            index.remove(key);
            misses++;
            return Optional.empty();
        }
        moveToHead(node);
        hits++;
        return Optional.of(node.value);
    }

    public synchronized void put(K key, V value) {
        long expires = nanoTime.getAsLong() + ttlNanos;
        Node<K, V> node = index.get(key);
        if (node != null) {
            node.value = value;
            node.expiresAtNanos = expires;
            moveToHead(node);
            return;
        }
        if (index.size() >= capacity) {
            evictOne();
        }
        node = new Node<>(key, value, expires);
        index.put(key, node);
        linkAtHead(node);
    }

    public synchronized boolean remove(K key) {
        Node<K, V> node = index.remove(key);
        if (node == null) {
            return false;
        }
        unlink(node);
        return true;
    }

    public synchronized void clear() {
        index.clear();
        head = tail = null;
    }

    public synchronized int size() {
        return index.size();
    }

    public synchronized Stats stats() {
        return new Stats(hits, misses, evictions, index.size());
    }

    public record Stats(long hits, long misses, long evictions, int size) {
    }

    /** Prefer dropping something already expired near the tail; otherwise the least recently used. */
    private void evictOne() {
        Node<K, V> victim = tail;
        Node<K, V> probe = tail;
        for (int i = 0; i < 8 && probe != null; i++, probe = probe.prev) {
            if (expired(probe)) {
                victim = probe;
                break;
            }
        }
        if (victim != null) {
            unlink(victim);
            index.remove(victim.key);
            evictions++;
        }
    }

    private boolean expired(Node<K, V> node) {
        return nanoTime.getAsLong() - node.expiresAtNanos >= 0;
    }

    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return;
        }
        unlink(node);
        linkAtHead(node);
    }

    private void linkAtHead(Node<K, V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    private void unlink(Node<K, V> node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = node.next = null;
    }
}
