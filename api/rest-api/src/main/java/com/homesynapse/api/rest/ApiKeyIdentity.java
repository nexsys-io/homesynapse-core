/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.time.Instant;
import java.util.Objects;

/**
 * Authenticated caller identity extracted from the API key authentication process.
 *
 * <p>Every authenticated API request carries an {@code ApiKeyIdentity} that identifies
 * the caller (Doc 09 §8.2, §12.1). The identity is extracted by {@link AuthMiddleware}
 * from the {@code Authorization: Bearer {key}} request header and attached to the
 * {@link ApiRequest} before it reaches endpoint handlers.</p>
 *
 * <p><strong>Security invariant:</strong> The raw API key value is never stored, returned,
 * or included in this record. Only the bcrypt hash exists in the key store. The
 * {@link #keyId()} is the key's public identifier, used for rate limiting, structured
 * logging, and audit trails.</p>
 *
 * <p>Thread-safe (immutable record).</p>
 *
 * @param keyId       the key's public identifier (never the raw key value),
 *                    never {@code null}
 * @param displayName the human-readable name set at key creation time,
 *                    never {@code null}
 * @param createdAt   the instant when the API key was created, never {@code null}
 *
 * @see AuthMiddleware
 * @see ApiRequest
 * @see RateLimiter
 * @see <a href="Doc 09 §12.1">Authentication</a>
 */
public record ApiKeyIdentity(String keyId, String displayName, Instant createdAt) {

    /**
     * Creates a new API key identity with validation of required fields.
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public ApiKeyIdentity {
        Objects.requireNonNull(keyId, "keyId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
