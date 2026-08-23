/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * <p><strong>The operator path (R-6, closes F-S5).</strong> The store file has ONE
 * lawful writer — the running service — so a second process editing
 * {@code api_tokens} offline is a lost-update race by construction. Operators
 * therefore never edit the store: they write an <em>operator request</em> file,
 * {@code config/token_ops.request} (one verb per line: {@code rotate},
 * {@code revoke <keyId>}, {@code mint <display name>}), and restart. The
 * composition root calls {@link #processOperatorRequests()} immediately after
 * {@link #ensureInitialToken()}; the request is deleted BEFORE any verb executes,
 * so a crash mid-batch can never replay it. {@link #rotate(String)} is the
 * all-sessions rotation — the remediation for a disclosed credential — and the
 * revoked rows stay in the store as history ({@code revoked=1}); nothing is ever
 * deleted from the store. Read-only inspection rides {@link #summaries()}, which
 * never exposes a hash or a raw token.</p>
 *
 * <p><strong>The last-full-access-token guard (R-H2, R-9 2026-08-22).</strong>
 * {@link #revoke(String)} REFUSES to revoke the only active full-access token
 * ({@link RevokeOutcome#REFUSED_LAST_FULL_ACCESS}): at HTTP that is a 409, at the
 * request file a {@code skipped} entry — never a silent no-op. No operator act can
 * therefore leave the store with zero active full-access tokens; replacing the
 * last one is what {@link #rotate(String)} is for (it mints first and revokes the
 * rest directly, never through {@code revoke}).</p>
 *
 * <p><strong>Thread safety.</strong> {@link #validate(String)},
 * {@link #claimsFor(String)} and {@link #summaries()} read a
 * {@link ConcurrentHashMap} and are safe for concurrent virtual-thread invocation;
 * mutations ({@link #mint}, {@link #revoke}, {@link #rotate},
 * {@link #ensureInitialToken}, {@link #processOperatorRequests}) serialize on a
 * {@link ReentrantLock} (LTD-11 — never {@code synchronized}) and rewrite the
 * backing file atomically (a same-directory {@code .tmp} sibling, fsynced, then
 * {@code ATOMIC_MOVE} — a crash mid-rewrite leaves the previous file intact; the
 * alternative, a truncated store, would read as EMPTY on the next boot and
 * silently mint a fresh pairing token).</p>
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

    /**
     * Name of the operator request file under the config directory (R-6). Written
     * by the operator (root via {@code homesynapse-token}, or the service user on a
     * bench), consumed exactly once at startup by {@link #processOperatorRequests()}.
     * It must be READABLE by the service user (owner, or mode ≥ 0640) and the config
     * directory must be writable by it (deletion) — both hold on the packaged path.
     */
    static final String OPERATOR_REQUEST_FILE = "token_ops.request";

    /** Raw token entropy in bytes (256-bit). */
    private static final int TOKEN_BYTES = 32;

    /** Public key-id entropy in bytes (not a secret). */
    private static final int KEY_ID_BYTES = 9;

    private static final String FIELD_DELIMITER = "\t";
    private static final String SCOPE_DELIMITER = ",";
    private static final long NEVER_EXPIRES = -1L;

    /** Same-directory temp sibling suffix for the atomic rewrite (the AtomicYamlWriter idiom). */
    private static final String TEMP_SUFFIX = ".tmp";

    /** Owner-only mode for both secret-bearing files on POSIX (the config dir's 0700 is the fence). */
    private static final String OWNER_ONLY = "rw-------";

    /** POSIX permission calls are guarded — the build and the desk run on Windows too. */
    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static final Logger LOG = LoggerFactory.getLogger(OpaqueTokenStore.class);

    private final Path tokenFile;
    private final Path artifactFile;
    private final Path requestFile;
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
     * Read-only view of one stored token for operators and the token-admin
     * endpoints: the public key id and its claims — NEVER the hash, NEVER a raw
     * token. {@code expiresAt} and {@code siteId} are {@code null} when unset.
     *
     * @param keyId       the public key identifier (not a secret)
     * @param displayName the human-readable label bound at mint time
     * @param createdAt   the mint instant
     * @param expiresAt   the expiry instant, or {@code null} for a non-expiring token
     * @param scopes      the granted scopes ({@code ["*"]} = full access); unmodifiable
     * @param siteId      the site scope, or {@code null} for an unscoped token
     * @param revoked     {@code true} once revoked — the row stays as history
     */
    public record TokenSummary(
            String keyId,
            String displayName,
            Instant createdAt,
            Instant expiresAt,
            List<String> scopes,
            String siteId,
            boolean revoked) {

        /** Normalizes {@code scopes} to an unmodifiable copy. */
        public TokenSummary {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(createdAt, "createdAt");
            scopes = (scopes == null) ? List.of() : List.copyOf(scopes);
        }
    }

    /**
     * What one {@link #processOperatorRequests()} pass did. A zero report (all
     * counts 0, {@code skipped} empty) means no request file was present — or one
     * was present but could not be read or removed and therefore executed NOTHING.
     *
     * @param rotated the number of {@code rotate} lines executed
     * @param revoked the number of {@code revoke} lines that revoked an active token
     * @param minted  the number of {@code mint} lines executed
     * @param skipped one entry per line that executed nothing — {@code "line N: <reason>"}
     *                (an unknown verb, a missing argument, a revoke of an unknown or
     *                already-revoked key, or a revoke the R-H2 guard refused). The
     *                operator's text is NEVER echoed — a raw token pasted where a key
     *                id belongs must not reach the journal — except a key id that
     *                already exists in the store (public by construction). Unmodifiable.
     */
    public record OperatorRequestReport(int rotated, int revoked, int minted, List<String> skipped) {

        /** Normalizes {@code skipped} to an unmodifiable copy. */
        public OperatorRequestReport {
            skipped = (skipped == null) ? List.of() : List.copyOf(skipped);
        }

        private static OperatorRequestReport none() {
            return new OperatorRequestReport(0, 0, 0, List.of());
        }
    }

    /**
     * What {@link #revoke(String)} did (R-H2, R-9 2026-08-22 — the
     * last-full-access-token guard).
     */
    public enum RevokeOutcome {
        /** A matching active token was revoked and the store persisted. */
        REVOKED,
        /** No active token carries that key id — unknown, or already revoked. */
        NOT_FOUND,
        /**
         * The key is the ONLY active (non-revoked, non-expired) full-access token:
         * revoking it would lock every client — and every later
         * {@code /internal/tokens} call — out (the self-lockout class). Nothing
         * was persisted; {@link #rotate(String)} is the way to replace it.
         */
        REFUSED_LAST_FULL_ACCESS
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
        this.requestFile = configDir.resolve(OPERATOR_REQUEST_FILE);
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
     * A point-in-time snapshot of every stored token (active AND revoked — the
     * revoked rows are history), sorted by {@code createdAt} then {@code keyId}.
     * Carries no hash and no raw token; safe to print and to serialize.
     *
     * @return the summaries; never {@code null}; unmodifiable
     */
    public List<TokenSummary> summaries() {
        List<TokenSummary> out = new ArrayList<>(byHash.size());
        for (TokenRecord r : byHash.values()) {
            out.add(new TokenSummary(r.keyId(), r.displayName(), r.createdAt(),
                    r.expiresAt(), r.scopes(), r.siteId(), r.revoked()));
        }
        out.sort(Comparator.comparing(TokenSummary::createdAt)
                .thenComparing(TokenSummary::keyId));
        return List.copyOf(out);
    }

    /**
     * Mints a new token. The raw token is returned <em>once</em> — only its hash
     * is persisted.
     *
     * @param displayName human-readable label; never {@code null}
     * @param scopes      granted scopes ({@code ["*"]} for full access);
     *                    never {@code null}; no scope may be blank or contain the
     *                    store's scope delimiter ({@code ,}) — such a scope would
     *                    persist as one value and reload as two
     * @param siteId      site scoping, or {@code null} for an unscoped token
     * @return the raw bearer token (shown once); never {@code null}
     * @throws IllegalArgumentException if a scope is blank or contains {@code ,}
     */
    public String mint(String displayName, List<String> scopes, String siteId) {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(scopes, "scopes");
        for (String scope : scopes) {
            if (scope == null || scope.isBlank() || scope.contains(SCOPE_DELIMITER)) {
                throw new IllegalArgumentException(
                        "a scope must be non-blank and must not contain '" + SCOPE_DELIMITER
                                + "' (the persisted scope delimiter)");
            }
        }
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
     * All-sessions rotation — the remediation for a disclosed credential: mints
     * ONE new full-access, unscoped token under {@code displayName}, revokes EVERY
     * other active token, persists the store ONCE, and writes the new raw token to
     * the {@code initial_api_token} artifact so delivery rides the existing
     * pairing path. The revoked rows stay as history — nothing is deleted. After
     * this call exactly one token is active. The new token is minted BEFORE the
     * others are revoked so a concurrent {@link #validate(String)} never observes a
     * store with zero active tokens.
     *
     * @param displayName the label for the new token; never {@code null}
     * @return the new raw bearer token (shown once); never {@code null}
     */
    public String rotate(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        writeLock.lock();
        try {
            String rawToken = randomUrlSafe(TOKEN_BYTES);
            String newHash = sha256Hex(rawToken);
            byHash.put(newHash, new TokenRecord(
                    randomUrlSafe(KEY_ID_BYTES), displayName, clock.instant(), null,
                    List.of(ApiKeyClaims.SCOPE_ALL), null, false));
            for (var entry : byHash.entrySet()) {
                TokenRecord r = entry.getValue();
                if (!newHash.equals(entry.getKey()) && !r.revoked()) {
                    entry.setValue(revokedCopy(r));
                }
            }
            persist();
            try {
                writeArtifact(rawToken);
            } catch (UncheckedIOException e) {
                // The rotation IS durable (every prior token revoked, the new hash stored)
                // but the new token cannot be delivered: name the state and the recovery
                // before rethrowing — the raw value is never logged.
                LOG.warn("rotation persisted — every prior token is revoked — but the new token "
                        + "could not be delivered to {}; fix the path and run rotate again",
                        artifactFile);
                throw e;
            }
            return rawToken;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Revokes a token by its public {@code key_id} (rotation = mint-new then
     * revoke-old; no in-place secret mutation).
     *
     * <p><strong>R-H2 — the last-full-access-token guard (R-9, 2026-08-22).</strong>
     * If the key is the ONLY active (non-revoked, non-expired — the
     * {@link #activeKeyCount()} predicate) full-access token, the revoke is
     * REFUSED: nothing is persisted, ONE WARN names the key id (public by
     * construction — never material), and
     * {@link RevokeOutcome#REFUSED_LAST_FULL_ACCESS} is returned. A scoped token
     * always revokes; an expired full-access row neither counts as active nor
     * resists its own revoke. {@link #rotate(String)} is never refused (it mints
     * first and revokes the rest directly, never through this method) — it is the
     * remediation when the last token must go.</p>
     *
     * <p>If the {@code initial_api_token} artifact still carries the token just
     * revoked, a WARN says so: a client reading it would be rejected. The artifact
     * is left in place; {@code mint}/{@code rotate} refresh it. (Since R-9 the
     * packaged readiness probe reads {@code /health} and never the artifact, so
     * this is a pairing concern only, not an availability one.)</p>
     *
     * @param keyId the public key identifier
     * @return what happened; never {@code null}
     */
    public RevokeOutcome revoke(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        writeLock.lock();
        try {
            for (var entry : byHash.entrySet()) {
                TokenRecord r = entry.getValue();
                if (keyId.equals(r.keyId()) && !r.revoked()) {
                    if (isLastActiveFullAccess(r)) {
                        LOG.warn("refusing to revoke the last active full-access token {}; use rotate",
                                keyId);
                        return RevokeOutcome.REFUSED_LAST_FULL_ACCESS;
                    }
                    entry.setValue(revokedCopy(r));
                    persist();
                    if (artifactCarriesDeadToken()) {
                        LOG.warn("the pairing artifact at {} now carries a token that no longer "
                                + "validates (key {} revoked); a client reading it will be "
                                + "rejected — refresh it with mint/rotate, or remove it",
                                artifactFile, keyId);
                    }
                    return RevokeOutcome.REVOKED;
                }
            }
            return RevokeOutcome.NOT_FOUND;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * The R-H2 predicate, evaluated under the write lock: {@code candidate} is an
     * ACTIVE full-access row and no OTHER active full-access row exists. An expired
     * full-access row grants nothing and never rescues the last live one; an expired
     * candidate is not "active" and so revokes freely. Reference identity on
     * purpose — "other rows", not "rows with different fields".
     */
    private boolean isLastActiveFullAccess(TokenRecord candidate) {
        Instant now = clock.instant();
        if (!isActiveFullAccess(candidate, now)) {
            return false;
        }
        for (TokenRecord r : byHash.values()) {
            if (r != candidate && isActiveFullAccess(r, now)) {
                return false;
            }
        }
        return true;
    }

    /** The {@link #activeKeyCount()} predicate narrowed to full-access rows. */
    private static boolean isActiveFullAccess(TokenRecord r, Instant now) {
        return !r.revoked()
                && (r.expiresAt() == null || now.isBefore(r.expiresAt()))
                && r.scopes().contains(ApiKeyClaims.SCOPE_ALL);
    }

    /**
     * Consumes the operator request file ({@value #OPERATOR_REQUEST_FILE}) — the
     * operator path for rotation (R-6). Under the write lock:
     * <ol>
     *   <li>no file → a zero report, no log;</li>
     *   <li>file present but UNREADABLE by the service user → WARN, zero report,
     *       the file is left in place (the store's own never-brick-startup posture;
     *       the operator fixes ownership/mode and restarts);</li>
     *   <li>file read → it is DELETED FIRST; if the delete fails → WARN, zero
     *       report, nothing executes (fail closed — an unremovable request would
     *       replay on every start);</li>
     *   <li>then each non-blank, non-{@code #} line executes: {@code rotate} →
     *       {@link #rotate(String)} named {@code operator-rotated-<instant>};
     *       {@code revoke <keyId>} → {@link #revoke(String)} ({@code NOT_FOUND} and
     *       the R-H2 {@code REFUSED_LAST_FULL_ACCESS} are {@code skipped} entries);
     *       {@code mint <display name…>} → {@link #mint} full-access/unscoped + the
     *       artifact; anything else → {@code skipped}.</li>
     * </ol>
     * ONE WARN summary is logged whenever a request was consumed — counts plus
     * the ARTIFACT PATH, never a token value (this path logs no secret; the
     * initial-mint WARN in {@link #ensureInitialToken()} is the ruled exception).
     *
     * @return what executed; never {@code null}
     */
    public OperatorRequestReport processOperatorRequests() {
        writeLock.lock();
        try {
            if (!Files.exists(requestFile)) {
                return OperatorRequestReport.none();
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(requestFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("token_ops request at {} is unreadable by the service user; no operation "
                        + "executed (the file must be owned by the service user or mode >= 0640): {}",
                        requestFile, e.toString());
                return OperatorRequestReport.none();
            }
            try {
                Files.delete(requestFile);
            } catch (IOException e) {
                LOG.warn("token_ops request at {} could not be removed; no operation executed "
                        + "(an unremovable request would replay on every start): {}",
                        requestFile, e.toString());
                return OperatorRequestReport.none();
            }
            int rotated = 0;
            int revoked = 0;
            int minted = 0;
            List<String> skipped = new ArrayList<>();
            int lineNumber = 0;
            for (String raw : lines) {
                lineNumber++;
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\s+", 2);
                String verb = parts[0];
                String argument = (parts.length > 1) ? parts[1].strip() : "";
                String where = "line " + lineNumber + ": ";
                // Skipped entries are logged: never echo the operator's text (a raw token
                // pasted where a key id belongs would land in the journal) — only a key
                // id the store already knows, which is public by construction.
                switch (verb) {
                    case "rotate" -> {
                        rotate("operator-rotated-" + clock.instant());
                        rotated++;
                    }
                    case "revoke" -> {
                        if (argument.isEmpty()) {
                            skipped.add(where + "revoke: missing keyId");
                        } else {
                            switch (revoke(argument)) {
                                case REVOKED -> revoked++;
                                // The key id is public by construction (it is in the store).
                                case REFUSED_LAST_FULL_ACCESS -> skipped.add(where + "revoke "
                                        + argument + ": refused — the last active full-access "
                                        + "token (use rotate)");
                                case NOT_FOUND -> {
                                    if (claimsFor(argument).isPresent()) {
                                        skipped.add(where + "revoke " + argument + ": already revoked");
                                    } else {
                                        skipped.add(where
                                                + "revoke: no such keyId (argument not echoed)");
                                    }
                                }
                            }
                        }
                    }
                    case "mint" -> {
                        if (argument.isEmpty()) {
                            skipped.add(where + "mint: missing display name");
                        } else {
                            writeArtifact(mint(argument, List.of(ApiKeyClaims.SCOPE_ALL), null));
                            minted++;
                        }
                    }
                    default -> skipped.add(where + "unknown verb (line not echoed)");
                }
            }
            LOG.warn("token operator request applied: rotated={} revoked={} minted={} skipped={} "
                    + "— a minted token, if any, is at {}",
                    rotated, revoked, minted, skipped, artifactFile);
            return new OperatorRequestReport(rotated, revoked, minted, skipped);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * {@code true} when the {@code initial_api_token} artifact exists and the
     * token it carries no longer validates (revoked, expired, or unknown) — the
     * state a bare {@code revoke} of the pairing key leaves behind. Package-private
     * so the predicate behind {@link #revoke}'s WARN is unit-pinned.
     */
    boolean artifactCarriesDeadToken() {
        if (!Files.exists(artifactFile)) {
            return false;
        }
        String artifactToken;
        try {
            artifactToken = Files.readString(artifactFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            LOG.debug("pairing artifact at {} unreadable during the revoke check: {}",
                    artifactFile, e.toString());
            return false;
        }
        return validate(artifactToken).isEmpty();
    }

    private static TokenRecord revokedCopy(TokenRecord r) {
        return new TokenRecord(r.keyId(), r.displayName(), r.createdAt(),
                r.expiresAt(), r.scopes(), r.siteId(), true);
    }

    /**
     * The resolved path of the store file ({@code configDir/api_tokens}) — for
     * operator output only; the file holds hashes, never tokens.
     *
     * @return the store path; never {@code null}; the file may not exist yet
     */
    public Path storePath() {
        return tokenFile;
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

    /**
     * Rewrites the whole token file ATOMICALLY (the class javadoc's promise, made
     * true by R-6): the previous file survives any failed write. Caller holds the
     * write lock.
     */
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
        writeOwnerOnlyAtomically(tokenFile, sb.toString(), "failed to persist token store at ");
    }

    private void writeArtifact(String rawToken) {
        writeOwnerOnlyAtomically(artifactFile, rawToken + System.lineSeparator(),
                "failed to write initial token artifact at ");
    }

    /**
     * Temp-then-{@code ATOMIC_MOVE} (the {@code AtomicYamlWriter} idiom, same
     * directory only): create the {@code .tmp} sibling — owner-only from the first
     * byte on POSIX — write, fsync, re-assert {@code rw-------}, then rename over
     * {@code target}. A failure at any step removes the temp best-effort and leaves
     * the previous {@code target} byte-identical. Non-POSIX (Windows) skips the
     * permission calls; the packaged config dir's 0700 is the fence, this is the
     * belt (the artifact and the store previously took umask-default 0644).
     */
    private static void writeOwnerOnlyAtomically(Path target, String content, String failurePrefix) {
        Path tmp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        try {
            Files.deleteIfExists(tmp);
            Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = POSIX
                    ? FileChannel.open(tmp, options, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString(OWNER_ONLY)))
                    : FileChannel.open(tmp, options)) {
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (POSIX) {
                // The creation mode is masked by the process umask (a strict umask can strip
                // owner bits too); re-assert after the write so the moved file is exactly
                // rw------- regardless of the umask the service runs under.
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(OWNER_ONLY));
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new UncheckedIOException(failurePrefix + target, e);
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
