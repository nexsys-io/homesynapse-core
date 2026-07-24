/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IdempotencyCache} (DP-4 / AMD-08).
 *
 * <p>Pins the fixture-paired boundaries: the 24h TTL (23h59m hit / 24h01m
 * miss), LRU — not FIFO — eviction at 10,001 entries, and fingerprint
 * canonicalization (map-order-insensitive at every depth, value-sensitive).</p>
 */
@DisplayName("IdempotencyCache")
final class IdempotencyCacheTest {

    private static final Instant T0 = Instant.parse("2026-07-22T00:00:00Z");
    private static final String FINGERPRINT = "fp-1";

    IdempotencyCacheTest() {
    }

    @Test
    @DisplayName("TTL boundary pair: a 23h59m-old entry replays, a 24h01m-old entry misses")
    void ttlBoundaryPair() {
        MutableTestClock clock = new MutableTestClock(T0);
        IdempotencyCache cache = new IdempotencyCache(clock);
        cache.store("key", FINGERPRINT, entryAt(T0));

        clock.advance(Duration.ofHours(23).plusMinutes(59));
        IdempotencyCache.Result hit = cache.get("key", FINGERPRINT);

        assertThat(hit.outcome()).isEqualTo(IdempotencyCache.Outcome.REPLAY);
        assertThat(hit.entry()).isEqualTo(entryAt(T0));

        clock.advance(Duration.ofMinutes(2));
        IdempotencyCache.Result miss = cache.get("key", FINGERPRINT);

        assertThat(miss.outcome()).isEqualTo(IdempotencyCache.Outcome.MISS);
    }

    @Test
    @DisplayName("same key + different fingerprint is CONFLICT; same fingerprint REPLAYs")
    void fingerprintComparisonGatesReplay() {
        MutableTestClock clock = new MutableTestClock(T0);
        IdempotencyCache cache = new IdempotencyCache(clock);
        cache.store("key", "fp-a", entryAt(T0));

        assertThat(cache.get("key", "fp-b").outcome())
                .isEqualTo(IdempotencyCache.Outcome.CONFLICT);
        assertThat(cache.get("key", "fp-a").outcome())
                .isEqualTo(IdempotencyCache.Outcome.REPLAY);
    }

    @Test
    @DisplayName("eviction at 10,001 entries is LRU (a freshly-touched old entry survives; "
            + "the least-recently-used one is evicted)")
    void lruEvictionAtCapacityPlusOne() {
        MutableTestClock clock = new MutableTestClock(T0);
        IdempotencyCache cache = new IdempotencyCache(clock);
        for (int i = 0; i < IdempotencyCache.MAX_ENTRIES; i++) {
            cache.store("key-" + i, FINGERPRINT, entryAt(T0));
        }

        // Touch key-0 so it is no longer the least-recently-used entry.
        assertThat(cache.get("key-0", FINGERPRINT).outcome())
                .isEqualTo(IdempotencyCache.Outcome.REPLAY);

        cache.store("key-" + IdempotencyCache.MAX_ENTRIES, FINGERPRINT, entryAt(T0));

        assertThat(cache.get("key-1", FINGERPRINT).outcome())
                .as("least-recently-used entry evicted at 10,001")
                .isEqualTo(IdempotencyCache.Outcome.MISS);
        assertThat(cache.get("key-0", FINGERPRINT).outcome())
                .as("freshly-touched entry survives (LRU, not FIFO)")
                .isEqualTo(IdempotencyCache.Outcome.REPLAY);
        assertThat(cache.get("key-" + IdempotencyCache.MAX_ENTRIES, FINGERPRINT).outcome())
                .isEqualTo(IdempotencyCache.Outcome.REPLAY);
    }

    @Test
    @DisplayName("fingerprint is parameter-order-insensitive at every map depth")
    void fingerprintOrderInsensitive() {
        Map<String, Object> nestedA = new LinkedHashMap<>();
        nestedA.put("y", 2);
        nestedA.put("x", 3);
        Map<String, Object> paramsA = new LinkedHashMap<>();
        paramsA.put("a", 1);
        paramsA.put("b", nestedA);

        Map<String, Object> nestedB = new LinkedHashMap<>();
        nestedB.put("x", 3);
        nestedB.put("y", 2);
        Map<String, Object> paramsB = new LinkedHashMap<>();
        paramsB.put("b", nestedB);
        paramsB.put("a", 1);

        String fingerprintA =
                IdempotencyCache.fingerprint("entity", "on_off", "turn_on", paramsA);
        String fingerprintB =
                IdempotencyCache.fingerprint("entity", "on_off", "turn_on", paramsB);

        assertThat(fingerprintA).isNotBlank();
        assertThat(fingerprintB).isEqualTo(fingerprintA);
    }

    @Test
    @DisplayName("fingerprint changes when a parameter value changes")
    void fingerprintValueSensitive() {
        String base = IdempotencyCache.fingerprint(
                "entity", "on_off", "turn_on", Map.of("level", 1));
        String changedValue = IdempotencyCache.fingerprint(
                "entity", "on_off", "turn_on", Map.of("level", 2));

        assertThat(changedValue).isNotEqualTo(base);
    }

    @Test
    @DisplayName("fingerprint changes when the command changes")
    void fingerprintCommandSensitive() {
        String base = IdempotencyCache.fingerprint(
                "entity", "on_off", "turn_on", Map.of());
        String changedCommand = IdempotencyCache.fingerprint(
                "entity", "on_off", "turn_off", Map.of());

        assertThat(changedCommand).isNotEqualTo(base);
    }

    private static IdempotencyEntry entryAt(Instant createdAt) {
        return new IdempotencyEntry("key", "01HA000000000000000000CMD1",
                "01HA000000000000000000CMD1", 100L, createdAt);
    }
}
