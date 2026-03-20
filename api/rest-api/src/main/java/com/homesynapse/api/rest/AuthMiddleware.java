/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

/**
 * Extracts and validates API keys from the HTTP Authorization header.
 *
 * <p>Every API request must pass through authentication (INV-SE-02). No endpoint
 * is accessible without a valid API key — there is no "local trust" exception for
 * LAN clients (Doc 09 §12.1).</p>
 *
 * <p>The middleware validates the raw key value from the
 * {@code Authorization: Bearer {key}} header against bcrypt hashes in the key store.
 * The raw key value is never stored or logged — only the bcrypt hash exists in
 * persistent storage.</p>
 *
 * <p>Thread-safe. Implementations must support concurrent invocation from multiple
 * virtual threads.</p>
 *
 * @see ApiKeyIdentity
 * @see ApiRequest
 * @see ProblemType#AUTHENTICATION_REQUIRED
 * @see ProblemType#FORBIDDEN
 * @see <a href="Doc 09 §12.1">Authentication</a>
 */
public interface AuthMiddleware {

    /**
     * Authenticates the caller from the HTTP Authorization header.
     *
     * @param authorizationHeader the raw {@code Authorization} header value
     *                            (e.g., {@code "Bearer hs_abc123..."}),
     *                            never {@code null}
     * @return the authenticated caller identity, never {@code null}
     * @throws ApiException with {@link ProblemType#AUTHENTICATION_REQUIRED} (401) if
     *                      the header is missing or malformed
     * @throws ApiException with {@link ProblemType#FORBIDDEN} (403) if the key is
     *                      invalid, expired, or revoked
     */
    ApiKeyIdentity authenticate(String authorizationHeader) throws ApiException;
}
