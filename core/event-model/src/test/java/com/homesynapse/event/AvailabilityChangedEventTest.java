/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AvailabilityChangedEvent} — emitted when device availability status changes.
 *
 * <p>Schema version 2 (J1 / LINK-READ-2, 2026-10-03): the two statuses plus five nullable
 * additions — {@code reason}, {@code lastSeenAt} and the link reading triple
 * {@code lqi}/{@code rssiDbm}/{@code linkAt}. A version-1 row decodes to this record with the
 * five additions {@code null}; the triple is all-or-none.
 */
@DisplayName("AvailabilityChangedEvent")
class AvailabilityChangedEventTest {

    private static final Instant LAST_SEEN = Instant.parse("2026-10-03T12:00:00Z");
    private static final Instant LINK_AT = Instant.parse("2026-10-03T11:59:30Z");

    /** The version-2 shape with every addition set. */
    private static AvailabilityChangedEvent full() {
        return new AvailabilityChangedEvent("online", "offline", "ping_timeout",
                LAST_SEEN, 200, -45, LINK_AT);
    }

    /** The version-1 shape: two statuses, the five additions {@code null}. */
    private static AvailabilityChangedEvent v1(String previous, String next) {
        return new AvailabilityChangedEvent(previous, next, null, null, null, null, null);
    }

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 7 fields accessible after construction")
        void allFieldsAccessible() {
            var event = full();

            assertThat(event.previousStatus()).isEqualTo("online");
            assertThat(event.newStatus()).isEqualTo("offline");
            assertThat(event.reason()).isEqualTo("ping_timeout");
            assertThat(event.lastSeenAt()).isEqualTo(LAST_SEEN);
            assertThat(event.lqi()).isEqualTo(200);
            assertThat(event.rssiDbm()).isEqualTo(-45);
            assertThat(event.linkAt()).isEqualTo(LINK_AT);
        }

        @Test
        @DisplayName("J1 T5: the version-1 shape — nulls on the five additions are VALUES, "
                + "never an error")
        void versionOneShapeAcceptsNullsOnTheFiveAdditions() {
            var event = v1("online", "offline");

            assertThat(event.previousStatus()).isEqualTo("online");
            assertThat(event.newStatus()).isEqualTo("offline");
            assertThat(event.reason()).isNull();
            assertThat(event.lastSeenAt()).isNull();
            assertThat(event.lqi()).isNull();
            assertThat(event.rssiDbm()).isNull();
            assertThat(event.linkAt()).isNull();
        }

        @Test
        @DisplayName("reason and lastSeenAt may be set without a link reading")
        void reasonAndLastSeenWithoutAReading() {
            var event = new AvailabilityChangedEvent("online", "offline", "silence_timeout",
                    LAST_SEEN, null, null, null);

            assertThat(event.reason()).isEqualTo("silence_timeout");
            assertThat(event.lastSeenAt()).isEqualTo(LAST_SEEN);
            assertThat(event.lqi()).isNull();
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = v1("online", "offline");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 7 components (schema version 2)")
        void exactlySevenFields() {
            assertThat(AvailabilityChangedEvent.class.getRecordComponents()).hasSize(7);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null previousStatus throws NullPointerException")
        void nullPreviousStatus() {
            assertThatNullPointerException().isThrownBy(() -> v1(null, "offline"))
                    .withMessageContaining("previousStatus");
        }

        @Test
        @DisplayName("null newStatus throws NullPointerException")
        void nullNewStatus() {
            assertThatNullPointerException().isThrownBy(() -> v1("online", null))
                    .withMessageContaining("newStatus");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank previousStatus throws IllegalArgumentException")
        void blankPreviousStatus() {
            assertThatThrownBy(() -> v1("  ", "offline"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("blank newStatus throws IllegalArgumentException")
        void blankNewStatus() {
            assertThatThrownBy(() -> v1("online", "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("J1 T5: the link triple is all-or-none — a partial triple throws "
                + "IllegalArgumentException")
        void partialLinkTripleIsRejected() {
            assertThatThrownBy(() -> new AvailabilityChangedEvent("online", "offline",
                    "frame_received", LAST_SEEN, 200, null, null))
                    .as("lqi alone")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("all null or all set");
            assertThatThrownBy(() -> new AvailabilityChangedEvent("online", "offline",
                    "frame_received", LAST_SEEN, 200, -45, null))
                    .as("lqi and rssiDbm without linkAt")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("all null or all set");
            assertThatThrownBy(() -> new AvailabilityChangedEvent("online", "offline",
                    "frame_received", LAST_SEEN, null, null, LINK_AT))
                    .as("linkAt alone")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("all null or all set");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical AvailabilityChangedEvents are equal")
    void identicalEqual() {
        var a = full();
        var b = full();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("AvailabilityChangedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = v1("online", "offline");
        var b = v1("offline", "online");
        assertThat(a).isNotEqualTo(b);
        assertThat(full()).isNotEqualTo(v1("online", "offline"));
    }
}
