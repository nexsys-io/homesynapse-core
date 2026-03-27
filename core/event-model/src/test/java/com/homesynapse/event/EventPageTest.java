/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventPage} — pagination container for EventStore query results.
 */
@DisplayName("EventPage")
class EventPageTest {

    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("empty events list accepted")
        void emptyEventsAccepted() {
            var page = new EventPage(List.of(), 0L, false);

            assertThat(page.events()).isEmpty();
            assertThat(page.nextPosition()).isZero();
            assertThat(page.hasMore()).isFalse();
        }

        @Test
        @DisplayName("all fields accessible")
        void allFieldsAccessible() {
            var page = new EventPage(List.of(), 42L, true);

            assertThat(page.events()).isEmpty();
            assertThat(page.nextPosition()).isEqualTo(42L);
            assertThat(page.hasMore()).isTrue();
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Test
    @DisplayName("null events throws NullPointerException")
    void nullEvents() {
        assertThatNullPointerException().isThrownBy(() ->
                new EventPage(null, 0L, false))
                .withMessageContaining("events");
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Test
    @DisplayName("negative nextPosition throws IllegalArgumentException")
    void negativeNextPosition() {
        assertThatThrownBy(() -> new EventPage(List.of(), -1L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextPosition");
    }

    @Test
    @DisplayName("nextPosition 0 accepted")
    void nextPositionZeroAccepted() {
        var page = new EventPage(List.of(), 0L, false);
        assertThat(page.nextPosition()).isZero();
    }

    // ── Defensive copy ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Defensive copy")
    class DefensiveCopyTests {

        @Test
        @DisplayName("events list is defensively copied — external mutation has no effect")
        void eventsDefensivelyCopied() {
            var mutable = new ArrayList<EventEnvelope>();
            var page = new EventPage(mutable, 0L, false);

            // Mutate the original list after construction
            mutable.add(createMinimalEnvelope());

            assertThat(page.events()).isEmpty();
        }

        @Test
        @DisplayName("events list is immutable — modification throws UnsupportedOperationException")
        void eventsImmutable() {
            var page = new EventPage(List.of(), 0L, false);

            assertThatThrownBy(() -> page.events().add(createMinimalEnvelope()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ── Pagination semantics ─────────────────────────────────────────────

    @Nested
    @DisplayName("Pagination semantics")
    class PaginationTests {

        @Test
        @DisplayName("hasMore true indicates more pages available")
        void hasMoreTrue() {
            var page = new EventPage(List.of(), 100L, true);
            assertThat(page.hasMore()).isTrue();
        }

        @Test
        @DisplayName("hasMore false indicates last page")
        void hasMoreFalse() {
            var page = new EventPage(List.of(), 100L, false);
            assertThat(page.hasMore()).isFalse();
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────

    private static EventEnvelope createMinimalEnvelope() {
        return new EventEnvelope(
                EventId.of(ULID_A), "test.event", 1, Instant.now(), null,
                SubjectRef.entity(EntityId.of(ULID_A)), 1L, 1L,
                EventPriority.NORMAL, EventOrigin.SYSTEM,
                List.of(EventCategory.SYSTEM),
                CausalContext.root(ULID_A), null,
                new DegradedEvent("test.event", 1, "{}", "test"));
    }
}
