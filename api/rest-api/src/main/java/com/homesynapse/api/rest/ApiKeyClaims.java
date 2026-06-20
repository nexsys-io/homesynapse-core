/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.List;
import java.util.Objects;

/**
 * Per-token authorization claims resolved server-side and keyed by the token's
 * {@code key_id} (Doc 09 §12.1, the opaque-bearer-token scheme — A2 ruling
 * 2026-06-19).
 *
 * <p><strong>Enterprise hook, designed now — MVP enforcement is binary.</strong>
 * At Tier 1 a valid token grants access to every external route; the auth filter
 * does not consult these claims. The record exists so the per-scope / per-site
 * authorization model (the enterprise tier) can be layered on additively without
 * reshaping the wire contract or the token store: {@link AuthMiddleware} resolves
 * the {@link ApiKeyIdentity} for the auth decision, and the claims are attached
 * separately (kept out of {@link ApiKeyIdentity}, which is shared with the
 * WebSocket module). MVP mints a single full-access claim
 * ({@code scopes = ["*"]}).</p>
 *
 * <p>Claims are never derived from the request — they are bound to the token at
 * mint time and held in the token store. The raw token is never represented here.</p>
 *
 * <p>Thread-safe (immutable record with an unmodifiable list).</p>
 *
 * @param scopes the granted scope identifiers; {@code ["*"]} denotes full access
 *               (the MVP claim). Never {@code null}; unmodifiable.
 * @param siteId the site/installation this token is scoped to (enterprise
 *               multi-site hook), or {@code null} for an unscoped token.
 *
 * @see AuthMiddleware
 * @see ApiKeyIdentity
 * @see OpaqueTokenStore
 */
public record ApiKeyClaims(List<String> scopes, String siteId) {

    /** The MVP full-access scope token. */
    public static final String SCOPE_ALL = "*";

    /**
     * Normalizes the scope list to an unmodifiable copy (empty if {@code null}).
     * {@code siteId} is left as-is (nullable).
     */
    public ApiKeyClaims {
        scopes = (scopes == null) ? List.of() : List.copyOf(scopes);
    }

    /**
     * @return {@code true} if these claims grant full access ({@link #SCOPE_ALL}
     *         is present) — the MVP default. Not consulted at Tier 1 (binary
     *         enforcement); the enterprise tier will gate per-route on the scope
     *         set.
     */
    public boolean fullAccess() {
        return scopes.contains(SCOPE_ALL);
    }

    /** @return a full-access, unscoped claim — the MVP single-claim default. */
    public static ApiKeyClaims fullAccess(String siteId) {
        return new ApiKeyClaims(List.of(SCOPE_ALL), siteId);
    }

    /**
     * @param scope the scope identifier to test
     * @return {@code true} if these claims include {@code scope} or full access
     */
    public boolean grants(String scope) {
        Objects.requireNonNull(scope, "scope");
        return fullAccess() || scopes.contains(scope);
    }
}
