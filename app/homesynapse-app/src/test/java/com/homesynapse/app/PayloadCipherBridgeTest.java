/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.persistence.EncryptedPayload;
import com.homesynapse.persistence.PayloadCipher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip tests for the M6.2 E2 bridge (Doc 15 §3.8 / CARRY 1): the
 * composition-root adapter built by {@link Main} over the config-resident
 * {@code ScopeKeyManager}, surfaced as the persistence-defined
 * {@link PayloadCipher} seam.
 *
 * <p>This is the charter done-when: the bridge actually works — bytes
 * encrypted through the adapter decrypt back through it, across adapter
 * instances (the key material is durable), with the scope-isolation
 * property intact. Only {@code com.homesynapse.app} can host this test:
 * it is the single module that reads both {@code config} and
 * {@code persistence} (zero-new-edge property).</p>
 */
@DisplayName("PayloadCipher bridge (app composition root, Doc 15 §3.8)")
class PayloadCipherBridgeTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path configDir;

    /** Creates a new test instance. */
    PayloadCipherBridgeTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Test
    @DisplayName("encrypt → EncryptedPayload → decrypt round-trips bytes"
            + " through the app adapter")
    void roundTripsBytesThroughTheAdapter() {
        PayloadCipher cipher = Main.payloadCipher(configDir, FIXED_CLOCK);
        byte[] plaintext =
                "presence_signal payload".getBytes(StandardCharsets.UTF_8);

        EncryptedPayload payload = cipher.encrypt("presence_personal", plaintext);

        assertThat(payload.keyVersion()).isEqualTo(1);
        assertThat(payload.iv()).hasSize(12);
        assertThat(payload.ciphertext()).isNotEqualTo(plaintext);

        byte[] decrypted = cipher.decrypt("presence_personal",
                payload.keyVersion(), payload.ciphertext(), payload.iv());
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("a fresh adapter over the same config dir decrypts prior"
            + " ciphertext (the store leg of encrypt→(store)→decrypt)")
    void freshAdapterDecryptsPriorCiphertext() {
        byte[] plaintext = "stored then read back".getBytes(StandardCharsets.UTF_8);
        EncryptedPayload payload = Main.payloadCipher(configDir, FIXED_CLOCK)
                .encrypt("identity", plaintext);

        byte[] decrypted = Main.payloadCipher(configDir, FIXED_CLOCK)
                .decrypt("identity", payload.keyVersion(),
                        payload.ciphertext(), payload.iv());

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("scope isolation survives the bridge: a foreign scope fails"
            + " authentication")
    void scopeIsolationSurvivesTheBridge() {
        PayloadCipher cipher = Main.payloadCipher(configDir, FIXED_CLOCK);
        EncryptedPayload payload = cipher.encrypt("identity",
                "person-linked".getBytes(StandardCharsets.UTF_8));
        cipher.encrypt("presence_personal",
                "other scope".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> cipher.decrypt("presence_personal",
                payload.keyVersion(), payload.ciphertext(), payload.iv()))
                .isInstanceOf(IllegalStateException.class);
    }
}
