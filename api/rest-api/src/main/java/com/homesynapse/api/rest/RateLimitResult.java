/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Result of a rate limit check against the per-key token bucket.
 *
 * <p>When a request arrives, the {@link RateLimiter} checks the caller's token bucket
 * and returns this result (Doc 09 §12.5). If {@link #allowed()} is {@code false}, the
 * REST API returns {@code 429 Too Many Requests} with a {@code Retry-After} header
 * set to {@link #retryAfterSeconds()}.</p>
 *
 * <p>A boolean alone cannot convey the retry delay, which is why the rate limiter
 * returns this record instead of a simple boolean. The {@code retryAfterSeconds}
 * value is meaningful only when {@code allowed} is {@code false}.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param allowed           {@code true} if the request should proceed,
 *                          {@code false} if the rate limit is exceeded
 * @param retryAfterSeconds seconds until the rate limit resets; meaningful only when
 *                          {@code allowed} is {@code false}
 *
 * @see RateLimiter
 * @see ProblemType#RATE_LIMITED
 * @see <a href="Doc 09 §12.5">Rate Limiting</a>
 */
public record RateLimitResult(boolean allowed, long retryAfterSeconds) {
}
