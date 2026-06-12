/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * RFC 5869 HKDF over HMAC-SHA256 — the scope-KEK derivation primitive
 * (Doc 15 §4.2: {@code KEK = HKDF(root, "scope:" + scopeId)}).
 *
 * <p>Hand-rolled per DP-2: JDK 21 has no HKDF API (it arrives with JEP 478
 * in JDK 24+) and the dependency catalog gains nothing for this WU —
 * extract-and-expand is ~30 lines over {@link Mac}. Correctness is pinned
 * by the RFC 5869 Appendix A.1/A.2 test vectors in {@code HkdfTest}.</p>
 *
 * <p>Stateless and thread-safe; a fresh {@link Mac} instance is used per
 * operation ({@code Mac} instances are not thread-safe — the LTD-11
 * confinement discipline).</p>
 */
final class Hkdf {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** SHA-256 output length — RFC 5869's {@code HashLen}. */
    private static final int HASH_LENGTH_BYTES = 32;

    private Hkdf() {
        // Utility class — non-instantiable
    }

    /**
     * RFC 5869 §2.2 extract step: {@code PRK = HMAC-Hash(salt, IKM)}.
     *
     * @param salt optional salt; {@code null} or empty means the RFC's
     *             default of {@code HashLen} zero bytes
     * @param ikm  input keying material; never {@code null}
     * @return the 32-byte pseudorandom key
     */
    static byte[] extract(byte[] salt, byte[] ikm) {
        Objects.requireNonNull(ikm, "ikm must not be null");
        byte[] effectiveSalt = (salt == null || salt.length == 0)
                ? new byte[HASH_LENGTH_BYTES]
                : salt;
        return hmac(effectiveSalt, ikm);
    }

    /**
     * RFC 5869 §2.3 expand step:
     * {@code T(n) = HMAC-Hash(PRK, T(n-1) | info | n)}, concatenated and
     * truncated to {@code length} bytes.
     *
     * @param prk    the pseudorandom key from {@link #extract};
     *               never {@code null}
     * @param info   optional context bytes; {@code null} means empty
     * @param length output length in bytes; {@code 1..255 * HashLen}
     * @return {@code length} bytes of output keying material
     * @throws IllegalArgumentException if {@code length} is out of the
     *         RFC's range
     */
    static byte[] expand(byte[] prk, byte[] info, int length) {
        Objects.requireNonNull(prk, "prk must not be null");
        if (length < 1 || length > 255 * HASH_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "HKDF output length must be in [1, " + 255 * HASH_LENGTH_BYTES
                            + "]: " + length);
        }
        byte[] infoBytes = info == null ? new byte[0] : info;
        byte[] okm = new byte[length];
        byte[] block = new byte[0];
        int copied = 0;
        int counter = 1;
        while (copied < length) {
            Mac mac = macFor(prk);
            mac.update(block);
            mac.update(infoBytes);
            mac.update((byte) counter);
            block = mac.doFinal();
            int chunk = Math.min(block.length, length - copied);
            System.arraycopy(block, 0, okm, copied, chunk);
            copied += chunk;
            counter++;
        }
        return okm;
    }

    /**
     * Convenience extract-then-expand in one call.
     *
     * @param ikm    input keying material; never {@code null}
     * @param salt   optional salt; {@code null} or empty means
     *               {@code HashLen} zero bytes
     * @param info   optional context bytes; {@code null} means empty
     * @param length output length in bytes
     * @return {@code length} bytes of output keying material
     */
    static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        return expand(extract(salt, ikm), info, length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        Mac mac = macFor(key);
        return mac.doFinal(data);
    }

    private static Mac macFor(byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is mandatory in every conformant JRE; an init
            // failure here means a broken runtime, not caller error.
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }
}
