/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FloorId} — the typed ULID wrapper for floors (AMD-44 §2.1).
 *
 * <p>{@code FloorId} delegates entirely to {@link Ulid}; these tests verify the
 * delegation is wired correctly (of/parse/compareTo/toString/null-guard). The
 * Crockford Base32 encoding itself is {@code Ulid}'s responsibility, verified in
 * {@link UlidTest}.</p>
 */
@DisplayName("FloorId")
class FloorIdTest {

    /** A known ULID for deterministic testing. */
    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);

    /** A second ULID that sorts after {@link #ULID_A} (higher msb). */
    private static final Ulid ULID_B = new Ulid(0x0191B3C4D5E6F709L, 0x0123456789ABCDEFL);

    @Nested
    @DisplayName("Construction via of(Ulid)")
    class OfTests {

        @Test
        @DisplayName("of(ulid) returns non-null instance")
        void of_validUlid_returnsNonNull() {
            assertThat(FloorId.of(ULID_A)).isNotNull();
        }

        @Test
        @DisplayName("of(ulid).value() returns the same Ulid")
        void of_validUlid_preservesValue() {
            assertThat(FloorId.of(ULID_A).value()).isEqualTo(ULID_A);
        }

        @Test
        @DisplayName("of(null) throws NullPointerException")
        void of_null_throwsNpe() {
            assertThatThrownBy(() -> FloorId.of(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("FloorId value must not be null");
        }
    }

    @Nested
    @DisplayName("Parsing via parse(String)")
    class ParseTests {

        @Test
        @DisplayName("parse(ulid.toString()) round-trips to of(ulid)")
        void parse_validString_roundTrips() {
            FloorId fromOf = FloorId.of(ULID_A);
            FloorId fromParse = FloorId.parse(ULID_A.toString());
            assertThat(fromParse).isEqualTo(fromOf);
        }

        @Test
        @DisplayName("parse(null) throws NullPointerException")
        void parse_null_throwsNpe() {
            assertThatThrownBy(() -> FloorId.parse(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("parse(\"\") throws IllegalArgumentException")
        void parse_empty_throwsIae() {
            assertThatThrownBy(() -> FloorId.parse(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("parse(\"invalid\") throws IllegalArgumentException")
        void parse_invalidString_throwsIae() {
            assertThatThrownBy(() -> FloorId.parse("invalid"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("compareTo delegation")
    class CompareToTests {

        @Test
        @DisplayName("lower ULID sorts before higher ULID")
        void compareTo_lowerUlid_sortsBeforeHigher() {
            assertThat(FloorId.of(ULID_A).compareTo(FloorId.of(ULID_B))).isNegative();
        }

        @Test
        @DisplayName("equal FloorIds compare to zero")
        void compareTo_equalIds_isZero() {
            assertThat(FloorId.of(ULID_A).compareTo(FloorId.of(ULID_A))).isZero();
        }
    }

    @Nested
    @DisplayName("equals / hashCode / toString")
    class ValueSemanticsTests {

        @Test
        @DisplayName("same Ulid produces equal FloorIds with equal hashCode")
        void equals_sameUlid_isEqual() {
            FloorId a = FloorId.of(ULID_A);
            FloorId b = FloorId.of(ULID_A);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different Ulids produce unequal FloorIds")
        void equals_differentUlid_notEqual() {
            assertThat(FloorId.of(ULID_A)).isNotEqualTo(FloorId.of(ULID_B));
        }

        @Test
        @DisplayName("toString() returns Crockford Base32 of the wrapped Ulid")
        void toString_returnsCrockfordOfWrappedUlid() {
            assertThat(FloorId.of(ULID_A).toString()).isEqualTo(ULID_A.toString());
        }
    }
}
