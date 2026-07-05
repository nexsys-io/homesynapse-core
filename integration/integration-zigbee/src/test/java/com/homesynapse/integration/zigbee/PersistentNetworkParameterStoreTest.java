/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PersistentNetworkParameterStore} (M9.4b §5.5) — the durable custody
 * split: non-secret parameters as JSON; key material ONLY through the
 * data-dir-rooted {@code SecretStore} (INV-SE-03: never in the JSON, logs, or
 * exception text). Corrupt custody surfaces loudly — never a silent re-form.
 */
@DisplayName("PersistentNetworkParameterStore — durable params + SecretStore key custody (M9.4b §5.5)")
class PersistentNetworkParameterStoreTest {

    private static final byte[] KEY = {
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB,
        (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF
    };

    @TempDir
    Path tempDir;

    private TestClock clock;
    private PersistentNetworkParameterStore store;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        store = new PersistentNetworkParameterStore(tempDir, clock);
    }

    @Test
    @DisplayName("first run: no parameters file reads as empty (form fresh)")
    void firstRun_loadsEmpty() {
        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("parameters round-trip through the JSON file")
    void parameters_roundTrip() {
        NetworkParameters params = new NetworkParameters(
                15, 0x1A62, 0x00124B0012345678L, NetworkFormation.NETWORK_KEY_REF);

        store.save(params);

        assertThat(store.load()).contains(params);
        assertThat(tempDir.resolve(
                PersistentNetworkParameterStore.PARAMETERS_FILE_NAME)).exists();
        // A second store over the SAME directory reads the same truth — the
        // second-boot resume substrate (§7.6).
        assertThat(new PersistentNetworkParameterStore(tempDir, clock).load())
                .contains(params);
    }

    @Test
    @DisplayName("key custody round-trips through the data-dir SecretStore")
    void keyCustody_roundTrip() {
        store.saveNetworkKey(NetworkFormation.NETWORK_KEY_REF, KEY);

        assertThat(store.loadNetworkKey(NetworkFormation.NETWORK_KEY_REF))
                .contains(KEY);
        // A fresh instance over the same directory recovers the key (the
        // encrypted-at-rest custody survives the process).
        assertThat(new PersistentNetworkParameterStore(tempDir, clock)
                .loadNetworkKey(NetworkFormation.NETWORK_KEY_REF)).contains(KEY);
    }

    @Test
    @DisplayName("a missing key reads as empty — the resume path's orElseThrow owns the verdict")
    void missingKey_loadsEmpty() {
        assertThat(store.loadNetworkKey("zigbee.network_key")).isEmpty();
    }

    @Test
    @DisplayName("INV-SE-03: the key hex appears in NO plaintext file the store writes")
    void keyMaterial_neverOnDiskInPlaintext() throws Exception {
        NetworkParameters params = new NetworkParameters(
                15, 0x1A62, 0x00124B0012345678L, NetworkFormation.NETWORK_KEY_REF);
        store.save(params);
        store.saveNetworkKey(NetworkFormation.NETWORK_KEY_REF, KEY);

        String keyHexLower = HexFormat.of().formatHex(KEY);
        String keyHexUpper = keyHexLower.toUpperCase(java.util.Locale.ROOT);
        try (var files = Files.walk(tempDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(file),
                        StandardCharsets.ISO_8859_1);
                assertThat(content)
                        .as("no plaintext key hex in %s (both casings — H5)", file)
                        .doesNotContain(keyHexLower)
                        .doesNotContain(keyHexUpper);
            }
        }
    }

    @Test
    @DisplayName("a corrupt parameters file throws naming the FILE — never a silent re-form, "
            + "never key material in the message")
    void corruptParameters_throwsLoudly() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(
                PersistentNetworkParameterStore.PARAMETERS_FILE_NAME), "not-json{{{");
        store.saveNetworkKey(NetworkFormation.NETWORK_KEY_REF, KEY);

        assertThatThrownBy(() -> store.load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        PersistentNetworkParameterStore.PARAMETERS_FILE_NAME)
                .hasMessageContaining("refusing to re-form")
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain(HexFormat.of().formatHex(KEY)));
    }
}
