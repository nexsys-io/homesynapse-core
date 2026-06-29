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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link ScopeKeyManager} implementation (Doc 15 §3.5/§4.2, M6.2).
 *
 * <h2>On-disk artifacts (all lazy — INV-CE-02)</h2>
 *
 * <ul>
 *   <li>{@code .root-key} — 32 raw bytes from {@link SecureRandom}.
 *       Created {@code rw-------}, written and fsynced, then tightened to
 *       {@code r--------} (0400). Both permission steps are best-effort
 *       with a WARN on non-POSIX filesystems (DP-1). The key bytes are
 *       never logged and never appear in exception messages.</li>
 *   <li>{@code scope_keys.json} — a JSON array of {@link ScopeKey} rows
 *       (Doc 15 §8.2 shape), byte arrays Base64-encoded, instants in
 *       ISO-8601. Written with the module's atomic
 *       temp-fsync-rename-fsyncdir discipline via
 *       {@link AtomicYamlWriter#writeAtomically}. Plaintext is correct:
 *       every DEK is wrapped ciphertext (DP-3). Timestamps are encoded by
 *       hand because the module deliberately carries no
 *       {@code jackson-datatype-jsr310} requires (G3 — module-info is
 *       frozen for this WU).</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <p>One {@link ReentrantLock} (LTD-11) guards the root key, the KEK/DEK
 * caches, and all store reads and mutations. The payload GCM operations
 * run outside the lock on the caller's thread — {@link Cipher} instances
 * are created per call, never shared. Key-version allocation never reuses
 * a version, including a destroyed one, so a {@code dek_ref} written
 * before a shred can never silently resolve to a different key.</p>
 */
final class StandardScopeKeyManager implements ScopeKeyManager {

    private static final Logger log =
            LoggerFactory.getLogger(StandardScopeKeyManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Doc 15 §3.5 — the machine-local root key file (DP-1). */
    static final String ROOT_KEY_FILE_NAME = ".root-key";

    /** [R6]/DP-3 — the config-side scope-key store file. */
    static final String SCOPE_KEYS_FILE_NAME = "scope_keys.json";

    /**
     * OR-M6-NONCE / DP-A — the durable per-{@code (scopeId, keyVersion)}
     * nonce-counter high-water store, written alongside the DEK (Doc 15 §3.4
     * "stored alongside the DEK"). A sibling file rather than a new
     * {@link ScopeKey} field so the M6.2 store row stays frozen.
     */
    static final String SCOPE_NONCE_COUNTERS_FILE_NAME = "scope_nonce_counters.json";

    private static final int KEY_LENGTH_BYTES = 32;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";

    /**
     * F3/F1 (AB-4) — the empty additional-authenticated-data array. The
     * random-IV {@link #encrypt} secrets path and the internal DEK wrap/unwrap
     * bind no AAD; the counter-nonce {@link #encryptPayload} payload path binds
     * the caller-supplied AAD (the persistence envelope version byte). Shared
     * and never mutated (GCM only reads it).
     */
    private static final byte[] NO_AAD = new byte[0];

    /** Doc 15 §4.2 — HKDF info prefix for scope-KEK derivation. */
    private static final String SCOPE_INFO_PREFIX = "scope:";

    private static final String CREATE_PERMISSIONS = "rw-------";
    private static final String FINAL_PERMISSIONS = "r--------";

    private final Path configDir;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Guards every field below plus all key-file and store I/O (LTD-11). */
    private final ReentrantLock lock = new ReentrantLock();

    /** Lazily loaded or generated; cached for the manager's lifetime. */
    private byte[] rootKey;

    /** Lazily loaded {@code scope_keys.json} rows; write-through on mutation. */
    private List<ScopeKey> scopeKeys;

    /** Per-scope KEKs — re-derived on demand, cached, never stored (DP-2). */
    private final Map<String, byte[]> kekCache = new HashMap<>();

    /** Unwrapped DEKs keyed by scope, then version. */
    private final Map<String, Map<Integer, byte[]>> dekCache = new HashMap<>();

    /**
     * OR-M6-NONCE — per-{@code (scopeId, keyVersion)} nonce high-water marks,
     * lazily loaded from {@link #SCOPE_NONCE_COUNTERS_FILE_NAME} and
     * write-through on every allocation. The in-memory map is always a
     * reflection of the persisted file (the file is fsynced before any
     * allocated nonce is returned), so re-init after a restart is automatic:
     * a fresh manager loads the persisted maxima and resumes at max + 1,
     * never from memory.
     */
    private Map<String, Map<Integer, Long>> nonceHighWater;

    /**
     * F3 (AB-4) — the per-scope nonce-construction binding (NIST SP 800-38D
     * §8.3). A scope binds to exactly one construction on its first encrypt:
     * {@link NonceConstruction#RANDOM_IV} via {@link #encrypt} (the
     * {@code config_secrets} path) or {@link NonceConstruction#COUNTER} via
     * {@link #encryptPayload} (the event-payload path). A later encrypt under
     * the other construction is rejected — mixing a random IV and a counter
     * nonce under one DEK risks a {@code (key, nonce)} collision that breaks
     * GCM confidentiality and authenticity. {@link #decrypt} is
     * construction-neutral and does not bind. First-use-wins, in-memory (the
     * MVP scope set is disjoint by construction — secrets vs event scopes — so
     * a real boot never mixes; this guards a future wiring mistake).
     */
    private final Map<String, NonceConstruction> scopeConstruction = new HashMap<>();

    /** F3 (AB-4) — the GCM nonce construction a scope is bound to. */
    private enum NonceConstruction {
        /** Fresh random 96-bit IV per call ({@link #encrypt}, secrets). */
        RANDOM_IV,
        /** Durable monotonic counter nonce ({@link #encryptPayload}, events). */
        COUNTER
    }

    /**
     * Creates the manager. Touches no files — all key material is created
     * or loaded lazily on first use (INV-CE-02).
     *
     * @param configDir the resolved configuration directory ([AMD-71-A]);
     *                  never {@code null}
     * @param clock     time source for {@link ScopeKey#createdAt()};
     *                  never {@code null}
     */
    StandardScopeKeyManager(Path configDir, Clock clock) {
        this.configDir = Objects.requireNonNull(configDir, "configDir must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ScopeCipherResult encrypt(String scopeId, byte[] plaintext) {
        requireScopeId(scopeId);
        Objects.requireNonNull(plaintext, "plaintext must not be null");

        DekHandle dek;
        lock.lock();
        try {
            // F3: this scope binds to the random-IV construction; a later
            // encryptPayload (counter) on the same scope is rejected.
            bindConstructionLocked(scopeId, NonceConstruction.RANDOM_IV);
            dek = activeDekLocked(scopeId);
        } finally {
            lock.unlock();
        }

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        random.nextBytes(iv);
        // Secrets bind no AAD (no envelope versioning on that path).
        byte[] ciphertext = runGcm(Cipher.ENCRYPT_MODE, dek.key(), iv,
                plaintext, scopeId, NO_AAD);
        return new ScopeCipherResult(ciphertext, iv, dek.keyVersion());
    }

    @Override
    public ScopeCipherResult encryptPayload(String scopeId, byte[] plaintext) {
        return encryptPayload(scopeId, plaintext, NO_AAD);
    }

    @Override
    public ScopeCipherResult encryptPayload(String scopeId, byte[] plaintext,
                                            byte[] aad) {
        requireScopeId(scopeId);
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        Objects.requireNonNull(aad, "aad must not be null");

        DekHandle dek;
        byte[] nonce;
        lock.lock();
        try {
            // F3: this scope binds to the counter construction; a later
            // encrypt (random IV) on the same scope is rejected.
            bindConstructionLocked(scopeId, NonceConstruction.COUNTER);
            dek = activeDekLocked(scopeId);
            // Allocate the next counter value AND fsync the new high-water
            // mark before the nonce leaves this method (OR-M6-NONCE
            // durable-ahead-of-return): a crash after this point but before
            // the persistence INSERT can only burn a counter value (a gap),
            // never reuse one. The increment serializes on the existing
            // ReentrantLock (LTD-11) — concurrent publishers can never draw
            // the same nonce.
            long counter = nextNonceLocked(scopeId, dek.keyVersion());
            nonce = nonceBytes(counter);
        } finally {
            lock.unlock();
        }
        // GCM runs outside the lock on the caller's (publishing virtual)
        // thread with a per-call Cipher, mirroring encrypt(): the counter
        // allocation is the only serialized step. The AAD (the at-rest
        // envelope version byte, F1) is bound into the tag but not encrypted.
        byte[] ciphertext = runGcm(Cipher.ENCRYPT_MODE, dek.key(), nonce,
                plaintext, scopeId, aad);
        return new ScopeCipherResult(ciphertext, nonce, dek.keyVersion());
    }

    @Override
    public byte[] decrypt(String scopeId, int keyVersion, byte[] ciphertext,
                          byte[] iv) {
        return decrypt(scopeId, keyVersion, ciphertext, iv, NO_AAD);
    }

    @Override
    public byte[] decrypt(String scopeId, int keyVersion, byte[] ciphertext,
                          byte[] iv, byte[] aad) {
        requireScopeId(scopeId);
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        Objects.requireNonNull(iv, "iv must not be null");
        Objects.requireNonNull(aad, "aad must not be null");

        byte[] dek;
        lock.lock();
        try {
            // decrypt is construction-neutral (F3): it does not bind a
            // construction, so a fresh manager can decrypt a stored payload
            // before it ever encrypts in that scope (the read-on-restart path).
            loadStoreLocked();
            ScopeKey row = findRowLocked(scopeId, keyVersion);
            if (row == null) {
                throw new IllegalArgumentException(
                        "no key exists for scope '" + scopeId + "' version "
                                + keyVersion);
            }
            if (row.destroyedAt() != null) {
                throw new IllegalArgumentException(
                        "key for scope '" + scopeId + "' version " + keyVersion
                                + " was destroyed at " + row.destroyedAt()
                                + "; its ciphertext is permanently unreadable"
                                + " (crypto-shred, Doc 15 §3.6)");
            }
            dek = unwrappedDekLocked(row);
        } finally {
            lock.unlock();
        }
        return runGcm(Cipher.DECRYPT_MODE, dek, iv, ciphertext, scopeId, aad);
    }

    /**
     * F3 (AB-4) — binds {@code scopeId} to one GCM nonce construction on first
     * use and rejects a later cross-construction call. Caller holds the lock.
     *
     * @throws IllegalStateException if the scope is already bound to a
     *         different construction
     */
    private void bindConstructionLocked(String scopeId, NonceConstruction construction) {
        NonceConstruction existing = scopeConstruction.putIfAbsent(scopeId, construction);
        if (existing != null && existing != construction) {
            throw new IllegalStateException(
                    "scope '" + scopeId + "' is bound to the " + existing
                            + " nonce construction; the " + construction
                            + " construction is rejected — a scope must use exactly"
                            + " one nonce construction. Mixing a random IV and a"
                            + " counter nonce under one DEK risks a (key, nonce)"
                            + " collision that breaks GCM confidentiality and"
                            + " authenticity (NIST SP 800-38D §8.3, F3).");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Scope DEK lifecycle (caller holds the lock)
    // ──────────────────────────────────────────────────────────────────

    private record DekHandle(int keyVersion, byte[] key) {
    }

    /**
     * Returns the scope's current (highest-version, non-destroyed) DEK,
     * creating the root key, the scope's KEK derivation, and a version-1
     * DEK on the scope's first use.
     */
    private DekHandle activeDekLocked(String scopeId) {
        loadStoreLocked();
        ScopeKey active = null;
        int maxVersion = 0;
        for (ScopeKey row : scopeKeys) {
            if (!row.scopeId().equals(scopeId)) {
                continue;
            }
            maxVersion = Math.max(maxVersion, row.keyVersion());
            if (row.destroyedAt() == null
                    && (active == null || row.keyVersion() > active.keyVersion())) {
                active = row;
            }
        }
        if (active == null) {
            // Versions are never reused — after a shred the next DEK
            // continues the sequence, so an old dek_ref can never resolve
            // to a different key.
            active = createScopeKeyLocked(scopeId, maxVersion + 1);
        }
        return new DekHandle(active.keyVersion(), unwrappedDekLocked(active));
    }

    private ScopeKey createScopeKeyLocked(String scopeId, int keyVersion) {
        byte[] kek = kekLocked(scopeId);
        byte[] dek = new byte[KEY_LENGTH_BYTES];
        random.nextBytes(dek);
        byte[] wrapIv = new byte[GCM_IV_LENGTH_BYTES];
        random.nextBytes(wrapIv);
        // DEK wrap binds no AAD (the wrapped DEK carries no envelope version).
        byte[] encryptedDek = runGcm(Cipher.ENCRYPT_MODE, kek, wrapIv, dek,
                scopeId, NO_AAD);

        ScopeKey row = new ScopeKey(scopeId, keyVersion, encryptedDek, wrapIv,
                clock.instant(), null);
        scopeKeys.add(row);
        persistStoreLocked();
        dekCache.computeIfAbsent(scopeId, key -> new HashMap<>())
                .put(keyVersion, dek);
        log.info("Scope key created: scope={} version={}", scopeId, keyVersion);
        return row;
    }

    private byte[] unwrappedDekLocked(ScopeKey row) {
        Map<Integer, byte[]> byVersion =
                dekCache.computeIfAbsent(row.scopeId(), key -> new HashMap<>());
        byte[] cached = byVersion.get(row.keyVersion());
        if (cached != null) {
            return cached;
        }
        byte[] kek = kekLocked(row.scopeId());
        byte[] dek = runGcm(Cipher.DECRYPT_MODE, kek, row.iv(),
                row.encryptedDek(), row.scopeId(), NO_AAD);
        if (dek.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    SCOPE_KEYS_FILE_NAME + " is corrupt: unwrapped DEK for scope '"
                            + row.scopeId() + "' version " + row.keyVersion()
                            + " is not " + KEY_LENGTH_BYTES + " bytes");
        }
        byVersion.put(row.keyVersion(), dek);
        return dek;
    }

    /** Doc 15 §4.2: KEK = HKDF-SHA256(root, "scope:" + scopeId), never stored. */
    private byte[] kekLocked(String scopeId) {
        byte[] cached = kekCache.get(scopeId);
        if (cached != null) {
            return cached;
        }
        byte[] info = (SCOPE_INFO_PREFIX + scopeId).getBytes(StandardCharsets.UTF_8);
        byte[] kek = Hkdf.derive(rootKeyLocked(), null, info, KEY_LENGTH_BYTES);
        kekCache.put(scopeId, kek);
        return kek;
    }

    // ──────────────────────────────────────────────────────────────────
    // Root key (Doc 15 §3.5; caller holds the lock)
    // ──────────────────────────────────────────────────────────────────

    private byte[] rootKeyLocked() {
        if (rootKey != null) {
            return rootKey;
        }
        Path file = configDir.resolve(ROOT_KEY_FILE_NAME);
        if (Files.exists(file)) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        ROOT_KEY_FILE_NAME + " cannot be read: " + file, e);
            }
            if (bytes.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException(
                        ROOT_KEY_FILE_NAME + " is corrupt: expected "
                                + KEY_LENGTH_BYTES + " bytes, found " + bytes.length);
            }
            rootKey = bytes;
            return rootKey;
        }
        byte[] key = new byte[KEY_LENGTH_BYTES];
        random.nextBytes(key);
        writeRootKey(file, key);
        rootKey = key;
        log.info("Machine-local root key generated at {} (Doc 15 §3.5)",
                ROOT_KEY_FILE_NAME);
        return rootKey;
    }

    /**
     * Creates the key file through a same-directory temp + atomic rename so
     * a crash mid-write can never leave a short or zero-filled
     * {@code .root-key} in place — a partial key file would wedge every
     * later start at the corrupt check, and a metadata-first crash artifact
     * could even be silently adopted as key material. The temp is created
     * {@code rw-------}, written and fsynced, tightened to {@code r--------}
     * (0400 — the owner could not write through a file created 0400, hence
     * the two-step), then renamed over the final name; the rename carries
     * the permissions. Permission steps are best-effort with one WARN on
     * non-POSIX filesystems (DP-1 / the ConfigLayoutTest precedent). The
     * rename's directory entry is made durable by the scope-key store's
     * directory-fsynced write that immediately follows the first key use.
     */
    private static void writeRootKey(Path file, byte[] key) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.deleteIfExists(tmp);
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString(CREATE_PERMISSIONS)));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp);
                log.warn("POSIX file permissions are unsupported on this"
                        + " filesystem; {} is created without owner-only"
                        + " permissions", ROOT_KEY_FILE_NAME);
            }
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(key);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.setPosixFilePermissions(tmp,
                        PosixFilePermissions.fromString(FINAL_PERMISSIONS));
            } catch (UnsupportedOperationException e) {
                // Already warned at creation — same filesystem property.
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new UncheckedIOException(
                    ROOT_KEY_FILE_NAME + " cannot be created: " + file, e);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // scope_keys.json store (DP-3; caller holds the lock)
    // ──────────────────────────────────────────────────────────────────

    private ScopeKey findRowLocked(String scopeId, int keyVersion) {
        for (ScopeKey row : scopeKeys) {
            if (row.scopeId().equals(scopeId) && row.keyVersion() == keyVersion) {
                return row;
            }
        }
        return null;
    }

    private void loadStoreLocked() {
        if (scopeKeys != null) {
            return;
        }
        Path file = configDir.resolve(SCOPE_KEYS_FILE_NAME);
        if (!Files.exists(file)) {
            scopeKeys = new ArrayList<>();
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    SCOPE_KEYS_FILE_NAME + " cannot be read: " + file, e);
        }
        if (!root.isArray()) {
            throw new IllegalStateException(
                    SCOPE_KEYS_FILE_NAME + " is corrupt: expected a JSON array"
                            + " of scope-key rows");
        }
        List<ScopeKey> rows = new ArrayList<>();
        for (JsonNode node : root) {
            rows.add(new ScopeKey(
                    requireField(node, "scopeId").asText(),
                    requireField(node, "keyVersion").asInt(),
                    Base64.getDecoder().decode(
                            requireField(node, "encryptedDek").asText()),
                    Base64.getDecoder().decode(requireField(node, "iv").asText()),
                    Instant.parse(requireField(node, "createdAt").asText()),
                    node.hasNonNull("destroyedAt")
                            ? Instant.parse(node.get("destroyedAt").asText())
                            : null));
        }
        scopeKeys = rows;
    }

    private void persistStoreLocked() {
        ArrayNode array = MAPPER.createArrayNode();
        for (ScopeKey row : scopeKeys) {
            ObjectNode node = array.addObject();
            node.put("scopeId", row.scopeId());
            node.put("keyVersion", row.keyVersion());
            node.put("encryptedDek",
                    Base64.getEncoder().encodeToString(row.encryptedDek()));
            node.put("iv", Base64.getEncoder().encodeToString(row.iv()));
            node.put("createdAt", row.createdAt().toString());
            if (row.destroyedAt() != null) {
                node.put("destroyedAt", row.destroyedAt().toString());
            } else {
                node.putNull("destroyedAt");
            }
        }
        Path file = configDir.resolve(SCOPE_KEYS_FILE_NAME);
        try {
            AtomicYamlWriter.writeAtomically(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    SCOPE_KEYS_FILE_NAME + " cannot be written; the prior store"
                            + " is intact: " + file, e);
        }
    }

    private static JsonNode requireField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    SCOPE_KEYS_FILE_NAME + " is corrupt: row is missing '"
                            + field + "'");
        }
        return value;
    }

    // ──────────────────────────────────────────────────────────────────
    // Durable nonce counters (OR-M6-NONCE; caller holds the lock)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Allocates the next strictly-monotonic counter value for
     * {@code (scopeId, keyVersion)} and persists the new high-water mark
     * durably before returning it (OR-M6-NONCE durable-ahead-of-return). On
     * the first allocation after a restart the value resumes from the
     * persisted maximum + 1 (re-init from durable state, never from memory),
     * because {@link #loadNonceCountersLocked()} reloads the file maxima.
     */
    private long nextNonceLocked(String scopeId, int keyVersion) {
        loadNonceCountersLocked();
        Map<Integer, Long> byVersion =
                nonceHighWater.computeIfAbsent(scopeId, key -> new HashMap<>());
        long next = byVersion.getOrDefault(keyVersion, 0L) + 1L;
        byVersion.put(keyVersion, next);
        persistNonceCountersLocked();
        return next;
    }

    /**
     * Encodes a counter value as a 96-bit GCM nonce: big-endian in the
     * trailing 8 bytes of the 12-byte field, leading 4 bytes zero (DP-C).
     * Distinct counter values yield distinct nonces, so no two stored
     * ciphertexts under one DEK can share a nonce.
     */
    private static byte[] nonceBytes(long counter) {
        byte[] nonce = new byte[GCM_IV_LENGTH_BYTES];
        ByteBuffer.wrap(nonce).putLong(GCM_IV_LENGTH_BYTES - Long.BYTES, counter);
        return nonce;
    }

    private void loadNonceCountersLocked() {
        if (nonceHighWater != null) {
            return;
        }
        Path file = configDir.resolve(SCOPE_NONCE_COUNTERS_FILE_NAME);
        if (!Files.exists(file)) {
            nonceHighWater = new HashMap<>();
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    SCOPE_NONCE_COUNTERS_FILE_NAME + " cannot be read: " + file, e);
        }
        if (!root.isArray()) {
            throw new IllegalStateException(
                    SCOPE_NONCE_COUNTERS_FILE_NAME + " is corrupt: expected a JSON"
                            + " array of nonce-counter rows");
        }
        Map<String, Map<Integer, Long>> counters = new HashMap<>();
        for (JsonNode node : root) {
            counters.computeIfAbsent(
                            requireCounterField(node, "scopeId").asText(),
                            key -> new HashMap<>())
                    .put(requireCounterField(node, "keyVersion").asInt(),
                            requireCounterField(node, "highWater").asLong());
        }
        nonceHighWater = counters;
    }

    private void persistNonceCountersLocked() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Map.Entry<String, Map<Integer, Long>> scope
                : nonceHighWater.entrySet()) {
            for (Map.Entry<Integer, Long> version : scope.getValue().entrySet()) {
                ObjectNode node = array.addObject();
                node.put("scopeId", scope.getKey());
                node.put("keyVersion", version.getKey());
                node.put("highWater", version.getValue());
            }
        }
        Path file = configDir.resolve(SCOPE_NONCE_COUNTERS_FILE_NAME);
        try {
            // F13b (AB-4): the DURABLE write — writeAtomicallyDurable fsyncs the
            // temp file (channel.force) before the atomic rename AND fails closed
            // if the directory-entry fsync genuinely fails on a POSIX filesystem
            // (a non-durable high-water mark could replay a nonce on crash →
            // catastrophic GCM (key, nonce) reuse). A platform that cannot open a
            // directory channel (Windows/non-POSIX) is tolerated — durability
            // there rides the metadata journal. The nonce-counter store is the
            // ONLY writer that opts into fail-closed dir-fsync; scope_keys/secrets
            // keep the best-effort writeAtomically (OR-M6-NONCE / Doc 15 §6).
            AtomicYamlWriter.writeAtomicallyDurable(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array));
        } catch (IOException e) {
            // A pre-rename failure (temp create/write/rename): the prior counter
            // file is intact. A confirmed dir-fsync durability failure surfaces
            // as an UncheckedIOException from writeAtomicallyDurable and is NOT
            // caught here — it propagates as the fail-closed signal.
            throw new UncheckedIOException(
                    SCOPE_NONCE_COUNTERS_FILE_NAME + " cannot be written; the prior"
                            + " counter state is intact: " + file, e);
        }
    }

    private static JsonNode requireCounterField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    SCOPE_NONCE_COUNTERS_FILE_NAME + " is corrupt: row is missing '"
                            + field + "'");
        }
        return value;
    }

    // ──────────────────────────────────────────────────────────────────
    // GCM plumbing
    // ──────────────────────────────────────────────────────────────────

    /**
     * One AES-256-GCM operation with a per-call {@link Cipher} —
     * {@code Cipher} instances are not thread-safe (LTD-11 confinement).
     * Exception messages never carry key material or plaintext.
     *
     * <p>{@code aad} is bound as additional authenticated data (covered by the
     * tag, not encrypted) when non-empty — F1's envelope-version binding. An
     * empty {@code aad} is a no-op, so the encrypt and decrypt sides agree as
     * long as both pass the same array (the secrets and DEK-wrap paths both
     * pass {@link #NO_AAD}).</p>
     */
    private static byte[] runGcm(int mode, byte[] key, byte[] iv, byte[] input,
                                 String scopeId, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(key, KEY_ALGORITHM),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            if (aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    (mode == Cipher.ENCRYPT_MODE ? "encryption" : "decryption")
                            + " failed for scope '" + scopeId + "'", e);
        }
    }

    private static void requireScopeId(String scopeId) {
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        if (scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
    }
}
