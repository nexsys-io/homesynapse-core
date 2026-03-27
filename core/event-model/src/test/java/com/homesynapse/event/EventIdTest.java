/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventId} — typed ULID wrapper for event identifiers.
 *
 * <p>Follows the same pattern as TypedIdTest in platform-api, but standalone
 * since EventId is the only typed wrapper in event-model.</p>
 */
@DisplayName("EventId")
class EventIdTest {

    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);
    private static final Ulid ULID_B = new Ulid(0x0191B3C4D5E6F709L, 0x0123456789ABCDEFL);

    // ── of(Ulid) ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("of(Ulid)")
    class OfTests {

        @Test
        @DisplayName("of(ulid) returns non-null instance")
        void ofReturnsNonNull() {
            assertThat(EventId.of(ULID_A)).isNotNull();
        }

        @Test
        @DisplayName("of(ulid).value() returns the same Ulid")
        void ofPreservesValue() {
            assertThat(EventId.of(ULID_A).value()).isEqualTo(ULID_A);
        }

        @Test
        @DisplayName("of(null) throws NullPointerException")
        void ofNullThrows() {
            assertThatNullPointerException().isThrownBy(() -> EventId.of(null));
        }
    }

    // ── Constructor null rejection ───────────────────────────────────────

    @Test
    @DisplayName("new EventId(null) throws NullPointerException")
    void constructorNullThrows() {
        assertThatNullPointerException().isThrownBy(() -> new EventId(null))
                .withMessageContaining("EventId value");
    }

    // ── parse(String) ────────────────────────────────────────────────────

    @Nested
    @DisplayName("parse(String)")
    class ParseTests {

        @Test
        @DisplayName("parse(ulid.toString()) round-trip equals of(ulid)")
        void parseRoundTrip() {
            var fromOf = EventId.of(ULID_A);
            var fromParse = EventId.parse(ULID_A.toString());
            assertThat(fromParse).isEqualTo(fromOf);
        }

        @Test
        @DisplayName("parse(null) throws NullPointerException")
        void parseNullThrows() {
            assertThatNullPointerException().isThrownBy(() -> EventId.parse(null));
        }

        @Test
        @DisplayName("parse(\"\") throws IllegalArgumentException")
        void parseEmptyThrows() {
            assertThatThrownBy(() -> EventId.parse(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("parse(\"invalid\") throws IllegalArgumentException")
        void parseInvalidThrows() {
            assertThatThrownBy(() -> EventId.parse("invalid"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── compareTo ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("compareTo")
    class CompareToTests {

        @Test
        @DisplayName("lower ULID sorts before higher ULID")
        void ordering() {
            var low = EventId.of(ULID_A);
            var high = EventId.of(ULID_B);
            assertThat(low.compareTo(high)).isNegative();
            assertThat(high.compareTo(low)).isPositive();
        }

        @Test
        @DisplayName("compareTo == 0 iff equals is true")
        void consistentWithEquals() {
            var a = EventId.of(ULID_A);
            var b = EventId.of(ULID_A);
            assertThat(a.compareTo(b)).isZero();
            assertThat(a).isEqualTo(b);
        }
    }

    // ── equals / hashCode ────────────────────────────────────────────────

    @Nested
    @DisplayName("equals / hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("same Ulid produces equal EventIds")
        void sameUlidEquals() {
            assertThat(EventId.of(ULID_A)).isEqualTo(EventId.of(ULID_A));
        }

        @Test
        @DisplayName("different Ulids produce unequal EventIds")
        void differentUlidsNotEqual() {
            assertThat(EventId.of(ULID_A)).isNotEqualTo(EventId.of(ULID_B));
        }

        @Test
        @DisplayName("equal EventIds have same hashCode")
        void equalHashCode() {
            assertThat(EventId.of(ULID_A).hashCode())
                    .isEqualTo(EventId.of(ULID_A).hashCode());
        }
    }

    // ── toString ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("toString() returns Crockford Base32 of wrapped Ulid")
    void toStringDelegates() {
        assertThat(EventId.of(ULID_A).toString()).isEqualTo(ULID_A.toString());
    }
}
