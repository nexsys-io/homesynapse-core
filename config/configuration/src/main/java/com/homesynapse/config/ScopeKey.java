/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of the {@code scope_keys} store (Doc 15 §8.2, M6.2): a per-scope
 * data-encryption key wrapped by the scope's HKDF-derived KEK.
 *
 * <p>Rows are persisted in the config-side {@code scope_keys.json} file
 * ([R6] — the store mirrors {@code secrets.enc} ownership on the
 * config/key-manager side; the Doc 15 §4.1 SQLite placement is a parked
 * currency nit). The file is correct as plaintext: {@code encryptedDek} is
 * AES-256-GCM ciphertext, unreadable without the root key (DP-3).</p>
 *
 * <p>A non-null {@link #destroyedAt} marks the key crypto-shredded
 * (INV-PD-07): {@link ScopeKeyManager#decrypt} refuses the
 * {@code (scopeId, keyVersion)} pair permanently. The shred operation that
 * sets it is post-MVP; M6.2 carries the field and enforces the
 * unreadability property only.</p>
 *
 * <p>The array components are NOT defensively copied, on construction or
 * on access — rows travel between trusted same-process collaborators
 * (DP-4). Record equality is consequently reference-based on the array
 * components; rows are looked up by {@code (scopeId, keyVersion)}, never
 * compared whole.</p>
 *
 * @param scopeId      the encryption scope this DEK belongs to;
 *                     never {@code null} or blank
 * @param keyVersion   the DEK version within the scope, starting at 1;
 *                     {@code >= 1}
 * @param encryptedDek the 256-bit DEK wrapped by the scope KEK with
 *                     AES-256-GCM; never {@code null}
 * @param iv           the 96-bit GCM IV used for the wrap;
 *                     never {@code null}
 * @param createdAt    when the DEK was generated (injected-clock time);
 *                     never {@code null}
 * @param destroyedAt  when the key was crypto-shredded; {@code null} while
 *                     the key is live
 *
 * @see ScopeKeyManager
 */
public record ScopeKey(
        String scopeId,
        int keyVersion,
        byte[] encryptedDek,
        byte[] iv,
        Instant createdAt,
        Instant destroyedAt
) {

    /**
     * Validates the non-nullable components and the version floor.
     * Arrays are deliberately not copied (DP-4); {@code destroyedAt} is
     * the record's only nullable component.
     */
    public ScopeKey {
        Objects.requireNonNull(scopeId, "scopeId must not be null");
        if (scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException(
                    "keyVersion must be >= 1: " + keyVersion);
        }
        Objects.requireNonNull(encryptedDek, "encryptedDek must not be null");
        Objects.requireNonNull(iv, "iv must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
