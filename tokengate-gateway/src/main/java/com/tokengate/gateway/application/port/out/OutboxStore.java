package com.tokengate.gateway.application.port.out;

public interface OutboxStore {

    /**
     * Locks up to {@code max} unpublished rows (skipping rows another instance holds), hands each to the
     * delivery in insertion order and marks it published. Stops at the first delivery failure so order per
     * key is kept. Returns how many were published.
     */
    int drain(int max, Delivery delivery);

    long pending();

    record Message(long id, String key, String payload) {
    }

    @FunctionalInterface
    interface Delivery {
        void deliver(Message message) throws Exception;
    }
}
