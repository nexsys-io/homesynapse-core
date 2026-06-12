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
 * @see EncryptedPayload
 */
public interface PayloadCipher {

    /**
     * Encrypts a payload under the named scope's current data-encryption
     * key.
     *
     * @param scopeId   the encryption scope (data category);
     *                  never {@code null} or blank
     * @param plaintext the payload bytes to encrypt; never {@code null}
     * @return the ciphertext, IV, and key version; never {@code null}
     */
    EncryptedPayload encrypt(String scopeId, byte[] plaintext);

    /**
     * Decrypts a payload previously produced by {@link #encrypt}.
     *
     * @param scopeId    the encryption scope; never {@code null} or blank
     * @param keyVersion the key version that encrypted the ciphertext
     * @param ciphertext the ciphertext with the GCM tag appended;
     *                   never {@code null}
     * @param iv         the 96-bit IV; never {@code null}
     * @return the plaintext; never {@code null}
     * @throws IllegalArgumentException if the {@code (scopeId, keyVersion)}
     *         key is absent or destroyed — the ciphertext is permanently
     *         unreadable (crypto-shred, Doc 15 §3.6)
     * @throws IllegalStateException if authentication of the ciphertext
     *         fails
     */
    byte[] decrypt(String scopeId, int keyVersion, byte[] ciphertext, byte[] iv);
}
