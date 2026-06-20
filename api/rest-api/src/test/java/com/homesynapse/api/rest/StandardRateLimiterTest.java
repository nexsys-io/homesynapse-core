/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StandardRateLimiter} (AB-1, Doc 09 §9/§12.5). A
 * deterministic, advanceable clock drives the refill assertions
 * (NO_DIRECT_TIME_ACCESS — no {@code Instant.now()}/{@code System.nanoTime()}).
 */
@DisplayName("StandardRateLimiter -- per-key token bucket")
final class StandardRateLimiterTest {

    /** Test clock advanced explicitly — no ambient time access. */
    private static final class TestClock extends Clock {
        private volatile Instant instant;

        TestClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration by) {
            this.instant = this.instant.plus(by);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    StandardRateLimiterTest() {
    }

    @Test
    @DisplayName("allows up to the burst size, then denies with a positive retry-after")
    void allowsBurstThenDenies() {
        TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        // 60 rpm (1 token/s), burst 3 — a fresh bucket starts full.
        StandardRateLimiter limiter = new StandardRateLimiter(clock, 60, 3);

        assertThat(limiter.check("key-a").allowed()).isTrue();
        assertThat(limiter.check("key-a").allowed()).isTrue();
        assertThat(limiter.check("key-a").allowed()).isTrue();

        RateLimitResult denied = limiter.check("key-a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("refills over time — a denied key is admitted again after the clock advances")
    void refillsOverTime() {
        TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        StandardRateLimiter limiter = new StandardRateLimiter(clock, 60, 1);

        assertThat(limiter.check("key-b").allowed()).isTrue();
        assertThat(limiter.check("key-b").allowed()).isFalse();

        // 1 token/s — advancing one second refills exactly one token.
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.check("key-b").allowed()).isTrue();
    }

    @Test
    @DisplayName("buckets are per-key — one exhausted key does not throttle another")
    void bucketsArePerKey() {
        TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        StandardRateLimiter limiter = new StandardRateLimiter(clock, 60, 1);

        assertThat(limiter.check("key-c").allowed()).isTrue();
        assertThat(limiter.check("key-c").allowed()).isFalse();

        // A different key has its own full bucket.
        assertThat(limiter.check("key-d").allowed()).isTrue();
    }

    @Test
    @DisplayName("the default constructor uses Doc 09 §9 limits (300 rpm / burst 50)")
    void defaultsToDocLimits() {
        TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        StandardRateLimiter limiter = new StandardRateLimiter(clock);

        for (int i = 0; i < StandardRateLimiter.DEFAULT_BURST_SIZE; i++) {
            assertThat(limiter.check("key-e").allowed())
                    .as("request %d within burst", i).isTrue();
        }
        assertThat(limiter.check("key-e").allowed()).isFalse();
    }
}
