/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production {@link RateLimiter} — a per-key token bucket (Doc 09 §9, §12.5).
 *
 * <p>Defaults: {@code requests_per_minute = 300} sustained, {@code burst_size = 50}.
 * Each key gets a bucket of {@code burst_size} tokens that refills continuously at
 * {@code requests_per_minute / 60} tokens per second up to the burst ceiling. A
 * request consumes one token; when the bucket is empty the result is not allowed
 * and carries a {@code retryAfterSeconds} hint (the filter returns 429 +
 * {@code Retry-After}).</p>
 *
 * <p><strong>Bounded by authenticated keys.</strong> The filter calls
 * {@link #check(String)} only <em>after</em> authentication succeeds, with the
 * resolved {@code key_id} — so the bucket map is bounded by the number of minted
 * tokens (typically &lt; 10), never by attacker-supplied input (unauthenticated
 * requests are rejected before rate limiting).</p>
 *
 * <p><strong>Clock-injected</strong> (no {@code Instant.now()}/{@code
 * System.nanoTime()} — §4c). Thread-safe via {@link ConcurrentHashMap#compute}
 * atomic per-key remap; no {@code synchronized}, no held locks across I/O
 * (LTD-11).</p>
 *
 * @see RateLimiter
 * @see RateLimitResult
 */
public final class StandardRateLimiter implements RateLimiter {

    /** Doc 09 §9 default sustained rate. */
    public static final int DEFAULT_REQUESTS_PER_MINUTE = 300;

    /** Doc 09 §9 default burst ceiling. */
    public static final int DEFAULT_BURST_SIZE = 50;

    private final Clock clock;
    private final double capacity;
    private final double refillPerSecond;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Immutable token-bucket snapshot; {@link ConcurrentHashMap#compute} swaps it atomically. */
    private record Bucket(double tokens, Instant lastRefill) {
    }

    /**
     * Constructs a rate limiter with the Doc 09 §9 defaults (300 rpm / burst 50).
     *
     * @param clock injected clock; never {@code null}
     */
    public StandardRateLimiter(Clock clock) {
        this(clock, DEFAULT_REQUESTS_PER_MINUTE, DEFAULT_BURST_SIZE);
    }

    /**
     * @param clock             injected clock; never {@code null}
     * @param requestsPerMinute sustained rate ceiling; {@code >= 1}
     * @param burstSize         burst ceiling (bucket capacity); {@code >= 1}
     */
    public StandardRateLimiter(Clock clock, int requestsPerMinute, int burstSize) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException(
                    "requestsPerMinute must be >= 1, got " + requestsPerMinute);
        }
        if (burstSize < 1) {
            throw new IllegalArgumentException("burstSize must be >= 1, got " + burstSize);
        }
        this.capacity = burstSize;
        this.refillPerSecond = requestsPerMinute / 60.0;
    }

    @Override
    public RateLimitResult check(String apiKeyId) {
        Objects.requireNonNull(apiKeyId, "apiKeyId");
        Instant now = clock.instant();
        RateLimitResult[] holder = new RateLimitResult[1];
        buckets.compute(apiKeyId, (key, current) -> {
            double tokens;
            if (current == null) {
                tokens = capacity;
            } else {
                double elapsedSeconds =
                        Math.max(0.0, Duration.between(current.lastRefill(), now).toNanos()
                                / 1_000_000_000.0);
                tokens = Math.min(capacity, current.tokens() + elapsedSeconds * refillPerSecond);
            }
            if (tokens >= 1.0) {
                holder[0] = new RateLimitResult(true, 0L);
                return new Bucket(tokens - 1.0, now);
            }
            long retryAfter = Math.max(1L, (long) Math.ceil((1.0 - tokens) / refillPerSecond));
            holder[0] = new RateLimitResult(false, retryAfter);
            return new Bucket(tokens, now);
        });
        return holder[0];
    }
}
