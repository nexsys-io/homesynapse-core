/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.config.ScopeKeyManager;
import com.homesynapse.config.SecretStore;
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

    private static final byte[] SEED = {
        0x0F, 0x1E, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78,
        (byte) 0x87, (byte) 0x96, (byte) 0xA5, (byte) 0xB4,
        (byte) 0xC3, (byte) 0xD2, (byte) 0xE1, (byte) 0xF0
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
    @DisplayName("M9.6-SEED: the TCLK seed round-trips through the data-dir "
            + "SecretStore under the fixed zigbee.tclk_seed name — never plaintext")
    void tclkSeed_roundTrip() throws Exception {
        store.saveTclkSeed(SEED);

        assertThat(store.loadTclkSeed()).contains(SEED);
        // A fresh instance over the same directory recovers the seed (the
        // encrypted-at-rest custody survives the process).
        assertThat(new PersistentNetworkParameterStore(tempDir, clock)
                .loadTclkSeed()).contains(SEED);
        // The fixed secret name, pinned against the independent literal AND
        // read back through an INDEPENDENT SecretStore over the same custody
        // dir — a corrupted constant cannot self-confirm.
        assertThat(PersistentNetworkParameterStore.TCLK_SEED_REF)
                .isEqualTo("zigbee.tclk_seed");
        SecretStore independent = SecretStore.create(tempDir,
                ScopeKeyManager.create(tempDir, clock), clock);
        assertThat(independent.list()).contains("zigbee.tclk_seed");
        assertThat(independent.resolve("zigbee.tclk_seed"))
                .as("hex-encoded like the network key")
                .isEqualTo(HexFormat.of().formatHex(SEED));
        // INV-SE-03: the seed hex appears in NO plaintext file (both casings).
        String seedHexLower = HexFormat.of().formatHex(SEED);
        String seedHexUpper = seedHexLower.toUpperCase(java.util.Locale.ROOT);
        try (var files = Files.walk(tempDir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(file),
                        StandardCharsets.ISO_8859_1);
                assertThat(content)
                        .as("no plaintext seed hex in %s (both casings — H5)", file)
                        .doesNotContain(seedHexLower)
                        .doesNotContain(seedHexUpper);
            }
        }
    }

    @Test
    @DisplayName("M9.6-SEED DP-2: pre-seed custody (params + key, NO seed secret) "
            + "reads an EMPTY seed — presence-as-marker: the well-known posture")
    void preSeedCustody_seedLoadsEmpty() {
        store.save(new NetworkParameters(
                15, 0x1A62, 0x00124B0012345678L, NetworkFormation.NETWORK_KEY_REF));
        store.saveNetworkKey(NetworkFormation.NETWORK_KEY_REF, KEY);

        assertThat(store.loadTclkSeed()).isEmpty();
        assertThat(new PersistentNetworkParameterStore(tempDir, clock)
                .loadTclkSeed()).isEmpty();
    }

    @Test
    @DisplayName("M9.6-SEED DP-4: the atomic key+seed save persists BOTH halves "
            + "(the AMD-68 setAll ride)")
    void atomicKeyAndSeedSave_persistsBoth() {
        store.saveNetworkKeyAndTclkSeed(
                NetworkFormation.NETWORK_KEY_REF, KEY, SEED);

        PersistentNetworkParameterStore fresh =
                new PersistentNetworkParameterStore(tempDir, clock);
        assertThat(fresh.loadNetworkKey(NetworkFormation.NETWORK_KEY_REF))
                .contains(KEY);
        assertThat(fresh.loadTclkSeed()).contains(SEED);
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
