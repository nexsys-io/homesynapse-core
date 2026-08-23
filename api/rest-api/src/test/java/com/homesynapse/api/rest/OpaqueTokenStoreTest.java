/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpaqueTokenStore} (AB-1, the opaque-bearer-token scheme).
 * Verifies mint/validate, hash-at-rest, first-run pairing, persistence across a
 * reopen, revocation, and claims resolution. Clock is injected fixed.
 *
 * <p>R-6 TOKEN-OPS (2026-08-22) adds the operator-path pins: {@code rotate}
 * (all-sessions, one active token after, history kept), {@code summaries()}
 * (never a hash, never a token), the request file ({@code token_ops.request} —
 * consumed exactly once, deleted BEFORE execution, fail-closed when it cannot
 * be read or removed), the atomic owner-only rewrite (no {@code .tmp} residue;
 * a failed write leaves the previous store intact; {@code rw-------} on POSIX),
 * and the stranded-artifact predicate behind {@code revoke}'s WARN. The
 * permission-mechanism tests are POSIX-gated AND non-root-gated via
 * {@link Assumptions} — root ignores directory write bits and container CI may
 * run as root; the desk runs on Windows.</p>
 *
 * <p>R-H2 (R-9, 2026-08-22) adds the last-full-access-token guard:
 * {@code revoke} returns {@link OpaqueTokenStore.RevokeOutcome} and REFUSES to
 * revoke the only active full-access token (the self-lockout class); a scoped
 * token revokes freely, an expired full-access row does not count as active,
 * {@code rotate} is never refused, and the request-file {@code revoke} arm
 * reports the refusal as a {@code skipped} entry. Fixtures that revoke a
 * full-access token therefore mint a second one first — asserting the
 * {@code REVOKED} outcome so a refusal can never make them vacuous.</p>
 */
@DisplayName("OpaqueTokenStore -- file-backed opaque bearer tokens")
final class OpaqueTokenStoreTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-19T00:00:00Z"), ZoneOffset.UTC);

    private static final String SHA256_HEX_RUN = "[0-9a-f]{64}";
    private static final String OWNER_ONLY = "rw-------";

    @TempDir
    Path configDir;

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    OpaqueTokenStoreTest() {
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private Path requestFile() {
        return configDir.resolve(OpaqueTokenStore.OPERATOR_REQUEST_FILE);
    }

    private Path artifactFile() {
        return configDir.resolve(OpaqueTokenStore.INITIAL_TOKEN_ARTIFACT);
    }

    private Path tokenFile() {
        return configDir.resolve(OpaqueTokenStore.TOKEN_FILE_NAME);
    }

    private static String keyIdOf(OpaqueTokenStore store, String rawToken) {
        return store.validate(rawToken).orElseThrow().keyId();
    }

    private static OpaqueTokenStore.OperatorRequestReport zeroReport() {
        return new OpaqueTokenStore.OperatorRequestReport(0, 0, 0, List.of());
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a minted token validates; a different token does not")
    void mintedTokenValidates() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);

        assertThat(store.validate(token)).isPresent();
        assertThat(store.validate("not-the-token")).isEmpty();
        assertThat(store.validate(null)).isEmpty();
        assertThat(store.validate("  ")).isEmpty();
    }

    @Test
    @DisplayName("the raw token is never written to the store file — only its hash")
    void rawTokenNeverPersisted() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);

        String fileContents = Files.readString(configDir.resolve(OpaqueTokenStore.TOKEN_FILE_NAME));
        assertThat(fileContents).doesNotContain(token);
    }

    @Test
    @DisplayName("ensureInitialToken mints once on a fresh store, writes the pairing artifact, "
            + "and is a no-op thereafter")
    void ensureInitialTokenIsIdempotent() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);

        Optional<String> minted = store.ensureInitialToken();
        assertThat(minted).isPresent();
        assertThat(store.validate(minted.get())).isPresent();

        // The one-time pairing artifact carries the raw token for first-run setup.
        Path artifact = configDir.resolve(OpaqueTokenStore.INITIAL_TOKEN_ARTIFACT);
        assertThat(Files.readString(artifact).trim()).isEqualTo(minted.get());

        // A second call mints nothing (a token already exists).
        assertThat(store.ensureInitialToken()).isEmpty();
    }

    @Test
    @DisplayName("tokens survive a reopen — a fresh store over the same dir loads the hashes")
    void tokensPersistAcrossReopen() {
        OpaqueTokenStore first = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = first.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), "site-1");

        OpaqueTokenStore reopened = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        assertThat(reopened.validate(token)).isPresent();
        assertThat(reopened.validate(token).get().keyId())
                .isEqualTo(first.validate(token).get().keyId());
    }

    @Test
    @DisplayName("a revoked token no longer validates — REVOKED while a second full-access token "
            + "is active; an already-revoked or unknown key is NOT_FOUND")
    void revokedTokenRejected() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);
        store.mint("keeper", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String keyId = store.validate(token).orElseThrow().keyId();

        assertThat(store.revoke(keyId)).isEqualTo(OpaqueTokenStore.RevokeOutcome.REVOKED);
        assertThat(store.validate(token)).isEmpty();
        // Re-revoking an already-revoked key reports no change; so does an unknown key.
        assertThat(store.revoke(keyId)).isEqualTo(OpaqueTokenStore.RevokeOutcome.NOT_FOUND);
        assertThat(store.revoke("no-such-key")).isEqualTo(OpaqueTokenStore.RevokeOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("claims are resolvable by key id and carry the granted scopes + site")
    void claimsResolvableByKeyId() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("enterprise", List.of("entities:read", "events:read"), "site-9");
        String keyId = store.validate(token).orElseThrow().keyId();

        Optional<ApiKeyClaims> claims = store.claimsFor(keyId);
        assertThat(claims).isPresent();
        assertThat(claims.get().scopes()).containsExactly("entities:read", "events:read");
        assertThat(claims.get().siteId()).isEqualTo("site-9");
        assertThat(claims.get().grants("entities:read")).isTrue();
        assertThat(claims.get().grants("admin")).isFalse();
    }

    @Test
    @DisplayName("activeKeyCount reflects mints and revocations")
    void activeKeyCountTracksState() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        assertThat(store.activeKeyCount()).isZero();

        String token = store.mint("a", List.of(ApiKeyClaims.SCOPE_ALL), null);
        store.mint("b", List.of(ApiKeyClaims.SCOPE_ALL), null);
        assertThat(store.activeKeyCount()).isEqualTo(2);

        store.revoke(store.validate(token).orElseThrow().keyId());
        assertThat(store.activeKeyCount()).isEqualTo(1);
    }

    // ── R-6 TOKEN-OPS: rotate + summaries ─────────────────────────────

    @Test
    @DisplayName("rotate revokes every other active token, leaves exactly one active, keeps "
            + "the revoked rows as history, and survives a reopen")
    void rotateRevokesEveryOtherActiveToken() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String a = store.mint("a", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String b = store.mint("b", List.of("entities:read"), "site-1");
        String keyA = keyIdOf(store, a);
        String keyB = keyIdOf(store, b);

        String fresh = store.rotate("rotated");

        assertThat(store.validate(a)).isEmpty();
        assertThat(store.validate(b)).isEmpty();
        assertThat(store.validate(fresh)).isPresent();
        assertThat(store.activeKeyCount()).isEqualTo(1);
        assertThat(store.claimsFor(keyIdOf(store, fresh))).hasValueSatisfying(
                claims -> assertThat(claims.fullAccess()).isTrue());

        List<OpaqueTokenStore.TokenSummary> summaries = store.summaries();
        assertThat(summaries).hasSize(3);
        assertThat(summaries.stream().filter(OpaqueTokenStore.TokenSummary::revoked)
                .map(OpaqueTokenStore.TokenSummary::keyId))
                .containsExactlyInAnyOrder(keyA, keyB);
        assertThat(summaries.stream().filter(s -> !s.revoked())
                .map(OpaqueTokenStore.TokenSummary::displayName))
                .containsExactly("rotated");

        OpaqueTokenStore reopened = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        assertThat(reopened.validate(fresh)).isPresent();
        assertThat(reopened.validate(a)).isEmpty();
        assertThat(reopened.activeKeyCount()).isEqualTo(1);
        assertThat(reopened.summaries()).hasSize(3);
    }

    @Test
    @DisplayName("rotate writes the new raw token to the pairing artifact (delivery rides the "
            + "existing path)")
    void rotateWritesTheArtifact() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        store.ensureInitialToken();

        String fresh = store.rotate("rotated");

        assertThat(Files.readString(artifactFile()).trim()).isEqualTo(fresh);
    }

    @Test
    @DisplayName("summaries carry the key id and claims but never a hash or a raw token")
    void summariesNeverExposeHashes() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), "site-9");
        String keyId = keyIdOf(store, token);

        List<OpaqueTokenStore.TokenSummary> summaries = store.summaries();
        String rendered = summaries.toString();

        assertThat(summaries).singleElement().satisfies(s -> {
            assertThat(s.keyId()).isEqualTo(keyId);
            assertThat(s.displayName()).isEqualTo("ops");
            assertThat(s.createdAt()).isEqualTo(FIXED_CLOCK.instant());
            assertThat(s.expiresAt()).isNull();
            assertThat(s.scopes()).containsExactly(ApiKeyClaims.SCOPE_ALL);
            assertThat(s.siteId()).isEqualTo("site-9");
            assertThat(s.revoked()).isFalse();
        });
        assertThat(rendered).contains(keyId);
        assertThat(rendered).doesNotContain(token);
        assertThat(rendered).doesNotContainPattern(SHA256_HEX_RUN);
    }

    // ── R-6 TOKEN-OPS: the operator request file ──────────────────────

    @Test
    @DisplayName("a `rotate` request is consumed exactly once: the file is gone before the "
            + "verb runs, the old token dies, and a second pass is a zero report")
    void operatorRequestRotateIsConsumedOnce() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String old = store.mint("old", List.of(ApiKeyClaims.SCOPE_ALL), null);
        Files.writeString(requestFile(), "rotate\n");

        OpaqueTokenStore.OperatorRequestReport report = store.processOperatorRequests();

        assertThat(report).isEqualTo(new OpaqueTokenStore.OperatorRequestReport(1, 0, 0, List.of()));
        assertThat(Files.exists(requestFile())).isFalse();
        assertThat(store.validate(old)).isEmpty();
        assertThat(store.activeKeyCount()).isEqualTo(1);
        String delivered = Files.readString(artifactFile()).trim();
        assertThat(store.validate(delivered)).hasValueSatisfying(identity ->
                assertThat(identity.displayName())
                        .isEqualTo("operator-rotated-2026-06-19T00:00:00Z"));

        assertThat(store.processOperatorRequests()).isEqualTo(zeroReport());
    }

    @Test
    @DisplayName("`revoke <keyId>` and `mint <name…>` execute; comments and blank lines are "
            + "ignored; a revoke of a key with no active token is a skipped entry")
    void operatorRequestRevokeAndMintVerbs() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String a = store.mint("a", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String keyA = keyIdOf(store, a);
        // mint BEFORE revoke — the doc's single-restart one-liner order; lines execute in
        // order, so the revoke of keyA is no longer the last full-access token (R-H2).
        Files.writeString(requestFile(),
                "# rotation batch\n\n   mint   Ops laptop  \nrevoke " + keyA + "\n");

        OpaqueTokenStore.OperatorRequestReport report = store.processOperatorRequests();

        assertThat(report).isEqualTo(new OpaqueTokenStore.OperatorRequestReport(0, 1, 1, List.of()));
        assertThat(Files.exists(requestFile())).isFalse();
        assertThat(store.validate(a)).isEmpty();
        assertThat(store.activeKeyCount()).isEqualTo(1);
        String delivered = Files.readString(artifactFile()).trim();
        assertThat(store.validate(delivered)).hasValueSatisfying(identity ->
                assertThat(identity.displayName()).isEqualTo("Ops laptop"));
        assertThat(store.claimsFor(keyIdOf(store, delivered))).hasValueSatisfying(
                claims -> assertThat(claims.fullAccess()).isTrue());

        Files.writeString(requestFile(), "revoke " + keyA + "\n");
        OpaqueTokenStore.OperatorRequestReport second = store.processOperatorRequests();
        assertThat(second.revoked()).isZero();
        assertThat(second.skipped()).containsExactly("line 1: revoke " + keyA + ": already revoked");
    }

    @Test
    @DisplayName("an unknown verb or a missing argument is skipped, never fatal; the store is "
            + "untouched and the file is still consumed; the operator's text is never echoed")
    void operatorRequestUnknownVerbIsSkippedNotFatal() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);
        // A raw token pasted where a key id belongs (the operator slip the doc invites —
        // both strings are on screen) must not reach the report, and so not the journal.
        Files.writeString(requestFile(),
                "frobnicate " + token + "\nrevoke\nmint\n\nrevoke " + token + "\n");

        OpaqueTokenStore.OperatorRequestReport report = store.processOperatorRequests();

        assertThat(report.rotated()).isZero();
        assertThat(report.revoked()).isZero();
        assertThat(report.minted()).isZero();
        assertThat(report.skipped()).containsExactly(
                "line 1: unknown verb (line not echoed)",
                "line 2: revoke: missing keyId",
                "line 3: mint: missing display name",
                "line 5: revoke: no such keyId (argument not echoed)");
        assertThat(report.toString()).doesNotContain(token);
        assertThat(Files.exists(requestFile())).isFalse();
        assertThat(store.validate(token)).isPresent();
        assertThat(store.activeKeyCount()).isEqualTo(1);
        assertThat(Files.exists(artifactFile())).isFalse();
    }

    @Test
    @DisplayName("delete-BEFORE-execute, pinned on every platform: a persist failure mid-batch "
            + "fails loudly, the store is intact, and the request is already gone (never replayed)")
    void operatorRequestIsDeletedBeforeExecutionEvenWhenTheBatchFails() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);
        byte[] before = Files.readAllBytes(tokenFile());
        Files.writeString(requestFile(), "rotate\n");
        // The non-empty-directory blocker at the temp path makes rotate's persist() fail.
        Path blocker = tokenFile().resolveSibling("api_tokens.tmp");
        Files.createDirectories(blocker.resolve("occupant"));
        Files.writeString(blocker.resolve("occupant").resolve("x"), "x");

        assertThatThrownBy(store::processOperatorRequests)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("failed to persist token store at");

        // An implementation that deletes AFTER executing would leave the file (and replay
        // the rotation on every start); the contract deletes first.
        assertThat(Files.exists(requestFile())).isFalse();
        assertThat(Files.readAllBytes(tokenFile())).isEqualTo(before);
        OpaqueTokenStore reopened = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        assertThat(reopened.validate(token)).isPresent();
        assertThat(reopened.activeKeyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("mint rejects a blank scope or one containing the persisted delimiter — such a "
            + "scope would persist as one value and reload as two")
    void mintRejectsScopesContainingTheDelimiter() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);

        assertThatThrownBy(() -> store.mint("x", List.of("entities:read,*"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope delimiter");
        assertThatThrownBy(() -> store.mint("x", List.of(" "), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.summaries()).isEmpty();
        assertThat(Files.exists(tokenFile())).isFalse();
    }

    @Test
    @DisplayName("a request the service cannot DELETE executes nothing and stays in place "
            + "(fail closed — an unremovable request would replay every start)")
    void operatorRequestUndeletableFileExecutesNothing() throws Exception {
        Assumptions.assumeTrue(posix(), "directory write bits are a POSIX mechanism");
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);
        Files.writeString(requestFile(), "rotate\n");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(configDir);
        Files.setPosixFilePermissions(configDir, PosixFilePermissions.fromString("r-x------"));
        try {
            Assumptions.assumeFalse(Files.isWritable(configDir),
                    "root ignores directory write bits — the delete cannot be made to fail");

            OpaqueTokenStore.OperatorRequestReport report = store.processOperatorRequests();

            assertThat(report).isEqualTo(zeroReport());
            assertThat(Files.exists(requestFile())).isTrue();
            assertThat(store.validate(token)).isPresent();
            assertThat(store.activeKeyCount()).isEqualTo(1);
        } finally {
            Files.setPosixFilePermissions(configDir, original);
        }
    }

    @Test
    @DisplayName("a request the service cannot READ executes nothing and is NOT deleted "
            + "(the operator fixes ownership/mode and restarts)")
    void operatorRequestUnreadableFileExecutesNothing() throws Exception {
        Assumptions.assumeTrue(posix(), "file read bits are a POSIX mechanism");
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);
        Files.writeString(requestFile(), "rotate\n");
        Files.setPosixFilePermissions(requestFile(), PosixFilePermissions.fromString("---------"));
        try {
            Assumptions.assumeFalse(Files.isReadable(requestFile()),
                    "root ignores file read bits — the read cannot be made to fail");

            OpaqueTokenStore.OperatorRequestReport report = store.processOperatorRequests();

            assertThat(report).isEqualTo(zeroReport());
            assertThat(Files.exists(requestFile())).isTrue();
            assertThat(store.validate(token)).isPresent();
        } finally {
            Files.setPosixFilePermissions(requestFile(), PosixFilePermissions.fromString("rw-------"));
        }
    }

    // ── R-6 hardening: atomic owner-only persistence ──────────────────

    @Test
    @DisplayName("persist is temp-then-move: no .tmp sibling survives a mint and a reopen parses "
            + "every row")
    void persistIsAtomic() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String a = store.mint("a", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String b = store.mint("b", List.of("entities:read"), "site-2");
        store.mint("c", List.of(ApiKeyClaims.SCOPE_ALL), null);
        assertThat(store.revoke(keyIdOf(store, a))).isEqualTo(OpaqueTokenStore.RevokeOutcome.REVOKED);

        assertThat(Files.exists(tokenFile().resolveSibling("api_tokens.tmp"))).isFalse();
        assertThat(Files.exists(tokenFile())).isTrue();

        OpaqueTokenStore reopened = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        assertThat(reopened.summaries()).hasSize(3);
        assertThat(reopened.validate(a)).isEmpty();
        assertThat(reopened.validate(b)).hasValueSatisfying(identity ->
                assertThat(identity.keyId()).isEqualTo(keyIdOf(store, b)));
        assertThat(reopened.claimsFor(keyIdOf(store, b))).hasValueSatisfying(claims -> {
            assertThat(claims.scopes()).containsExactly("entities:read");
            assertThat(claims.siteId()).isEqualTo("site-2");
        });
    }

    @Test
    @DisplayName("on POSIX the store file AND the pairing artifact are rw------- (umask-default "
            + "0644 was the pre-R-6 state)")
    void storeAndArtifactAreOwnerOnlyOnPosix() throws Exception {
        Assumptions.assumeTrue(posix(), "file modes are a POSIX mechanism");
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);

        store.ensureInitialToken();

        assertThat(Files.getPosixFilePermissions(tokenFile()))
                .isEqualTo(PosixFilePermissions.fromString(OWNER_ONLY));
        assertThat(Files.getPosixFilePermissions(artifactFile()))
                .isEqualTo(PosixFilePermissions.fromString(OWNER_ONLY));
        assertThat(Files.exists(artifactFile().resolveSibling("initial_api_token.tmp"))).isFalse();
    }

    @Test
    @DisplayName("a failed rewrite leaves the previous store byte-identical (the directory-"
            + "blocker at the temp path stops the mechanism before the move)")
    void persistFailureLeavesPreviousStoreIntact() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String a = store.mint("a", List.of(ApiKeyClaims.SCOPE_ALL), null);
        byte[] before = Files.readAllBytes(tokenFile());
        // A NON-EMPTY directory at the temp path: deleteIfExists/open both fail on every
        // platform, so the write dies BEFORE any move can touch the real file.
        Path blocker = tokenFile().resolveSibling("api_tokens.tmp");
        Files.createDirectories(blocker.resolve("occupant"));
        Files.writeString(blocker.resolve("occupant").resolve("x"), "x");

        assertThatThrownBy(() -> store.mint("b", List.of(ApiKeyClaims.SCOPE_ALL), null))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("failed to persist token store at");

        assertThat(Files.readAllBytes(tokenFile())).isEqualTo(before);
        OpaqueTokenStore reopened = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        assertThat(reopened.validate(a)).isPresent();
        assertThat(reopened.activeKeyCount()).isEqualTo(1);
        assertThat(reopened.summaries()).hasSize(1);
    }

    @Test
    @DisplayName("revoking the key the pairing artifact carries strands the artifact (the "
            + "predicate behind revoke's WARN); mint/rotate refresh it")
    void revokingTheArtifactTokenIsFlagged() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        assertThat(store.artifactCarriesDeadToken()).as("no artifact yet").isFalse();
        String pairing = store.ensureInitialToken().orElseThrow();
        assertThat(store.artifactCarriesDeadToken()).as("fresh artifact validates").isFalse();
        // A second full-access token so the revoke below is a real revoke (R-H2).
        store.mint("keeper", List.of(ApiKeyClaims.SCOPE_ALL), null);

        assertThat(store.revoke(keyIdOf(store, pairing)))
                .isEqualTo(OpaqueTokenStore.RevokeOutcome.REVOKED);
        assertThat(store.artifactCarriesDeadToken()).as("artifact token revoked").isTrue();

        store.rotate("fresh");
        assertThat(store.artifactCarriesDeadToken()).as("rotate rewrote the artifact").isFalse();
    }

    // ── R-H2 (R-9, 2026-08-22): the last-full-access-token guard ───────

    @Test
    @DisplayName("revoke REFUSES the last active full-access token: nothing persisted (the store "
            + "file is byte-identical), the token still validates, the row stays active")
    void revokeRefusesTheLastActiveFullAccessToken() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String only = store.mint("only", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String keyId = keyIdOf(store, only);
        byte[] before = Files.readAllBytes(tokenFile());

        assertThat(store.revoke(keyId))
                .isEqualTo(OpaqueTokenStore.RevokeOutcome.REFUSED_LAST_FULL_ACCESS);

        assertThat(Files.readAllBytes(tokenFile())).isEqualTo(before);
        assertThat(store.validate(only)).isPresent();
        assertThat(store.activeKeyCount()).isEqualTo(1);
        assertThat(store.summaries()).singleElement()
                .satisfies(s -> assertThat(s.revoked()).isFalse());
        // A scoped sibling does not change the verdict — it grants nothing administrative.
        store.mint("reader", List.of("entities:read"), null);
        assertThat(store.revoke(keyId))
                .isEqualTo(OpaqueTokenStore.RevokeOutcome.REFUSED_LAST_FULL_ACCESS);
        assertThat(store.validate(only)).isPresent();
    }

    @Test
    @DisplayName("a scoped (non-*) token revokes even when it is the last token of any kind — the "
            + "guard is about full access only")
    void scopedTokenRevokesEvenWhenLast() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String scoped = store.mint("reader", List.of("entities:read"), "site-1");

        assertThat(store.revoke(keyIdOf(store, scoped)))
                .isEqualTo(OpaqueTokenStore.RevokeOutcome.REVOKED);

        assertThat(store.validate(scoped)).isEmpty();
        assertThat(store.activeKeyCount()).isZero();
    }

    @Test
    @DisplayName("an EXPIRED full-access row does not count as active (the activeKeyCount "
            + "predicate): the last LIVE full-access token is still refused; the expired row "
            + "itself revokes freely")
    void expiredFullAccessRowDoesNotRescueTheLastLiveOne() throws Exception {
        // mint() never sets an expiry, so the expired row is written in the store's own
        // documented format (8 tab-separated fields, Base64'd free text, -1 = never expires).
        Instant now = FIXED_CLOCK.instant();
        String expiredRow = String.join("\t",
                "expiredKey", "0".repeat(64), b64("expired"),
                Long.toString(now.minusSeconds(3600).toEpochMilli()),
                Long.toString(now.minusSeconds(60).toEpochMilli()),
                b64(ApiKeyClaims.SCOPE_ALL), "", "0") + "\n";
        Files.writeString(tokenFile(), expiredRow);
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        assertThat(store.summaries()).hasSize(1);
        assertThat(store.activeKeyCount()).isZero();
        String live = store.mint("live", List.of(ApiKeyClaims.SCOPE_ALL), null);
        assertThat(store.activeKeyCount()).isEqualTo(1);

        assertThat(store.revoke(keyIdOf(store, live)))
                .isEqualTo(OpaqueTokenStore.RevokeOutcome.REFUSED_LAST_FULL_ACCESS);

        assertThat(store.validate(live)).isPresent();
        assertThat(store.revoke("expiredKey")).isEqualTo(OpaqueTokenStore.RevokeOutcome.REVOKED);
        assertThat(store.summaries()).filteredOn(s -> s.keyId().equals("expiredKey"))
                .singleElement().satisfies(s -> assertThat(s.revoked()).isTrue());
    }

    @Test
    @DisplayName("rotate is never refused: on a single-token store it mints first and leaves "
            + "exactly one active token (it revokes via revokedCopy, never through revoke())")
    void rotateIsNeverRefusedOnASingleTokenStore() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String only = store.mint("only", List.of(ApiKeyClaims.SCOPE_ALL), null);

        String fresh = store.rotate("rotated");

        assertThat(store.validate(only)).isEmpty();
        assertThat(store.validate(fresh)).isPresent();
        assertThat(store.activeKeyCount()).isEqualTo(1);
        assertThat(store.summaries()).hasSize(2);
    }

    @Test
    @DisplayName("the request file's `revoke` of the last full-access key is a skipped entry "
            + "(revoked=0), the token stays live, the file is still consumed")
    void operatorRequestRevokeOfTheLastFullAccessKeyIsSkipped() throws Exception {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String only = store.mint("only", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String keyId = keyIdOf(store, only);
        Files.writeString(requestFile(), "revoke " + keyId + "\n");

        OpaqueTokenStore.OperatorRequestReport report = store.processOperatorRequests();

        assertThat(report.revoked()).isZero();
        assertThat(report.skipped()).containsExactly(
                "line 1: revoke " + keyId + ": refused — the last active full-access token (use rotate)");
        assertThat(Files.exists(requestFile())).isFalse();
        assertThat(store.validate(only)).isPresent();
        assertThat(store.activeKeyCount()).isEqualTo(1);
    }
}
