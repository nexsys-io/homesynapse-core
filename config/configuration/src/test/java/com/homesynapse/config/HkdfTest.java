/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link Hkdf} — the hand-rolled RFC 5869 HKDF-SHA256 (DP-2).
 *
 * <p>Correctness is pinned by the RFC 5869 Appendix A.1 and A.2 test
 * vectors verbatim: both the intermediate PRK (extract) and the OKM
 * (expand) are asserted, so a defect in either step is attributed
 * precisely.</p>
 */
@DisplayName("Hkdf (RFC 5869 HKDF-SHA256, Doc 15 §4.2 DP-2)")
class HkdfTest {

    private static final HexFormat HEX = HexFormat.of();

    /** Creates a new test instance. */
    HkdfTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // RFC 5869 Appendix A.1 — basic test case with SHA-256
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A.1: extract produces the published PRK")
    void a1ExtractMatchesRfcVector() {
        byte[] ikm = HEX.parseHex("0b".repeat(22));
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");

        byte[] prk = Hkdf.extract(salt, ikm);

        assertThat(HEX.formatHex(prk)).isEqualTo(
                "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
    }

    @Test
    @DisplayName("A.1: expand produces the published 42-byte OKM")
    void a1ExpandMatchesRfcVector() {
        byte[] prk = HEX.parseHex(
                "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] okm = Hkdf.expand(prk, info, 42);

        assertThat(HEX.formatHex(okm)).isEqualTo(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865");
    }

    // ──────────────────────────────────────────────────────────────────
    // RFC 5869 Appendix A.2 — longer inputs/outputs (multi-block expand)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A.2: extract produces the published PRK")
    void a2ExtractMatchesRfcVector() {
        byte[] ikm = sequentialBytes(0x00, 80);
        byte[] salt = sequentialBytes(0x60, 80);

        byte[] prk = Hkdf.extract(salt, ikm);

        assertThat(HEX.formatHex(prk)).isEqualTo(
                "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244");
    }

    @Test
    @DisplayName("A.2: expand produces the published 82-byte OKM (3 HMAC blocks)")
    void a2ExpandMatchesRfcVector() {
        byte[] prk = HEX.parseHex(
                "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244");
        byte[] info = sequentialBytes(0xb0, 80);

        byte[] okm = Hkdf.expand(prk, info, 82);

        assertThat(HEX.formatHex(okm)).isEqualTo(
                "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c"
                        + "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db7"
                        + "1cc30c58179ec3e87c14c01d5c1f3434f1d87");
    }

    // ──────────────────────────────────────────────────────────────────
    // Derivation properties the key hierarchy relies on
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("derive is extract-then-expand (A.1 end to end)")
    void deriveComposesExtractAndExpand() {
        byte[] ikm = HEX.parseHex("0b".repeat(22));
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] okm = Hkdf.derive(ikm, salt, info, 42);

        assertThat(HEX.formatHex(okm)).isEqualTo(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865");
    }

    @Test
    @DisplayName("different scope info derives different keys (Doc 15 §4.2)")
    void differentInfoDerivesDifferentKeys() {
        byte[] root = new byte[32];

        byte[] alpha = Hkdf.derive(root, null,
                "scope:alpha".getBytes(StandardCharsets.UTF_8), 32);
        byte[] beta = Hkdf.derive(root, null,
                "scope:beta".getBytes(StandardCharsets.UTF_8), 32);

        assertThat(alpha).isNotEqualTo(beta);
    }

    @Test
    @DisplayName("expand rejects lengths outside [1, 255 * HashLen]")
    void expandRejectsOutOfRangeLength() {
        byte[] prk = new byte[32];

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Hkdf.expand(prk, null, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Hkdf.expand(prk, null, 255 * 32 + 1));
    }

    /** {@code count} bytes counting up from {@code from} (the A.2 inputs). */
    private static byte[] sequentialBytes(int from, int count) {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            bytes[i] = (byte) (from + i);
        }
        return bytes;
    }

    /** Guards the test helper itself — A.2's info runs 0xb0..0xff. */
    @Test
    @DisplayName("sequentialBytes helper produces the documented ranges")
    void sequentialBytesHelperIsCorrect() {
        byte[] bytes = sequentialBytes(0xb0, 80);

        assertThat(bytes[0]).isEqualTo((byte) 0xb0);
        assertThat(new BigInteger(1, new byte[] {bytes[79]}).intValue())
                .isEqualTo(0xff);
    }
}
