/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ScopeKeyManager} / {@link StandardScopeKeyManager}
 * (Doc 15 §3.5/§4.2, M6.2).
 *
 * <p>Covers the DP-1/DP-2 key hierarchy end to end: lazy root-key and DEK
 * creation (INV-CE-02), per-scope round-trips, scope isolation (a foreign
 * scope's key fails GCM authentication), key-version persistence across
 * manager instances, the Doc 15 §8.2 contract-freeze unreadability
 * round-trip (encrypt → destroy the key by direct store manipulation →
 * decrypt refuses), and the {@code 0400} root-key permission row
 * ({@code Assumptions}-guarded on non-POSIX filesystems, the
 * {@code ConfigLayoutTest} precedent).</p>
 */
@DisplayName("ScopeKeyManager (Doc 15 §3.5/§4.2, M6.2)")
class ScopeKeyManagerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path configDir;

    /** Creates a new test instance. */
    ScopeKeyManagerTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private ScopeKeyManager manager() {
        return ScopeKeyManager.create(configDir, FIXED_CLOCK);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ──────────────────────────────────────────────────────────────────
    // Round-trip + scope isolation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("encrypt/decrypt")
    class RoundTripTests {

        /** Creates a new test instance. */
        RoundTripTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("round-trips bytes within a scope")
        void roundTripsWithinScope() {
            ScopeKeyManager manager = manager();
            byte[] plaintext = bytes("the quick brown fox");

            ScopeCipherResult result = manager.encrypt("alpha", plaintext);
            byte[] decrypted = manager.decrypt("alpha", result.keyVersion(),
                    result.ciphertext(), result.iv());

            assertThat(decrypted).isEqualTo(plaintext);
            assertThat(result.ciphertext()).isNotEqualTo(plaintext);
            assertThat(result.keyVersion()).isEqualTo(1);
            assertThat(result.iv()).hasSize(12);
        }

        @Test
        @DisplayName("two encryptions never reuse an IV")
        void freshIvPerEncryption() {
            ScopeKeyManager manager = manager();

            ScopeCipherResult first = manager.encrypt("alpha", bytes("one"));
            ScopeCipherResult second = manager.encrypt("alpha", bytes("two"));

            assertThat(first.iv()).isNotEqualTo(second.iv());
        }

        @Test
        @DisplayName("two scopes have different keys: cross-scope decrypt fails"
                + " GCM authentication")
        void crossScopeDecryptFailsAuthentication() {
            ScopeKeyManager manager = manager();
            ScopeCipherResult alpha = manager.encrypt("alpha", bytes("alpha data"));
            // Materialize beta's own version-1 DEK so the decrypt reaches
            // GCM authentication rather than the absent-key guard.
            manager.encrypt("beta", bytes("beta data"));

            assertThatThrownBy(() -> manager.decrypt("beta", alpha.keyVersion(),
                    alpha.ciphertext(), alpha.iv()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("decryption failed");
        }

        @Test
        @DisplayName("blank or null scopeId is rejected")
        void invalidScopeIdRejected() {
            ScopeKeyManager manager = manager();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> manager.encrypt(" ", bytes("x")));
            assertThatThrownBy(() -> manager.encrypt(null, bytes("x")))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Lazy creation (INV-CE-02) + durability
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("key lifecycle")
    class LifecycleTests {

        /** Creates a new test instance. */
        LifecycleTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("construction touches no files; first encrypt creates the"
                + " root key and the store (INV-CE-02)")
        void lazyCreationOnFirstEncrypt() {
            ScopeKeyManager manager = manager();

            assertThat(configDir.resolve(".root-key")).doesNotExist();
            assertThat(configDir.resolve("scope_keys.json")).doesNotExist();

            manager.encrypt("alpha", bytes("first use"));

            assertThat(configDir.resolve(".root-key")).exists();
            assertThat(configDir.resolve("scope_keys.json")).exists();
        }

        @Test
        @DisplayName("a fresh manager over the same directory decrypts prior"
                + " ciphertext (durability)")
        void freshManagerDecryptsPriorCiphertext() {
            byte[] plaintext = bytes("survives the restart");
            ScopeCipherResult result = manager().encrypt("alpha", plaintext);

            byte[] decrypted = manager().decrypt("alpha", result.keyVersion(),
                    result.ciphertext(), result.iv());

            assertThat(decrypted).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("keyVersion is persisted in scope_keys.json and honored on"
                + " decrypt")
        void keyVersionPersistedAndHonored() throws IOException {
            ScopeCipherResult result = manager().encrypt("alpha", bytes("data"));

            JsonNode store = MAPPER.readTree(
                    Files.readString(configDir.resolve("scope_keys.json")));
            assertThat(store.isArray()).isTrue();
            assertThat(store).hasSize(1);
            JsonNode row = store.get(0);
            assertThat(row.get("scopeId").asText()).isEqualTo("alpha");
            assertThat(row.get("keyVersion").asInt())
                    .isEqualTo(result.keyVersion()).isEqualTo(1);
            assertThat(row.get("createdAt").asText())
                    .isEqualTo("2026-06-11T00:00:00Z");
            assertThat(row.get("destroyedAt").isNull()).isTrue();
            // The wrapped DEK is ciphertext — the plaintext DEK never
            // appears on disk (DP-3).
            assertThat(row.get("encryptedDek").asText()).isNotBlank();
        }

        @Test
        @DisplayName("a corrupt root key file fails closed, naming the file")
        void corruptRootKeyFailsClosed() throws IOException {
            Files.write(configDir.resolve(".root-key"), new byte[10]);

            assertThatThrownBy(() -> manager().encrypt("alpha", bytes("x")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(".root-key");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Unreadability (Doc 15 §8.2 contract-freeze gate)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unreadability")
    class UnreadabilityTests {

        /** Creates a new test instance. */
        UnreadabilityTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("decrypt of an absent (scope, version) throws")
        void absentScopeVersionThrows() {
            ScopeKeyManager manager = manager();
            ScopeCipherResult result = manager.encrypt("alpha", bytes("data"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> manager.decrypt("alpha", 99,
                            result.ciphertext(), result.iv()))
                    .withMessageContaining("no key exists");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> manager.decrypt("ghost", 1,
                            result.ciphertext(), result.iv()))
                    .withMessageContaining("no key exists");
        }

        @Test
        @DisplayName("encrypt → destroy the scope key → decrypt refuses: the"
                + " ciphertext is permanently unreadable (freeze-gate)")
        void destroyedScopeKeyIsUnreadable() throws IOException {
            ScopeCipherResult result = manager().encrypt("alpha", bytes("data"));

            // The shred OPERATION is post-MVP — destruction is simulated by
            // direct store manipulation (Doc 15 §8.2), exactly what the
            // operation will do: set destroyedAt on the row.
            Path storeFile = configDir.resolve("scope_keys.json");
            JsonNode store = MAPPER.readTree(Files.readString(storeFile));
            ((ObjectNode) store.get(0)).put("destroyedAt", "2026-06-11T01:00:00Z");
            Files.writeString(storeFile, MAPPER.writeValueAsString(store));

            // Fresh manager: no cached DEK survives the "shred".
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> manager().decrypt("alpha",
                            result.keyVersion(), result.ciphertext(), result.iv()))
                    .withMessageContaining("destroyed")
                    .withMessageContaining("unreadable");
        }

        @Test
        @DisplayName("a destroyed version is never reissued: the next DEK"
                + " continues the sequence (dek_ref stability)")
        void destroyedVersionIsNeverReissued() throws IOException {
            ScopeCipherResult v1 = manager().encrypt("alpha", bytes("v1 data"));

            Path storeFile = configDir.resolve("scope_keys.json");
            JsonNode store = MAPPER.readTree(Files.readString(storeFile));
            ((ObjectNode) store.get(0)).put("destroyedAt", "2026-06-11T01:00:00Z");
            Files.writeString(storeFile, MAPPER.writeValueAsString(store));

            // A re-keyed scope continues PAST the destroyed version — were
            // version 1 reissued under a new DEK, an old dek_ref would
            // silently resolve to the wrong key.
            ScopeKeyManager fresh = manager();
            ScopeCipherResult v2 = fresh.encrypt("alpha", bytes("v2 data"));

            assertThat(v2.keyVersion()).isEqualTo(2);
            assertThat(fresh.decrypt("alpha", 2, v2.ciphertext(), v2.iv()))
                    .isEqualTo(bytes("v2 data"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> fresh.decrypt("alpha", 1,
                            v1.ciphertext(), v1.iv()))
                    .withMessageContaining("destroyed");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Root-key permissions (DP-1)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(".root-key is created 0400 (owner read only) on POSIX"
            + " filesystems")
    void rootKeyPermissionsAre0400() throws IOException {
        Assumptions.assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews()
                        .contains("posix"),
                "POSIX file permissions are not supported in this environment");

        manager().encrypt("alpha", bytes("data"));

        Set<PosixFilePermission> permissions =
                Files.getPosixFilePermissions(configDir.resolve(".root-key"));
        assertThat(permissions)
                .containsExactly(PosixFilePermission.OWNER_READ);
    }
}
