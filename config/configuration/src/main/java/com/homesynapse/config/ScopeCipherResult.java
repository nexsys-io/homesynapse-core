/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.util.Objects;

/**
 * Outcome of one {@link ScopeKeyManager#encrypt} call (Doc 15 §4.2, M6.2):
 * the AES-256-GCM ciphertext, the fresh 96-bit IV it was produced with,
 * and the scope-DEK version that encrypted it.
 *
 * <p>The composition root maps this record onto the persistence-defined
 * {@code EncryptedPayload} seam record (Doc 15 §3.8 / CARRY 1) — the two
 * shapes are deliberately identical so the adapter is a field-for-field
 * copy with no transformation.</p>
 *
 * <p>The array components are NOT defensively copied, on construction or
 * on access — this record rides a hot path between trusted same-process
 * collaborators (DP-4). Callers must not mutate the arrays.</p>
 *
 * @param ciphertext the encrypted bytes with the 128-bit GCM tag appended;
 *                   never {@code null}
 * @param iv         the 96-bit GCM IV; fresh per encryption, never reused;
 *                   never {@code null}
 * @param keyVersion the scope-DEK version that encrypted the ciphertext;
 *                   {@code >= 1}
 *
 * @see ScopeKeyManager
 */
public record ScopeCipherResult(
        byte[] ciphertext,
        byte[] iv,
        int keyVersion
) {

    /**
     * Validates non-null arrays and a positive key version. Arrays are
     * deliberately not copied (DP-4).
     */
    public ScopeCipherResult {
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        Objects.requireNonNull(iv, "iv must not be null");
        if (keyVersion < 1) {
            throw new IllegalArgumentException(
                    "keyVersion must be >= 1: " + keyVersion);
        }
    }
}
