package dev.llmladder.gateway;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Token bucket por tenant: uma entrada patológica de um cliente não derruba os outros. */
final class RateLimiter {
    private static final class Bucket {
        double tokens;
        long lastRefillMs;
    }

    private final double capacity;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimiter(int requestsPerMinute, Clock clock) {
        this.capacity = requestsPerMinute;
        this.clock = clock;
    }

    boolean tryAcquire(String tenant) {
        Bucket bucket = buckets.computeIfAbsent(tenant, t -> {
            Bucket b = new Bucket();
            b.tokens = capacity;
            b.lastRefillMs = clock.millis();
            return b;
        });
        synchronized (bucket) {
            long now = clock.millis();
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.lastRefillMs) * capacity / 60_000.0);
            bucket.lastRefillMs = now;
            if (bucket.tokens < 1) {
                return false;
            }
            bucket.tokens -= 1;
            return true;
        }
    }
}
