/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.util.Objects;

/**
 * Result of one {@link PayloadCipher#encrypt} call (Doc 15 §3.8, M6.2):
 * the encrypted payload bytes, the GCM IV, and the key version that maps
 * to a {@code dek_ref} of {@code scope_id:key_version} on the M6.3 at-rest
 * write path.
 *
 * <p>The shape deliberately mirrors the config module's
 * {@code ScopeCipherResult} field-for-field so the composition-root
 * adapter is a plain copy — but the two types stay distinct because
 * neither module may name the other's (the zero-new-edge property,
 * Doc 15 §3.8 / CARRY 1).</p>
 *
 * <p>The array components are NOT defensively copied, on construction or
 * on access — this record rides the persistence write path between
 * trusted same-process collaborators (DP-5). Callers must not mutate the
 * arrays.</p>
 *
 * @param ciphertext the encrypted bytes with the GCM tag appended;
 *                   never {@code null}
 * @param iv         the 96-bit GCM IV; never {@code null}
 * @param keyVersion the scope-key version that encrypted the ciphertext;
 *                   {@code >= 1}
 *
 * @see PayloadCipher
 */
public record EncryptedPayload(
        byte[] ciphertext,
        byte[] iv,
        int keyVersion
) {

    /**
     * Validates non-null arrays and a positive key version. Arrays are
     * deliberately not copied (DP-5).
     */
    public EncryptedPayload {
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
        Objects.requireNonNull(iv, "iv must not be null");
        if (keyVersion < 1) {
            throw new IllegalArgumentException(
                    "keyVersion must be >= 1: " + keyVersion);
        }
    }
}
