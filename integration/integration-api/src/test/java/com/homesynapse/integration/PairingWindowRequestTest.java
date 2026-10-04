/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T13 (J2b) — {@link PairingWindowRequest}'s optional scope: {@code null} is the
 * un-scoped window (PJ-2's behavior, unchanged); a non-null scope must be
 * {@code 0x} + 16 hex digits and is CANONICALIZED to {@code 0x} + upper-case —
 * the ONE canonicalizer on the path (the endpoint passes the text through; the
 * window, the event of record and the REST view carry this form).
 */
@DisplayName("PairingWindowRequest — the optional device scope (J2b)")
class PairingWindowRequestTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    PairingWindowRequestTest() {
        // Defaults are sufficient.
    }

    @Test
    @DisplayName("a null scope is the un-scoped window — the three PJ-2 validations stand")
    void nullScope_isUnscoped_pj2ValidationsStand() {
        PairingWindowRequest request = new PairingWindowRequest(120, "pair", "key-01", null);
        assertThat(request.scope()).isNull();
        assertThat(request.durationSeconds()).isEqualTo(120);
        assertThatThrownBy(() -> new PairingWindowRequest(0, "pair", "key-01", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PairingWindowRequest(120, " ", "key-01", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PairingWindowRequest(120, "pair", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a valid scope is canonicalized: lower-case hex and an upper-case X prefix "
            + "both read back as 0x + 16 upper-case hex; the canonical form is a fixed point")
    void validScope_canonicalized() {
        assertThat(new PairingWindowRequest(120, "pair", "key-01", "0x00124b0012345678").scope())
                .isEqualTo("0x00124B0012345678");
        assertThat(new PairingWindowRequest(120, "pair", "key-01", "0X00124B00aabbccdd").scope())
                .isEqualTo("0x00124B00AABBCCDD");
        assertThat(new PairingWindowRequest(120, "pair", "key-01", "0x00124B0012345678").scope())
                .isEqualTo("0x00124B0012345678");
    }

    @Test
    @DisplayName("a non-matching scope is rejected at construction — short, long, non-hex, "
            + "un-prefixed, blank — with the one problem text")
    void invalidScope_rejected() {
        for (String bad : new String[] {"0x123", "0x00124B00123456789", "0x00124B001234567G",
                "00124B0012345678", "", " ", "0x"}) {
            assertThatThrownBy(() -> new PairingWindowRequest(120, "pair", "key-01", bad))
                    .as("scope '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scope must be 0x followed by 16 hex digits");
        }
    }

    @Test
    @DisplayName("SCOPE_PATTERN is the published shape: 0x or 0X, then exactly 16 hex digits")
    void scopePattern_isThePublishedShape() {
        assertThat(PairingWindowRequest.SCOPE_PATTERN).isEqualTo("^0[xX][0-9a-fA-F]{16}$");
    }
}
