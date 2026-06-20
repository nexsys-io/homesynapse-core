/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.Objects;

/**
 * Production {@link AuthMiddleware} for the opaque-bearer-token scheme (A2 ruling,
 * 2026-06-19): validates an {@code Authorization: Bearer {token}} header against
 * the local {@link OpaqueTokenStore}.
 *
 * <p>Behavioral contract (Doc 09 §12.1):</p>
 * <ul>
 *   <li>Missing / blank / non-{@code Bearer} header →
 *       {@link ProblemType#AUTHENTICATION_REQUIRED} (401, {@code WWW-Authenticate:
 *       Bearer} is set by the filter's exception handler).</li>
 *   <li>Well-formed header but absent / revoked / expired token →
 *       {@link ProblemType#FORBIDDEN} (403).</li>
 *   <li>Valid token → the {@link ApiKeyIdentity} (today {@code keyId},
 *       {@code displayName}, {@code createdAt} — never the raw token). The
 *       enterprise per-scope/per-site claims are resolved separately via
 *       {@link OpaqueTokenStore#claimsFor(String)}; MVP enforcement is binary
 *       (a valid token grants access — Doc 09/10 Tier 1).</li>
 * </ul>
 *
 * <p>The raw token is never logged (reference is by {@code key_id} only). Stateless
 * beyond the immutable store reference; safe for concurrent virtual-thread
 * invocation (LTD-11 — the store uses {@code ReentrantLock}/concurrent structures,
 * never {@code synchronized}).</p>
 *
 * @see AuthMiddleware
 * @see OpaqueTokenStore
 * @see ApiKeyClaims
 */
public final class StandardAuthMiddleware implements AuthMiddleware {

    /** RFC 6750 bearer scheme prefix (the scheme name is matched case-insensitively). */
    private static final String BEARER_PREFIX = "Bearer ";

    private final OpaqueTokenStore tokenStore;

    /**
     * @param tokenStore the local opaque-token store; never {@code null}
     */
    public StandardAuthMiddleware(OpaqueTokenStore tokenStore) {
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
    }

    @Override
    public ApiKeyIdentity authenticate(String authorizationHeader) throws ApiException {
        if (authorizationHeader == null || authorizationHeader.isBlank()
                || !authorizationHeader.regionMatches(
                        true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw RestFilters.problem(ProblemType.AUTHENTICATION_REQUIRED,
                    "missing or malformed Authorization header; expected "
                            + "'Authorization: Bearer {token}'");
        }
        String rawToken = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            throw RestFilters.problem(ProblemType.AUTHENTICATION_REQUIRED,
                    "empty bearer token");
        }
        return tokenStore.validate(rawToken).orElseThrow(() ->
                RestFilters.problem(ProblemType.FORBIDDEN,
                        "the supplied bearer token is invalid, expired, or revoked"));
    }
}
