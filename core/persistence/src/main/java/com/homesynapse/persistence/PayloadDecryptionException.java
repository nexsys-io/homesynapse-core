/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Typed, fail-closed error raised when an at-rest-encrypted event row cannot be
 * decrypted on the read path (AB-2 / OR-RF-DECRYPT, Doc 15 §6).
 *
 * <p>The MVP read contract is <strong>fail-closed per read-batch</strong>: a
 * decrypt failure in {@code SqliteEventStore.fromRow} aborts the entire
 * {@code readRows} batch loudly with this exception. There is no per-row
 * degrade (the crypto-shred degrade half is post-MVP, F4-gated on
 * {@code chain_hash} activation), no silent skip, and no silent plaintext
 * fallback (Doc 15 §6). A lost or corrupt root key making the store unreadable
 * is an intentional, visible failure — surfacing it loudly is the correct
 * posture versus INV-RF-04, never quiet corruption.</p>
 *
 * <p>Unchecked by design: it propagates directly through
 * {@code ReadExecutor.execute} (which wraps only checked exceptions), so callers
 * see this type unwrapped on the read path. The {@link #failureKind()}
 * discriminates the four read-side failure modes; AB-2's future degrade seam
 * keys off {@link FailureKind#KEY_ABSENT_OR_DESTROYED} (CASE-a, an intended
 * crypto-shred) versus {@link FailureKind#GCM_AUTH_FAILED} (CASE-b, possible
 * tampering).</p>
 *
 * @see PayloadCipher
 * @see com.homesynapse.event.DegradedEvent
 */
public final class PayloadDecryptionException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * Read-side decrypt failure classification. Mirrors the two
     * {@link PayloadCipher#decrypt} exception cases (CASE-a / CASE-b) plus the
     * store-local pre-conditions that must hold before the cipher is even
     * consulted (no cipher wired, a malformed {@code dek_ref}, or an
     * unrecognized at-rest envelope version).
     */
    public enum FailureKind {
        /** No {@link PayloadCipher} is wired, but an encrypted row was read. */
        NO_CIPHER_WIRED,
        /** The {@code dek_ref} column is not parseable as {@code scope_id:key_version}. */
        MALFORMED_DEK_REF,
        /**
         * The stored at-rest envelope's leading version byte is absent or
         * unrecognized (AB-4 F1, Doc 15 §4.1). The MVP supports exactly one
         * envelope version ({@code v1} = AES-256-GCM); an absent or unknown
         * leading byte is a hard, fail-closed decrypt failure — never an
         * implicit-{@code v1} fallback, and never fed to the cipher. The byte
         * is also bound as GCM AAD, so a stored byte that survives this strict
         * check but was tampered to a different value would additionally fail
         * the auth tag ({@link #GCM_AUTH_FAILED}).
         */
        UNKNOWN_ENVELOPE_VERSION,
        /** GCM authentication failed — corrupt or tampered ciphertext (CASE-b). */
        GCM_AUTH_FAILED,
        /** The decryption key is absent or destroyed — crypto-shred (CASE-a). */
        KEY_ABSENT_OR_DESTROYED
    }

    private final transient FailureKind failureKind;
    private final long globalPosition;
    private final transient String scopeId;
    private final transient Integer keyVersion;

    /**
     * Constructs a fail-closed decrypt error.
     *
     * @param failureKind    the failure classification; never {@code null}
     * @param globalPosition the {@code global_position} of the offending row
     * @param scopeId        the encryption scope parsed from {@code dek_ref},
     *                       or {@code null} if it could not be parsed
     * @param keyVersion     the key version parsed from {@code dek_ref}, or
     *                       {@code null} if it could not be parsed
     * @param message        the Register-C diagnostic message; never {@code null}
     */
    public PayloadDecryptionException(
            FailureKind failureKind,
            long globalPosition,
            String scopeId,
            Integer keyVersion,
            String message) {
        super(message);
        this.failureKind = failureKind;
        this.globalPosition = globalPosition;
        this.scopeId = scopeId;
        this.keyVersion = keyVersion;
    }

    /**
     * Constructs a fail-closed decrypt error wrapping the underlying cipher
     * exception (CASE-a / CASE-b) or a parse failure.
     *
     * @param failureKind    the failure classification; never {@code null}
     * @param globalPosition the {@code global_position} of the offending row
     * @param scopeId        the encryption scope, or {@code null}
     * @param keyVersion     the key version, or {@code null}
     * @param message        the Register-C diagnostic message; never {@code null}
     * @param cause          the underlying cause
     */
    public PayloadDecryptionException(
            FailureKind failureKind,
            long globalPosition,
            String scopeId,
            Integer keyVersion,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failureKind = failureKind;
        this.globalPosition = globalPosition;
        this.scopeId = scopeId;
        this.keyVersion = keyVersion;
    }

    /** @return the failure classification; never {@code null}. */
    public FailureKind failureKind() {
        return failureKind;
    }

    /** @return the {@code global_position} of the row that failed to decrypt. */
    public long globalPosition() {
        return globalPosition;
    }

    /**
     * @return the encryption scope parsed from {@code dek_ref}, or {@code null}
     *         when the {@code dek_ref} could not be parsed
     *         ({@link FailureKind#NO_CIPHER_WIRED} /
     *         {@link FailureKind#MALFORMED_DEK_REF}).
     */
    public String scopeId() {
        return scopeId;
    }

    /**
     * @return the key version parsed from {@code dek_ref}, or {@code null} when
     *         it could not be parsed.
     */
    public Integer keyVersion() {
        return keyVersion;
    }
}
