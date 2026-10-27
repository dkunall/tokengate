package com.tokengate.gateway.adapter.out.redis;

/** The tenant id sits in a hash tag so a tenant's keys land on one slot if this ever runs on Redis Cluster. */
final class RedisKeys {

    private RedisKeys() {
    }

    static String bucket(String tenant) {
        return "tg:rl:{" + tenant + "}";
    }

    static String tokensDay(String tenant, String day) {
        return "tg:budget:{" + tenant + "}:tokens:" + day;
    }

    static String costMonth(String tenant, String month) {
        return "tg:budget:{" + tenant + "}:cost:" + month;
    }

    static String exact(String fingerprint) {
        return "tg:cache:exact:" + fingerprint;
    }
}
