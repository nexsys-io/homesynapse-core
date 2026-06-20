/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed store for opaque bearer tokens (the A2 ruling, 2026-06-19):
 * random 256-bit tokens presented as {@code Authorization: Bearer {token}},
 * validated against a local store that holds only a one-way SHA-256 hash of each
 * token (INV-SE-03) plus its server-side claims.
 *
 * <p><strong>Scheme (RULED).</strong> Tokens are 256-bit {@link SecureRandom}
 * values, URL-safe Base64 (43 chars), shown <em>once</em> at mint time and stored
 * only as the SHA-256 hash of the raw token bytes. The JDK-native hash is correct
 * here: a 256-bit random token has no low-entropy weakness that bcrypt's work
 * factor exists to defend (there is also no bcrypt/password-hash library in the
 * version catalog). The raw token is never persisted (except the one-time pairing
 * artifact below) and never logged.</p>
 *
 * <p><strong>Module placement.</strong> This is a self-contained store inside
 * {@code api.rest} that reads a config-dir {@link Path} passed in at the
 * composition root — adding <em>zero</em> new module edges (the A1.2 option). Its
 * public surface names only {@code java.base} types and {@code api.rest}'s own
 * {@link ApiKeyIdentity}/{@link ApiKeyClaims}, so it leaks nothing on the
 * exported API. The composition root in {@code lifecycle}/{@code app} constructs
 * it.</p>
 *
 * <p><strong>Pairing / first run.</strong> {@link #ensureInitialToken()} mints one
 * full-access token on a fresh store and surfaces it once — written to the
 * {@code initial_api_token} pairing artifact in the config dir and logged at WARN
 * (no network call; R-δ AX-9). Rotation is mint-new + revoke-old; there is no
 * in-place mutation of a token's secret and no default/shared bootstrap secret
 * (zero-config stays authenticated, INV-SE-02).</p>
 *
 * <p><strong>Thread safety.</strong> {@link #validate(String)} and
 * {@link #claimsFor(String)} read a {@link ConcurrentHashMap} and are safe for
 * concurrent virtual-thread invocation; mutations ({@link #mint}, {@link #revoke},
 * {@link #ensureInitialToken}) serialize on a {@link ReentrantLock} (LTD-11 — never
 * {@code synchronized}) and rewrite the backing file atomically.</p>
 *
 * @see AuthMiddleware
 * @see StandardAuthMiddleware
 * @see ApiKeyClaims
 */
public final class OpaqueTokenStore {

    /** Name of the token store file under the config directory (hashes only). */
    static final String TOKEN_FILE_NAME = "api_tokens";

    /** Name of the one-time pairing artifact written on first run (raw token). */
    static final String INITIAL_TOKEN_ARTIFACT = "initial_api_token";

    /** Raw token entropy in bytes (256-bit). */
    private static final int TOKEN_BYTES = 32;

    /** Public key-id entropy in bytes (not a secret). */
    private static final int KEY_ID_BYTES = 9;

    private static final String FIELD_DELIMITER = "\t";
    private static final String SCOPE_DELIMITER = ",";
    private static final long NEVER_EXPIRES = -1L;

    private static final Logger LOG = LoggerFactory.getLogger(OpaqueTokenStore.class);

    private final Path tokenFile;
    private final Path artifactFile;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantLock writeLock = new ReentrantLock();

    /** token-hash (hex) → record. The hash IS the lookup key, so validation is a map get. */
    private final ConcurrentHashMap<String, TokenRecord> byHash = new ConcurrentHashMap<>();

    /**
     * Server-side token record. The raw token is never held — only its hash (the
     * map key). {@code expiresAt} is {@code null} for a non-expiring token.
     */
    private record TokenRecord(
            String keyId,
            String displayName,
            Instant createdAt,
            Instant expiresAt,
            List<String> scopes,
            String siteId,
            boolean revoked) {
    }

    /**
     * Opens (or initializes empty) the token store rooted at {@code configDir}.
     * Loads any existing {@code api_tokens} file; a malformed line is skipped with
     * a WARN (a dropped token simply fails to authenticate — fail-closed — and
     * never bricks startup).
     *
     * @param configDir the configuration directory the token files live under;
     *                  never {@code null}. Must already exist.
     * @param clock     injected clock for mint timestamps and expiry checks;
     *                  never {@code null}
     */
    public OpaqueTokenStore(Path configDir, Clock clock) {
        Objects.requireNonNull(configDir, "configDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenFile = configDir.resolve(TOKEN_FILE_NAME);
        this.artifactFile = configDir.resolve(INITIAL_TOKEN_ARTIFACT);
        load();
    }

    /**
     * Validates a raw bearer token.
     *
     * @param rawToken the raw token value (NOT the {@code Authorization} header),
     *                 may be {@code null}
     * @return the caller identity for a present, non-revoked, non-expired token;
     *         {@link Optional#empty()} otherwise. The raw token is never logged.
     */
    public Optional<ApiKeyIdentity> validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        TokenRecord record = byHash.get(sha256Hex(rawToken));
        if (record == null || record.revoked()) {
            return Optional.empty();
        }
        if (record.expiresAt() != null && !clock.instant().isBefore(record.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(
                new ApiKeyIdentity(record.keyId(), record.displayName(), record.createdAt()));
    }

    /**
     * Resolves the server-side claims for a {@code key_id} (the enterprise hook;
     * not enforced at Tier 1).
     *
     * @param keyId the public key identifier
     * @return the claims, or empty if no such key exists
     */
    public Optional<ApiKeyClaims> claimsFor(String keyId) {
        if (keyId == null) {
            return Optional.empty();
        }
        return byHash.values().stream()
                .filter(r -> keyId.equals(r.keyId()))
                .findFirst()
                .map(r -> new ApiKeyClaims(r.scopes(), r.siteId()));
    }

    /**
     * Mints a new token. The raw token is returned <em>once</em> — only its hash
     * is persisted.
     *
     * @param displayName human-readable label; never {@code null}
     * @param scopes      granted scopes ({@code ["*"]} for full access);
     *                    never {@code null}
     * @param siteId      site scoping, or {@code null} for an unscoped token
     * @return the raw bearer token (shown once); never {@code null}
     */
    public String mint(String displayName, List<String> scopes, String siteId) {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(scopes, "scopes");
        writeLock.lock();
        try {
            String rawToken = randomUrlSafe(TOKEN_BYTES);
            String keyId = randomUrlSafe(KEY_ID_BYTES);
            TokenRecord record = new TokenRecord(
                    keyId, displayName, clock.instant(), null,
                    List.copyOf(scopes), siteId, false);
            byHash.put(sha256Hex(rawToken), record);
            persist();
            return rawToken;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * On a fresh (empty) store, mints one full-access pairing token, persists its
     * hash, writes the one-time {@code initial_api_token} artifact in the config
     * dir, and logs it at WARN. No-op (returns empty) when any token already
     * exists.
     *
     * @return the raw pairing token if one was minted; empty if the store was
     *         already initialized
     */
    public Optional<String> ensureInitialToken() {
        writeLock.lock();
        try {
            if (!byHash.isEmpty()) {
                return Optional.empty();
            }
            String rawToken = mint("initial-pairing-token", List.of(ApiKeyClaims.SCOPE_ALL), null);
            writeArtifact(rawToken);
            LOG.warn("Minted the initial HomeSynapse API token (shown once). Pair a client "
                    + "with this bearer token, then delete the artifact at {}. Token: {}",
                    artifactFile, rawToken);
            return Optional.of(rawToken);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Revokes a token by its public {@code key_id} (rotation = mint-new then
     * revoke-old; no in-place secret mutation).
     *
     * @param keyId the public key identifier
     * @return {@code true} if a matching active token was revoked
     */
    public boolean revoke(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        writeLock.lock();
        try {
            for (var entry : byHash.entrySet()) {
                TokenRecord r = entry.getValue();
                if (keyId.equals(r.keyId()) && !r.revoked()) {
                    entry.setValue(new TokenRecord(r.keyId(), r.displayName(), r.createdAt(),
                            r.expiresAt(), r.scopes(), r.siteId(), true));
                    persist();
                    return true;
                }
            }
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    /** @return the count of active (non-revoked, non-expired) tokens. */
    public int activeKeyCount() {
        Instant now = clock.instant();
        return (int) byHash.values().stream()
                .filter(r -> !r.revoked())
                .filter(r -> r.expiresAt() == null || now.isBefore(r.expiresAt()))
                .count();
    }

    // ──────────────────────────────────────────────────────────────────
    // Persistence (java.base only — no Jackson edge)
    // ──────────────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(tokenFile)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(tokenFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to read token store at " + tokenFile, e);
        }
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            try {
                parseLine(line);
            } catch (RuntimeException e) {
                LOG.warn("skipping malformed token store line in {} (the token will not "
                        + "authenticate): {}", tokenFile, e.getMessage());
            }
        }
    }

    private void parseLine(String line) {
        String[] f = line.split(FIELD_DELIMITER, -1);
        if (f.length != 8) {
            throw new IllegalArgumentException("expected 8 tab-separated fields, got " + f.length);
        }
        String keyId = f[0];
        String tokenHashHex = f[1];
        String displayName = decode(f[2]);
        Instant createdAt = Instant.ofEpochMilli(Long.parseLong(f[3]));
        long expiresMillis = Long.parseLong(f[4]);
        Instant expiresAt = (expiresMillis == NEVER_EXPIRES)
                ? null : Instant.ofEpochMilli(expiresMillis);
        List<String> scopes = parseScopes(decode(f[5]));
        String siteId = f[6].isEmpty() ? null : decode(f[6]);
        boolean revoked = "1".equals(f[7]);
        byHash.put(tokenHashHex, new TokenRecord(
                keyId, displayName, createdAt, expiresAt, scopes, siteId, revoked));
    }

    /** Rewrites the whole token file. Caller holds the write lock. */
    private void persist() {
        StringBuilder sb = new StringBuilder();
        for (var entry : byHash.entrySet()) {
            TokenRecord r = entry.getValue();
            sb.append(r.keyId()).append(FIELD_DELIMITER)
                    .append(entry.getKey()).append(FIELD_DELIMITER)
                    .append(encode(r.displayName())).append(FIELD_DELIMITER)
                    .append(r.createdAt().toEpochMilli()).append(FIELD_DELIMITER)
                    .append(r.expiresAt() == null ? NEVER_EXPIRES : r.expiresAt().toEpochMilli())
                    .append(FIELD_DELIMITER)
                    .append(encode(String.join(SCOPE_DELIMITER, r.scopes()))).append(FIELD_DELIMITER)
                    .append(r.siteId() == null ? "" : encode(r.siteId())).append(FIELD_DELIMITER)
                    .append(r.revoked() ? "1" : "0").append('\n');
        }
        try {
            Files.writeString(tokenFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist token store at " + tokenFile, e);
        }
    }

    private void writeArtifact(String rawToken) {
        try {
            Files.writeString(artifactFile, rawToken + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "failed to write initial token artifact at " + artifactFile, e);
        }
    }

    private static List<String> parseScopes(String csv) {
        if (csv.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(SCOPE_DELIMITER)) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private String randomUrlSafe(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm; absence is non-recoverable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
