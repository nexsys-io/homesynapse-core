/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SecretStore} / {@link StandardSecretStore} (Doc 06
 * §3.4/§4.8, Doc 15 §7.3, AMD-68 §5 + the REC-140 folds, M6.2).
 *
 * <p>The AMD-68 rows: {@code setAllPersistsAllEntries},
 * {@code setAllIsAllOrNothingOnFailure} (the torn-pair guard),
 * {@code setAllDurableBeforeReturn} (the REC-140 crash-frame/restart
 * simulation — a FRESH store over the same files reads everything),
 * {@code setAllRejectsEmpty}, {@code setDelegatesToSetAll}. Plus the
 * AMD-16/DP-9 backup rotation (the 6th post-seed mutation deletes
 * {@code bak.1}; backups restorable by direct decrypt), the
 * {@code resolve}-missing contract (MODULE_CONTEXT decision #7 pin:
 * {@code IllegalArgumentException}, no new exception type), and the
 * INV-CE-02 zero-touch read path.</p>
 *
 * <p>Write failures are injected without a test seam: the atomic write
 * targets the deterministic sibling {@code secrets.enc.tmp}, so
 * pre-creating a DIRECTORY at that path fails the temp-file open
 * mid-operation, before the rename — exactly the torn window AMD-68
 * §2.2 guards.</p>
 */
@DisplayName("SecretStore (Doc 06 §3.4, AMD-68, M6.2)")
class SecretStoreTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path configDir;

    /** Creates a new test instance. */
    SecretStoreTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private SecretStore store() {
        return store(FIXED_CLOCK);
    }

    private SecretStore store(Clock clock) {
        return SecretStore.create(configDir,
                ScopeKeyManager.create(configDir, clock), clock);
    }

    private Path secretsFile() {
        return configDir.resolve("secrets.enc");
    }

    /** Decrypts an on-disk envelope the way the store does — used to verify
     * backups and timestamps without going through the store under test. */
    private JsonNode decryptEnvelope(Path file) throws IOException {
        JsonNode envelope = MAPPER.readTree(Files.readString(file));
        byte[] plaintext = ScopeKeyManager.create(configDir, FIXED_CLOCK).decrypt(
                StandardSecretStore.SECRETS_SCOPE_ID,
                envelope.get("keyVersion").asInt(),
                Base64.getDecoder().decode(envelope.get("ciphertext").asText()),
                Base64.getDecoder().decode(envelope.get("iv").asText()));
        return MAPPER.readTree(new String(plaintext, StandardCharsets.UTF_8));
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-68 §5 rows
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setAll (AMD-68)")
    class SetAllTests {

        /** Creates a new test instance. */
        SetAllTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("setAllPersistsAllEntries: every entry resolves after return")
        void setAllPersistsAllEntries() {
            SecretStore store = store();

            store.setAll(Map.of(
                    "oauth_access_token", "access-123",
                    "oauth_refresh_token", "refresh-456"));

            assertThat(store.resolve("oauth_access_token")).isEqualTo("access-123");
            assertThat(store.resolve("oauth_refresh_token")).isEqualTo("refresh-456");
        }

        @Test
        @DisplayName("setAllIsAllOrNothingOnFailure: a mid-operation write"
                + " failure leaves the store exactly as before (torn-pair guard)")
        void setAllIsAllOrNothingOnFailure() throws IOException {
            SecretStore store = store();
            store.setAll(Map.of("oauth_access_token", "old-access",
                    "oauth_refresh_token", "old-refresh"));

            // Block the atomic write's temp file: the rewrite fails before
            // the rename, so the prior file must remain byte-identical.
            // (The writer's own cleanup may delete the empty blocker
            // directory — deleteIfExists tolerates either outcome.)
            Path tmpBlocker = configDir.resolve("secrets.enc.tmp");
            Files.createDirectory(tmpBlocker);
            try {
                assertThatThrownBy(() -> store.setAll(Map.of(
                        "oauth_access_token", "new-access",
                        "oauth_refresh_token", "new-refresh")))
                        .isInstanceOf(UncheckedIOException.class);
            } finally {
                Files.deleteIfExists(tmpBlocker);
            }

            // The pair is NOT torn: both entries still read the old values.
            assertThat(store.resolve("oauth_access_token")).isEqualTo("old-access");
            assertThat(store.resolve("oauth_refresh_token")).isEqualTo("old-refresh");
        }

        @Test
        @DisplayName("setAllDurableBeforeReturn: a fresh store over the same"
                + " files reads all entries (REC-140 restart simulation)")
        void setAllDurableBeforeReturn() {
            store().setAll(Map.of(
                    "mqtt_password", "hunter2",
                    "api_token", "tok-789"));

            // Fresh store AND fresh key manager — nothing in-memory
            // survives; everything must come off disk.
            SecretStore restarted = store();
            assertThat(restarted.resolve("mqtt_password")).isEqualTo("hunter2");
            assertThat(restarted.resolve("api_token")).isEqualTo("tok-789");
        }

        @Test
        @DisplayName("setAllRejectsEmpty: empty map → IllegalArgumentException")
        void setAllRejectsEmpty() {
            SecretStore store = store();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> store.setAll(Map.of()));
            assertThat(secretsFile()).doesNotExist();
        }

        @Test
        @DisplayName("setDelegatesToSetAll: single-key set shares the atomic,"
                + " durable path")
        void setDelegatesToSetAll() throws IOException {
            SecretStore store = store();
            store.set("zigbee_network_key", "0xDEADBEEF");

            // Durable like setAll: a fresh store reads it.
            assertThat(store().resolve("zigbee_network_key"))
                    .isEqualTo("0xDEADBEEF");

            // All-or-nothing like setAll: the same injected failure leaves
            // the store unchanged.
            Path tmpBlocker = configDir.resolve("secrets.enc.tmp");
            Files.createDirectory(tmpBlocker);
            try {
                assertThatThrownBy(() -> store.set("zigbee_network_key", "torn"))
                        .isInstanceOf(UncheckedIOException.class);
            } finally {
                Files.deleteIfExists(tmpBlocker);
            }
            assertThat(store.resolve("zigbee_network_key"))
                    .isEqualTo("0xDEADBEEF");
        }

        @Test
        @DisplayName("updating an entry preserves createdAt and refreshes"
                + " updatedAt")
        void updatePreservesCreatedAt() throws IOException {
            Clock later = Clock.fixed(
                    Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC);
            store(FIXED_CLOCK).set("api_token", "v1");
            store(later).set("api_token", "v2");

            JsonNode storeDocument = decryptEnvelope(secretsFile());
            JsonNode entry = storeDocument.get("entries").get(0);
            assertThat(entry.get("createdAt").asText())
                    .isEqualTo("2026-06-11T00:00:00Z");
            assertThat(entry.get("updatedAt").asText())
                    .isEqualTo("2026-06-12T00:00:00Z");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-16 / DP-9 backup rotation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("backup rotation (AMD-16/DP-9)")
    class BackupRotationTests {

        /** Creates a new test instance. */
        BackupRotationTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("the first-ever write takes no backup")
        void firstWriteTakesNoBackup() {
            store().set("first", "value");

            assertThat(configDir.resolve("secrets.enc.bak.1")).doesNotExist();
        }

        @Test
        @DisplayName("the 6th post-seed mutation creates bak.6 and deletes"
                + " bak.1 (max 5 retained, N monotonic)")
        void sixthMutationDeletesOldestBackup() {
            SecretStore store = store();
            store.set("seed", "0");                       // creates the file
            for (int i = 1; i <= 5; i++) {
                store.set("key" + i, "value" + i);        // bak.1 .. bak.5
            }
            assertThat(configDir.resolve("secrets.enc.bak.1")).exists();
            assertThat(configDir.resolve("secrets.enc.bak.5")).exists();

            store.set("key6", "value6");                  // bak.6, prunes bak.1

            assertThat(configDir.resolve("secrets.enc.bak.1")).doesNotExist();
            assertThat(configDir.resolve("secrets.enc.bak.2")).exists();
            assertThat(configDir.resolve("secrets.enc.bak.6")).exists();
        }

        @Test
        @DisplayName("backups are restorable by direct decrypt under their"
                + " recorded key version")
        void backupsAreRestorableByDirectDecrypt() throws IOException {
            SecretStore store = store();
            store.set("api_token", "snapshot-me");
            store.set("api_token", "newer-value");        // creates bak.1

            JsonNode snapshot = decryptEnvelope(
                    configDir.resolve("secrets.enc.bak.1"));

            assertThat(snapshot.get("version").asInt()).isEqualTo(1);
            JsonNode entry = snapshot.get("entries").get(0);
            assertThat(entry.get("key").asText()).isEqualTo("api_token");
            assertThat(entry.get("value").asText()).isEqualTo("snapshot-me");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Read surface
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolve/list/remove")
    class ReadAndRemoveTests {

        /** Creates a new test instance. */
        ReadAndRemoveTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("resolve of a missing key throws IllegalArgumentException"
                + " naming the key, never any value (LTD-15)")
        void resolveMissingThrows() {
            SecretStore store = store();
            store.set("present", "SUPER-SECRET-VALUE");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> store.resolve("absent"))
                    .withMessageContaining("absent")
                    .satisfies(e -> assertThat(e.getMessage())
                            .doesNotContain("SUPER-SECRET-VALUE"));
        }

        @Test
        @DisplayName("resolve against an absent store throws without creating"
                + " any file (INV-CE-02)")
        void resolveAgainstAbsentStoreThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> store().resolve("anything"));

            assertThat(secretsFile()).doesNotExist();
            assertThat(configDir.resolve(".root-key")).doesNotExist();
            assertThat(configDir.resolve("scope_keys.json")).doesNotExist();
        }

        @Test
        @DisplayName("list returns names only, unmodifiable; empty for an"
                + " absent store")
        void listReturnsNamesOnly() {
            SecretStore store = store();
            assertThat(store.list()).isEmpty();

            store.setAll(Map.of("alpha_key", "alpha-value",
                    "beta_key", "beta-value"));

            assertThat(store.list())
                    .containsExactlyInAnyOrder("alpha_key", "beta_key");
            assertThatThrownBy(() -> store.list().add("gamma"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("remove deletes the entry durably; removing a missing key"
                + " throws")
        void removeDeletesDurably() {
            SecretStore store = store();
            store.setAll(Map.of("keep", "k", "drop", "d"));

            store.remove("drop");

            assertThat(store().list()).containsExactly("keep");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> store.remove("drop"));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // On-disk form (DP-7)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("secrets.enc is an envelope of keyVersion/iv/ciphertext;"
            + " no plaintext value appears on disk (INV-SE-03)")
    void onDiskFormIsEncryptedEnvelope() throws IOException {
        store().set("mqtt_password", "plaintext-marker");

        String envelope = Files.readString(secretsFile());

        assertThat(envelope).doesNotContain("plaintext-marker");
        JsonNode node = MAPPER.readTree(envelope);
        assertThat(node.get("keyVersion").asInt()).isEqualTo(1);
        assertThat(node.has("iv")).isTrue();
        assertThat(node.has("ciphertext")).isTrue();
    }
}
