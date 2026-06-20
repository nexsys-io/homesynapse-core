/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpaqueTokenStore} (AB-1, the opaque-bearer-token scheme).
 * Verifies mint/validate, hash-at-rest, first-run pairing, persistence across a
 * reopen, revocation, and claims resolution. Clock is injected fixed.
 */
@DisplayName("OpaqueTokenStore -- file-backed opaque bearer tokens")
final class OpaqueTokenStoreTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-19T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path configDir;

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    OpaqueTokenStoreTest() {
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
    @DisplayName("a revoked token no longer validates")
    void revokedTokenRejected() {
        OpaqueTokenStore store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);
        String keyId = store.validate(token).orElseThrow().keyId();

        assertThat(store.revoke(keyId)).isTrue();
        assertThat(store.validate(token)).isEmpty();
        // Re-revoking an already-revoked key reports no change.
        assertThat(store.revoke(keyId)).isFalse();
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
}
