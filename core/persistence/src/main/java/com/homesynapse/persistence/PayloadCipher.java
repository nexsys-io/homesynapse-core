/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Consumer-defined encryption seam for the at-rest event-payload write
 * path (Doc 15 §3.8, M6.2 — the AMD-45 {@code AtomicCheckpointSink}
 * injection pattern applied to a config-supplied implementation).
 *
 * <p>Persistence <em>defines</em> this interface but implements nothing
 * behind it: the real key management ({@code ScopeKeyManager} — root key,
 * HKDF scope KEKs, wrapped DEKs, the {@code scope_keys} store) lives in
 * {@code com.homesynapse.config}, which must not be a static dependency of
 * this module (the AMD-52 cycle class). The composition root
 * ({@code com.homesynapse.app.Main} — the only module requiring both
 * {@code config} and {@code persistence}) wraps the key manager in a thin
 * adapter of this type and threads it through {@code HomeSynapseCore}
 * (lifecycle) into persistence. Net JPMS result: zero new module edges in
 * either direction.</p>
 *
 * <p><strong>M6.3 consumption point.</strong> No persistence code calls
 * this interface in M6.2 — the adapter is constructed, threaded, and held.
 * The M6.3 at-rest write path consumes it to encrypt sensitive-PII scope
 * payloads before the single-writer INSERT (Doc 15 §3.4: {@code payload}
 * ciphertext + {@code payload_iv} + {@code dek_ref}), with the
 * OR-M6-NONCE counter-nonce discipline owned on that path.</p>
 *
 * <p>All signatures are deliberately {@code java.base}-only (DP-5) — this
 * surface must never name a cross-module type, or the zero-new-edge
 * property breaks.</p>
 *
 * <p><strong>AB-4 / F1 — additional authenticated data.</strong> Both
 * operations take an {@code aad} (additional authenticated data) byte array
 * bound into the AES-GCM computation but NOT encrypted. The persistence
 * write/read path uses it to bind the 1-byte at-rest envelope version
 * discriminator (Doc 15 §4.1, AMD-94 F1) into the auth tag — making the
 * version downgrade-resistant — while the version byte itself is framed
 * (prepended + strictly parsed) in the persistence envelope codec, the single
 * place that owns the stored {@code payload} BLOB. The cipher is a pure AEAD
 * primitive: it binds whatever {@code aad} it is handed (an empty array for
 * callers that bind nothing). The encrypt and decrypt {@code aad} MUST match
 * byte-for-byte or GCM authentication fails.</p>
 *
 * @see EncryptedPayload
 */
public interface PayloadCipher {

    /**
     * Encrypts a payload under the named scope's current data-encryption
     * key, binding {@code aad} as AES-GCM additional authenticated data.
     *
     * @param scopeId   the encryption scope (data category);
     *                  never {@code null} or blank
     * @param plaintext the payload bytes to encrypt; never {@code null}
     * @param aad       additional authenticated data bound into the GCM tag
     *                  but not encrypted (AB-4 F1 — the envelope version byte);
     *                  never {@code null}, may be empty to bind nothing
     * @return the ciphertext, IV, and key version; never {@code null}
     */
    EncryptedPayload encrypt(String scopeId, byte[] plaintext, byte[] aad);

    /**
     * Decrypts a payload previously produced by {@link #encrypt} with the same
     * {@code aad}.
     *
     * @param scopeId    the encryption scope; never {@code null} or blank
     * @param keyVersion the key version that encrypted the ciphertext
     * @param ciphertext the ciphertext with the GCM tag appended;
     *                   never {@code null}
     * @param iv         the 96-bit IV; never {@code null}
     * @param aad        the additional authenticated data bound at encrypt
     *                   time (AB-4 F1 — the envelope version byte); never
     *                   {@code null}, may be empty; MUST equal the encrypt-side
     *                   {@code aad} or authentication fails
     * @return the plaintext; never {@code null}
     * @throws IllegalArgumentException if the {@code (scopeId, keyVersion)}
     *         key is absent or destroyed — the ciphertext is permanently
     *         unreadable (crypto-shred, Doc 15 §3.6)
     * @throws IllegalStateException if authentication of the ciphertext
     *         fails (wrong key, tampered ciphertext, or mismatched {@code aad})
     */
    byte[] decrypt(String scopeId, int keyVersion, byte[] ciphertext, byte[] iv,
                   byte[] aad);
}
