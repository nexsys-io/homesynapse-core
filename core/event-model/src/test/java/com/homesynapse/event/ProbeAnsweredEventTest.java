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
 * AVAIL-API-1 E-1 — {@link ProbeAnsweredEvent} (AMD-103 R-B): the sampled record of an
 * answered liveness probe. The compact constructor's invariants: {@code at} and
 * {@code windowStart} non-null with {@code windowStart <= at}; the counts non-negative and
 * {@code answeredSinceLastRecord >= 1} (the recorded answer counts in it); the link triple
 * all null or all set (the form {@code AvailabilityChangedEvent} v2 gave the reading).
 */
@DisplayName("ProbeAnsweredEvent (AMD-103 R-B)")
class ProbeAnsweredEventTest {

    private static final Instant AT = Instant.parse("2026-10-11T12:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-10-11T11:00:00Z");
    private static final Instant LINK_AT = Instant.parse("2026-10-11T11:59:30Z");

    /** The record with a reading: the hourly shape. */
    private static ProbeAnsweredEvent full() {
        return new ProbeAnsweredEvent(AT, WINDOW_START, 55, 1, 200, -45, LINK_AT);
    }

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ProbeAnsweredEventTest() {
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 7 fields accessible after construction")
        void allFieldsAccessible() {
            ProbeAnsweredEvent event = full();

            assertThat(event.at()).isEqualTo(AT);
            assertThat(event.windowStart()).isEqualTo(WINDOW_START);
            assertThat(event.answeredSinceLastRecord()).isEqualTo(55);
            assertThat(event.missedSinceLastRecord()).isEqualTo(1);
            assertThat(event.lqi()).isEqualTo(200);
            assertThat(event.rssiDbm()).isEqualTo(-45);
            assertThat(event.linkAt()).isEqualTo(LINK_AT);
        }

        @Test
        @DisplayName("a record without a reading (the probe reply carries none) has the triple null")
        void noReadingIsAValue() {
            ProbeAnsweredEvent event = new ProbeAnsweredEvent(AT, WINDOW_START, 1, 0,
                    null, null, null);

            assertThat(event.lqi()).isNull();
            assertThat(event.rssiDbm()).isNull();
            assertThat(event.linkAt()).isNull();
        }

        @Test
        @DisplayName("the opening record: windowStart equal to at is allowed (a zero-length window)")
        void windowStartEqualToAtIsAllowed() {
            ProbeAnsweredEvent event = new ProbeAnsweredEvent(AT, AT, 1, 0, null, null, null);

            assertThat(event.windowStart()).isEqualTo(event.at());
        }

        @Test
        @DisplayName("carries @EventType(EventTypes.PROBE_ANSWERED)")
        void carriesTheAnnotation() {
            EventType annotation = ProbeAnsweredEvent.class.getAnnotation(EventType.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo(EventTypes.PROBE_ANSWERED)
                    .isEqualTo("probe_answered");
        }
    }

    @Nested
    @DisplayName("Invariants")
    class InvariantTests {

        @Test
        @DisplayName("at is required")
        void atRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProbeAnsweredEvent(null, WINDOW_START, 1, 0,
                            null, null, null))
                    .withMessageContaining("at");
        }

        @Test
        @DisplayName("windowStart is required")
        void windowStartRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProbeAnsweredEvent(AT, null, 1, 0, null, null, null))
                    .withMessageContaining("windowStart");
        }

        @Test
        @DisplayName("windowStart after at is rejected")
        void windowStartAfterAtRejected() {
            assertThatThrownBy(() -> new ProbeAnsweredEvent(AT, AT.plusSeconds(1), 1, 0,
                    null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("windowStart");
        }

        @Test
        @DisplayName("answeredSinceLastRecord below 1 is rejected — the recorded answer counts")
        void answeredBelowOneRejected() {
            assertThatThrownBy(() -> new ProbeAnsweredEvent(AT, WINDOW_START, 0, 0,
                    null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("answeredSinceLastRecord");
        }

        @Test
        @DisplayName("a negative missedSinceLastRecord is rejected")
        void negativeMissedRejected() {
            assertThatThrownBy(() -> new ProbeAnsweredEvent(AT, WINDOW_START, 1, -1,
                    null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missedSinceLastRecord");
        }

        @Test
        @DisplayName("a partial link triple is rejected (all null or all set)")
        void partialTripleRejected() {
            assertThatThrownBy(() -> new ProbeAnsweredEvent(AT, WINDOW_START, 1, 0,
                    200, null, LINK_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("all null or all set");
            assertThatThrownBy(() -> new ProbeAnsweredEvent(AT, WINDOW_START, 1, 0,
                    null, null, LINK_AT))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ProbeAnsweredEvent(AT, WINDOW_START, 1, 0,
                    200, -45, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Equality")
    class EqualityTests {

        @Test
        @DisplayName("records with the same components are equal")
        void equalComponentsAreEqual() {
            assertThat(full()).isEqualTo(full()).hasSameHashCodeAs(full());
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            assertThat(full()).isInstanceOf(DomainEvent.class);
        }
    }
}
