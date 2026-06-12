/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * {@link SecretStore} implementation (Doc 06 §3.4/§4.8, Doc 15 §7.3,
 * AMD-68, M6.2).
 *
 * <h2>Shared-root unification (Doc 15 §7.3)</h2>
 *
 * <p>{@code secrets.enc} is encrypted under the {@code "config_secrets"}
 * scope DEK via the injected {@link ScopeKeyManager} — the secret store is
 * one scope in the key hierarchy, NOT a second key system. The legacy
 * single-static-{@code .secret-key} design is superseded before first
 * release; no migration path exists because no production secrets exist
 * (DP-7).</p>
 *
 * <h2>On-disk form (DP-7, documented Coder choice)</h2>
 *
 * <p>{@code secrets.enc} is a small plaintext JSON envelope
 * {@code {"keyVersion": N, "iv": "<base64>", "ciphertext": "<base64>"}}
 * whose ciphertext is the AES-256-GCM encryption of the Doc 06 §4.8 store
 * document {@code {"version": 1, "entries": [{key, value, createdAt,
 * updatedAt}, …]}} (instants ISO-8601; encoded by hand because the module
 * carries no {@code jackson-datatype-jsr310} requires). A fresh random IV
 * is used per write: this is a full-file-rewrite path at occasional
 * CLI/rotator volume, where random-IV collision risk is negligible — the
 * OR-M6-NONCE counter-nonce discipline belongs to the M6.3 per-event
 * path, NOT here; do not cargo-cult it across (DP-7).</p>
 *
 * <h2>Atomicity and durability (AMD-68 §2.2 / AMD-68-INV-01)</h2>
 *
 * <p>Every mutation rewrites the encrypted file in full through
 * {@link AtomicYamlWriter#writeAtomically} (write-temp → fsync → atomic
 * rename → fsync-dir): a crash before the rename leaves the prior file
 * intact (all-or-nothing); after return, every entry is durable
 * (durable-before-return). This is the store-layer discharge of
 * AMD-60-INV-03 that the M9 {@code CredentialRotator} inherits by calling
 * {@link #setAll}.</p>
 *
 * <h2>Per-operation backups (AMD-16 / DP-9)</h2>
 *
 * <p>Before every mutating operation that rewrites an existing
 * {@code secrets.enc}, the current file is copied to
 * {@code secrets.enc.bak.{N}} (N monotonic, 1-indexed; at most 5 retained,
 * oldest deleted). The first-ever write takes no backup. Backups are
 * ciphertext snapshots restorable by direct decrypt under their recorded
 * key version. A backup failure fails the mutation closed. The restore
 * CLI and {@code secrets_restored} event are M13.</p>
 *
 * <h2>Plaintext lifetime (Doc 06 §3.4 / INV-SE-03)</h2>
 *
 * <p>Decrypted entries exist only for the duration of the operation that
 * needed them and are never cached on this store. Intermediate plaintext
 * {@code byte[]}s are zeroed best-effort after use (hygiene, not a
 * contract — the parsed strings themselves are subject to GC).</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Reads ({@link #resolve}, {@link #list}) take no lock — the atomic
 * rename guarantees a reader sees a wholly-old or wholly-new file.
 * Mutations serialize on one {@link ReentrantLock} (LTD-11).</p>
 */
final class StandardSecretStore implements SecretStore {

    private static final Logger log =
            LoggerFactory.getLogger(StandardSecretStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The encrypted store file (Doc 06 §3.4; E68-1 pinned name). */
    static final String SECRETS_FILE_NAME = "secrets.enc";

    /** AMD-16/DP-9 backup name prefix; the suffix is the monotonic N. */
    static final String BACKUP_PREFIX = SECRETS_FILE_NAME + ".bak.";

    /** Doc 15 §7.3 — the secret store's scope in the key hierarchy. */
    static final String SECRETS_SCOPE_ID = "config_secrets";

    private static final int MAX_RETAINED_BACKUPS = 5;
    private static final int STORE_FORMAT_VERSION = 1;

    private final Path configDir;
    private final ScopeKeyManager keyManager;
    private final Clock clock;

    /** Serializes every mutation (DP-8 / the SecretStore contract). */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates the store. Touches no files — the store file, the scope DEK,
     * and the root key all come into existence on the first mutation
     * (INV-CE-02).
     *
     * @param configDir  the resolved configuration directory ([AMD-71-A]);
     *                   never {@code null}
     * @param keyManager the shared-root key manager (Doc 15 §7.3);
     *                   never {@code null}
     * @param clock      time source for {@link SecretEntry} timestamps;
     *                   never {@code null}
     */
    StandardSecretStore(Path configDir, ScopeKeyManager keyManager, Clock clock) {
        this.configDir = Objects.requireNonNull(configDir, "configDir must not be null");
        this.keyManager = Objects.requireNonNull(keyManager, "keyManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public String resolve(String key) {
        Objects.requireNonNull(key, "key must not be null");
        SecretEntry entry = readEntries().get(key);
        if (entry == null) {
            // LTD-15: the message names the KEY, never any value.
            throw new IllegalArgumentException(
                    "secret key is not in the secret store: " + key);
        }
        return entry.value();
    }

    @Override
    public void set(String key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        // DP-8: uniform atomicity — the single-key write IS the all-or-
        // nothing path.
        setAll(Map.of(key, value));
    }

    @Override
    public void setAll(Map<String, String> secrets) {
        Objects.requireNonNull(secrets, "secrets must not be null");
        if (secrets.isEmpty()) {
            throw new IllegalArgumentException(
                    "setAll requires at least one entry (AMD-68 §2.1)");
        }
        for (Map.Entry<String, String> entry : secrets.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "secret keys must not be null");
            Objects.requireNonNull(entry.getValue(),
                    "secret values must not be null");
        }
        writeLock.lock();
        try {
            Map<String, SecretEntry> entries = readEntries();
            Instant now = clock.instant();
            for (Map.Entry<String, String> entry : secrets.entrySet()) {
                SecretEntry existing = entries.get(entry.getKey());
                entries.put(entry.getKey(), new SecretEntry(
                        entry.getKey(),
                        entry.getValue(),
                        existing != null ? existing.createdAt() : now,
                        now));
            }
            rotateBackupLocked();
            writeStoreLocked(entries);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void remove(String key) {
        Objects.requireNonNull(key, "key must not be null");
        writeLock.lock();
        try {
            Map<String, SecretEntry> entries = readEntries();
            if (entries.remove(key) == null) {
                throw new IllegalArgumentException(
                        "secret key is not in the secret store: " + key);
            }
            rotateBackupLocked();
            writeStoreLocked(entries);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Set<String> list() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(readEntries().keySet()));
    }

    // ──────────────────────────────────────────────────────────────────
    // Decrypt / read (Doc 06 §3.4 — decrypt, use, discard)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Decrypts the store into a fresh mutable map. An absent file is the
     * empty store — created on first mutation, never by a read
     * (INV-CE-02).
     */
    private Map<String, SecretEntry> readEntries() {
        Path file = configDir.resolve(SECRETS_FILE_NAME);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        String envelopeJson;
        try {
            envelopeJson = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    SECRETS_FILE_NAME + " cannot be read: " + file, e);
        }
        return decryptEntries(envelopeJson);
    }

    private Map<String, SecretEntry> decryptEntries(String envelopeJson) {
        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(envelopeJson);
        } catch (IOException e) {
            throw new IllegalStateException(
                    SECRETS_FILE_NAME + " is corrupt: the envelope is not valid"
                            + " JSON", e);
        }
        int keyVersion = requireField(envelope, "keyVersion").asInt();
        byte[] iv = Base64.getDecoder()
                .decode(requireField(envelope, "iv").asText());
        byte[] ciphertext = Base64.getDecoder()
                .decode(requireField(envelope, "ciphertext").asText());

        byte[] plaintext =
                keyManager.decrypt(SECRETS_SCOPE_ID, keyVersion, ciphertext, iv);
        try {
            JsonNode store = MAPPER.readTree(
                    new String(plaintext, StandardCharsets.UTF_8));
            int version = requireField(store, "version").asInt();
            if (version != STORE_FORMAT_VERSION) {
                throw new IllegalStateException(
                        SECRETS_FILE_NAME + " carries unsupported store format"
                                + " version " + version + "; this build reads"
                                + " version " + STORE_FORMAT_VERSION);
            }
            JsonNode entriesNode = requireField(store, "entries");
            Map<String, SecretEntry> entries = new LinkedHashMap<>();
            for (JsonNode node : entriesNode) {
                SecretEntry entry = new SecretEntry(
                        requireField(node, "key").asText(),
                        requireField(node, "value").asText(),
                        Instant.parse(requireField(node, "createdAt").asText()),
                        Instant.parse(requireField(node, "updatedAt").asText()));
                entries.put(entry.key(), entry);
            }
            return entries;
        } catch (IOException e) {
            throw new IllegalStateException(
                    SECRETS_FILE_NAME + " is corrupt: the decrypted store is not"
                            + " valid JSON", e);
        } finally {
            // Best-effort hygiene (§3.4) — the decoded strings are GC'd.
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Encrypt / write (AMD-68 §2.2; caller holds the write lock)
    // ──────────────────────────────────────────────────────────────────

    private void writeStoreLocked(Map<String, SecretEntry> entries) {
        ObjectNode store = MAPPER.createObjectNode();
        store.put("version", STORE_FORMAT_VERSION);
        ArrayNode entriesNode = store.putArray("entries");
        for (SecretEntry entry : entries.values()) {
            ObjectNode node = entriesNode.addObject();
            node.put("key", entry.key());
            node.put("value", entry.value());
            node.put("createdAt", entry.createdAt().toString());
            node.put("updatedAt", entry.updatedAt().toString());
        }
        byte[] plaintext = store.toString().getBytes(StandardCharsets.UTF_8);
        ScopeCipherResult encrypted;
        try {
            encrypted = keyManager.encrypt(SECRETS_SCOPE_ID, plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("keyVersion", encrypted.keyVersion());
        envelope.put("iv", Base64.getEncoder().encodeToString(encrypted.iv()));
        envelope.put("ciphertext",
                Base64.getEncoder().encodeToString(encrypted.ciphertext()));
        Path file = configDir.resolve(SECRETS_FILE_NAME);
        try {
            AtomicYamlWriter.writeAtomically(file, envelope.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    SECRETS_FILE_NAME + " write failed; the prior store is"
                            + " intact: " + file, e);
        }
    }

    /**
     * AMD-16/DP-9: copies the existing store to the next monotonic backup
     * number, then prunes the oldest backups beyond the retention cap.
     * No existing file (the first-ever write) takes no backup. A failure
     * fails the mutation closed — silently losing the only rollback copy
     * is worse than a rejected write.
     */
    private void rotateBackupLocked() {
        Path file = configDir.resolve(SECRETS_FILE_NAME);
        if (!Files.exists(file)) {
            return;
        }
        List<Integer> existing = backupNumbers();
        int next = existing.isEmpty()
                ? 1
                : existing.get(existing.size() - 1) + 1;
        Path backup = configDir.resolve(BACKUP_PREFIX + next);
        try {
            AtomicYamlWriter.copyBackup(file, backup);
            // The backup is load-bearing rollback history (AMD-16) — fsync
            // it so a power loss after the mutation returns cannot silently
            // empty the snapshot this mutation just promised.
            try (FileChannel channel = FileChannel.open(backup,
                    StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "secret store mutation failed; the backup could not be"
                            + " created at " + backup, e);
        }
        existing.add(next);
        while (existing.size() > MAX_RETAINED_BACKUPS) {
            int oldest = existing.remove(0);
            Path victim = configDir.resolve(BACKUP_PREFIX + oldest);
            try {
                Files.deleteIfExists(victim);
            } catch (IOException e) {
                // Retention pruning is housekeeping — an undeletable old
                // backup never blocks the mutation.
                log.warn("Stale secret-store backup could not be deleted: {}",
                        victim, e);
            }
        }
    }

    /**
     * Existing backup numbers in ascending order. Non-numeric and over-long
     * suffixes are ignored, not parsed — the rotation tolerates junk file
     * names rather than letting a stray operator-created file fail every
     * mutation with a parse error (9 digits keeps parseInt overflow-free).
     */
    private List<Integer> backupNumbers() {
        try (Stream<Path> entries = Files.list(configDir)) {
            return new ArrayList<>(entries
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(BACKUP_PREFIX))
                    .map(name -> name.substring(BACKUP_PREFIX.length()))
                    .filter(suffix -> !suffix.isEmpty()
                            && suffix.length() <= 9
                            && suffix.chars().allMatch(Character::isDigit))
                    .map(Integer::parseInt)
                    .sorted()
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "secret store mutation failed; the backup rotation could"
                            + " not list " + configDir, e);
        }
    }

    private static JsonNode requireField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    SECRETS_FILE_NAME + " is corrupt: missing '" + field + "'");
        }
        return value;
    }
}
