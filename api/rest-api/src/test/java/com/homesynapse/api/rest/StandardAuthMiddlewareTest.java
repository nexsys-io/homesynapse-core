/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StandardAuthMiddleware} (AB-1, Doc 09 §12.1): the
 * {@code Authorization: Bearer {token}} contract — 401 for a missing/malformed
 * header, 403 for an invalid/revoked token, the identity for a valid token.
 */
@DisplayName("StandardAuthMiddleware -- bearer token authentication")
final class StandardAuthMiddlewareTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-19T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path configDir;

    private OpaqueTokenStore store;
    private StandardAuthMiddleware middleware;

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    StandardAuthMiddlewareTest() {
    }

    @BeforeEach
    void setUp() {
        store = new OpaqueTokenStore(configDir, FIXED_CLOCK);
        middleware = new StandardAuthMiddleware(store);
    }

    @Test
    @DisplayName("a null/blank/non-Bearer/empty header is 401 AUTHENTICATION_REQUIRED")
    void malformedHeaderIs401() {
        assertAuthenticationRequired(() -> middleware.authenticate(null));
        assertAuthenticationRequired(() -> middleware.authenticate("   "));
        assertAuthenticationRequired(() -> middleware.authenticate("Basic abc123"));
        assertAuthenticationRequired(() -> middleware.authenticate("Bearer "));
        assertAuthenticationRequired(() -> middleware.authenticate("Bearer    "));
    }

    @Test
    @DisplayName("an unknown token is 403 FORBIDDEN")
    void unknownTokenIs403() {
        assertThatThrownBy(() -> middleware.authenticate("Bearer not-a-real-token"))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.problemDetail().type()).isEqualTo(ProblemType.FORBIDDEN));
    }

    @Test
    @DisplayName("a valid token returns the identity (never the raw token); scheme is case-insensitive")
    void validTokenReturnsIdentity() {
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);

        ApiKeyIdentity identity = middleware.authenticate("Bearer " + token);
        assertThat(identity).isNotNull();
        assertThat(identity.keyId()).isNotBlank();
        assertThat(identity.keyId()).isNotEqualTo(token);

        // RFC 6750 scheme name is case-insensitive.
        assertThat(middleware.authenticate("bearer " + token)).isNotNull();
    }

    @Test
    @DisplayName("a revoked token is 403 FORBIDDEN")
    void revokedTokenIs403() {
        String token = store.mint("ops", List.of(ApiKeyClaims.SCOPE_ALL), null);
        store.revoke(middleware.authenticate("Bearer " + token).keyId());

        assertThatThrownBy(() -> middleware.authenticate("Bearer " + token))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.problemDetail().type()).isEqualTo(ProblemType.FORBIDDEN));
    }

    private static void assertAuthenticationRequired(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.problemDetail().type())
                                .isEqualTo(ProblemType.AUTHENTICATION_REQUIRED));
    }
}
