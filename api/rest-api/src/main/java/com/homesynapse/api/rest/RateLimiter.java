/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Per-key token bucket rate limiter for API request throttling.
 *
 * <p>Checks whether a request from a given API key should proceed or be throttled
 * (Doc 09 §12.5). The token bucket allows short bursts (§9 {@code burst_size: 50})
 * while enforcing a sustained rate ceiling (§9 {@code requests_per_minute: 300}).</p>
 *
 * <p>When {@link RateLimitResult#allowed()} is {@code false}, the API returns
 * {@code 429 Too Many Requests} with a {@code Retry-After} header set to
 * {@link RateLimitResult#retryAfterSeconds()}.</p>
 *
 * <p>Phase 3 implements this with a {@code ConcurrentHashMap} of lightweight token
 * buckets — one {@code long} for token count and one {@code long} for last-refill
 * timestamp per key. The memory footprint is bounded by the number of active API
 * keys (typically fewer than 10).</p>
 *
 * <p>Thread-safe. Implementations must support concurrent invocation from multiple
 * virtual threads.</p>
 *
 * @see RateLimitResult
 * @see ProblemType#RATE_LIMITED
 * @see <a href="Doc 09 §12.5">Rate Limiting</a>
 */
public interface RateLimiter {

    /**
     * Checks whether a request from the given API key should proceed.
     *
     * @param apiKeyId the API key's public identifier (from
     *                 {@link ApiKeyIdentity#keyId()}), never {@code null}
     * @return the rate limit check result, never {@code null}
     */
    RateLimitResult check(String apiKeyId);
}
