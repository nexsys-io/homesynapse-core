/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the M6.3 counter-nonce payload path
 * ({@link ScopeKeyManager#encryptPayload}) and its OR-M6-NONCE durability
 * obligations (Doc 15 §3.4, §13.4) on {@link StandardScopeKeyManager}.
 *
 * <p>This class proves the <em>config-side</em> half of OR-M6-NONCE: the
 * per-{@code (scope, keyVersion)} counter is strictly monotonic, fsync-durable
 * ahead of return, and re-initializes from the persisted high-water mark on a
 * fresh manager (never from memory). The persistence write-path half (the
 * §13.4 kill-mid-encrypt close gate) is proven in the persistence module's
 * {@code AtRestEncryptionWritePathTest}.</p>
 *
 * <p>Clock is injected as a fixed instant (the {@code NO_DIRECT_TIME_ACCESS}
 * convention is self-enforced in this module's test sources — the ArchUnit
 * rule's enforcement reaches only {@code com.homesynapse.app}'s test
 * classpath).</p>
 */
@DisplayName("ScopeKeyManager.encryptPayload — counter nonces (OR-M6-NONCE)")
class ScopeKeyManagerPayloadNonceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-13T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path configDir;

    /** Creates a new test instance. */
    ScopeKeyManagerPayloadNonceTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private ScopeKeyManager manager() {
        return ScopeKeyManager.create(configDir, FIXED_CLOCK);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes the 96-bit counter nonce back to its {@code long} counter value
     * (big-endian in the trailing 8 bytes — the DP-C construction).
     */
    private static long counterOf(byte[] nonce) {
        assertThat(nonce).hasSize(12);
        return ByteBuffer.wrap(nonce).getLong(nonce.length - Long.BYTES);
    }

    @Test
    @DisplayName("nonces are strictly monotonic with no repeats within a scope")
    void encryptPayload_noncesStrictlyMonotonic() {
        ScopeKeyManager manager = manager();
        List<Long> counters = new ArrayList<>();
        Set<String> seenIvs = new HashSet<>();

        for (int i = 0; i < 64; i++) {
            ScopeCipherResult result =
                    manager.encryptPayload("identity", bytes("event " + i));
            counters.add(counterOf(result.iv()));
            // Distinct nonce bytes for every encryption under this DEK.
            assertThat(seenIvs.add(java.util.Arrays.toString(result.iv())))
                    .as("nonce %d must be unique", i)
                    .isTrue();
        }

        assertThat(counters).startsWith(1L);  // fresh scope begins at 1
        for (int i = 1; i < counters.size(); i++) {
            assertThat(counters.get(i))
                    .as("counter must strictly increase")
                    .isGreaterThan(counters.get(i - 1));
        }
    }

    @Test
    @DisplayName("a fresh manager re-inits from the persisted max, never from memory")
    void encryptPayload_reInitFromPersistedMaxOnRestart() {
        ScopeKeyManager first = manager();
        long last = 0L;
        for (int i = 0; i < 5; i++) {
            last = counterOf(first.encryptPayload("presence_personal",
                    bytes("before restart " + i)).iv());
        }

        // Drop all in-memory state: a brand-new manager over the same dir.
        long resumed = counterOf(manager().encryptPayload("presence_personal",
                bytes("after restart")).iv());

        assertThat(resumed)
                .as("must resume strictly above the persisted high-water mark")
                .isGreaterThan(last)
                .isEqualTo(last + 1);
    }

    @Test
    @DisplayName("the high-water mark is durable ahead of return (gap allowed,"
            + " reuse forbidden)")
    void encryptPayload_counterDurableAheadOfReturn() throws IOException {
        long returned = counterOf(
                manager().encryptPayload("identity", bytes("one")).iv());

        // The high-water mark for (identity, 1) is fsync-durable when the call
        // returned, so it is at least the value just handed back. A crash now
        // (before any persistence INSERT consumed the nonce) leaves a gap, not
        // a reuse.
        assertThat(persistedHighWater("identity", 1))
                .isGreaterThanOrEqualTo(returned);

        // Simulate exactly that crash: a fresh manager must never reissue the
        // returned value.
        long next = counterOf(
                manager().encryptPayload("identity", bytes("two")).iv());
        assertThat(next).isGreaterThan(returned);
    }

    @Test
    @DisplayName("per-scope counters are independent and each monotonic")
    void encryptPayload_perScopeIndependentCounters() {
        ScopeKeyManager manager = manager();

        long a1 = counterOf(manager.encryptPayload("identity", bytes("a1")).iv());
        long b1 = counterOf(manager.encryptPayload("presence_personal", bytes("b1")).iv());
        long a2 = counterOf(manager.encryptPayload("identity", bytes("a2")).iv());
        long b2 = counterOf(manager.encryptPayload("presence_personal", bytes("b2")).iv());

        // Both scopes count from 1 independently — interleaving does not
        // bleed one scope's counter into the other.
        assertThat(a1).isEqualTo(1L);
        assertThat(b1).isEqualTo(1L);
        assertThat(a2).isEqualTo(2L);
        assertThat(b2).isEqualTo(2L);
    }

    @Test
    @DisplayName("the fence holds: encrypt stays random-IV; encryptPayload is the counter")
    void fence_randomIvPathUnchanged() {
        ScopeKeyManager manager = manager();

        // M6.2 secrets path: random IV — two calls differ unpredictably.
        ScopeCipherResult r1 = manager.encrypt("identity", bytes("secret one"));
        ScopeCipherResult r2 = manager.encrypt("identity", bytes("secret two"));
        assertThat(r1.iv()).isNotEqualTo(r2.iv());

        // M6.3 payload path: deterministic counter — strictly +1 each call,
        // on its own counter sequence, untouched by the random-IV calls above.
        long p1 = counterOf(manager.encryptPayload("identity", bytes("payload one")).iv());
        long p2 = counterOf(manager.encryptPayload("identity", bytes("payload two")).iv());
        assertThat(p1).isEqualTo(1L);
        assertThat(p2).isEqualTo(2L);
    }

    @Test
    @DisplayName("encryptPayload then decrypt round-trips the plaintext")
    void roundTrip_encryptPayloadThenDecrypt() {
        ScopeKeyManager manager = manager();
        byte[] plaintext = bytes("person-linked presence payload");

        ScopeCipherResult result = manager.encryptPayload("presence_personal", plaintext);
        byte[] decrypted = manager.decrypt("presence_personal", result.keyVersion(),
                result.ciphertext(), result.iv());

        assertThat(decrypted).isEqualTo(plaintext);
        assertThat(result.ciphertext()).isNotEqualTo(plaintext);
        assertThat(result.keyVersion()).isEqualTo(1);
    }

    /** Reads the persisted high-water mark for a scope/version from disk. */
    private long persistedHighWater(String scopeId, int keyVersion) throws IOException {
        Path file = configDir.resolve("scope_nonce_counters.json");
        JsonNode root = MAPPER.readTree(Files.readString(file));
        for (JsonNode row : root) {
            if (row.get("scopeId").asText().equals(scopeId)
                    && row.get("keyVersion").asInt() == keyVersion) {
                return row.get("highWater").asLong();
            }
        }
        throw new AssertionError(
                "no persisted counter row for " + scopeId + ":" + keyVersion);
    }
}
