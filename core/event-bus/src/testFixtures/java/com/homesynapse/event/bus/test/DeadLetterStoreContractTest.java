/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus.test;

import com.homesynapse.event.bus.DeadLetter;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Abstract contract test for any persistent dead-letter store
 * implementation (AMD-36).
 *
 * <p>Defines the behavioral contract that ALL DLQ store implementations must
 * satisfy. The persistence module's {@code SqliteDeadLetterStoreContractTest}
 * subclasses this and provides SQLite wiring; any future implementation
 * (in-memory test fixture, alternative backend) follows the same pattern.</p>
 *
 * <p>The contract validated here covers:</p>
 * <ul>
 *   <li>First-park INSERT semantics: a new row is created with the supplied
 *       fields.</li>
 *   <li>Idempotent upsert on
 *       {@code UNIQUE(subscriberId, eventPosition)} — repeated parks for the
 *       same event update {@code attemptCount}, {@code lastAttemptAt}, and
 *       cause/diagnostic columns rather than inserting duplicates.</li>
 *   <li>Per-subscriber isolation: {@code findBySubscriber} only returns rows
 *       for the requested subscriber.</li>
 *   <li>Position lookup: {@code findByPosition} returns the matching row or
 *       {@code Optional.empty()}.</li>
 *   <li>Counts: {@code countBySubscriber} matches the number of parked
 *       events for that subscriber.</li>
 *   <li>Nullable {@code diagnostics}: a parked entry with {@code null}
 *       diagnostics survives the round trip with {@code null} preserved.</li>
 *   <li>Validation: parking a {@code null} {@link DeadLetter} is rejected
 *       with {@link NullPointerException}.</li>
 * </ul>
 *
 * <p>Subclasses must implement {@link #park(DeadLetter)},
 * {@link #findBySubscriber(String)},
 * {@link #findByPosition(String, long)},
 * {@link #countBySubscriber(String)}, {@link #parkNullRejected()}, and
 * {@link #resetStore()}. The {@link #parkNullRejected()} delegate exists so
 * tests can assert {@link NullPointerException} without having to type-cast a
 * {@code null} literal.</p>
 *
 * @see DeadLetter
 */
@DisplayName("DeadLetterStore Contract")
public abstract class DeadLetterStoreContractTest {

    /** Common test instants — derived from a fixed reference for determinism. */
    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    protected static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
    protected static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");

    /** Two arbitrary event IDs used across the suite. */
    protected static final Ulid EVENT_ID_A = new Ulid(0x01__00_00_00_00_00_00_00L, 0x00_00_00_00_00_00_00_01L);
    protected static final Ulid EVENT_ID_B = new Ulid(0x02__00_00_00_00_00_00_00L, 0x00_00_00_00_00_00_00_02L);

    /** Subclass constructor. */
    protected DeadLetterStoreContractTest() {
        // Abstract — subclasses provide wiring.
    }

    // ──────────────────────────────────────────────────────────────────
    // Subclass wiring
    // ──────────────────────────────────────────────────────────────────

    /** Parks the given dead-letter via the store under test. */
    protected abstract void park(DeadLetter deadLetter);

    /** Looks up all parked entries for {@code subscriberId}. */
    protected abstract List<DeadLetter> findBySubscriber(String subscriberId);

    /** Looks up the entry for {@code (subscriberId, eventPosition)}. */
    protected abstract Optional<DeadLetter> findByPosition(
            String subscriberId, long eventPosition);

    /** Returns the count of parked entries for {@code subscriberId}. */
    protected abstract int countBySubscriber(String subscriberId);

    /**
     * Calls the store's {@code park} method with {@code null}. The subclass
     * implements this as a no-arg helper to keep the assertion site free of
     * casting and to avoid coupling the abstract suite to a specific
     * implementation entry point name.
     */
    protected abstract void parkNullRejected();

    /** Resets the store to an empty state — called from {@code @BeforeEach}. */
    protected abstract void resetStore();

    @BeforeEach
    void setUp() {
        resetStore();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 1: First-park INSERT
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("park inserts a new entry with the supplied fields")
    void parkNewDeadLetter_insertsRow() {
        DeadLetter dl = sampleEntry("state-projection", "sub:dev:1", 100L,
                EVENT_ID_A, "java.lang.IllegalStateException", "boom", 1, T0, T0, "trace");

        park(dl);

        Optional<DeadLetter> retrieved = findByPosition("state-projection", 100L);
        assertThat(retrieved).isPresent();
        DeadLetter row = retrieved.get();
        assertThat(row.subscriberId()).isEqualTo("state-projection");
        assertThat(row.sequenceKey()).isEqualTo("sub:dev:1");
        assertThat(row.eventPosition()).isEqualTo(100L);
        assertThat(row.eventId()).isEqualTo(EVENT_ID_A);
        assertThat(row.causeClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(row.causeMessage()).isEqualTo("boom");
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(row.firstSeenAt()).isEqualTo(T0);
        assertThat(row.lastAttemptAt()).isEqualTo(T0);
        assertThat(row.diagnostics()).isEqualTo("trace");
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 2: Idempotent upsert
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("re-parking the same (subscriberId, eventPosition) upserts")
    void parkExisting_upserts() {
        park(sampleEntry("state-projection", "sub:dev:1", 100L, EVENT_ID_A,
                "OldCause", "old", 1, T0, T0, "first"));
        park(sampleEntry("state-projection", "sub:dev:1", 100L, EVENT_ID_A,
                "NewCause", "new", 4, T0, T2, "fourth"));

        // Still exactly one row for this subscriber.
        assertThat(countBySubscriber("state-projection")).isEqualTo(1);

        // The row reflects the most recent values for cause/attempt/last-seen.
        DeadLetter row = findByPosition("state-projection", 100L).orElseThrow();
        assertThat(row.attemptCount()).isEqualTo(4);
        assertThat(row.causeClass()).isEqualTo("NewCause");
        assertThat(row.causeMessage()).isEqualTo("new");
        assertThat(row.diagnostics()).isEqualTo("fourth");
        assertThat(row.lastAttemptAt()).isEqualTo(T2);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 3: Per-subscriber isolation
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findBySubscriber returns only that subscriber's parked events")
    void findBySubscriber_returnsAllForSubscriber() {
        park(sampleEntry("state-projection", "sub:dev:1", 100L, EVENT_ID_A,
                "Cause", "m1", 1, T0, T0, null));
        park(sampleEntry("state-projection", "sub:dev:2", 101L, EVENT_ID_B,
                "Cause", "m2", 1, T1, T1, null));
        park(sampleEntry("other-subscriber", "sub:dev:3", 200L, EVENT_ID_A,
                "Cause", "m3", 1, T0, T0, null));

        List<DeadLetter> stateRows = findBySubscriber("state-projection");

        assertThat(stateRows).hasSize(2);
        assertThat(stateRows)
                .extracting(DeadLetter::eventPosition)
                .containsExactlyInAnyOrder(100L, 101L);
        assertThat(stateRows)
                .allMatch(r -> r.subscriberId().equals("state-projection"));
    }

    @Test
    @DisplayName("findBySubscriber returns empty list for unknown subscriber")
    void findBySubscriber_unknown_returnsEmpty() {
        assertThat(findBySubscriber("does-not-exist")).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 4: Position lookup
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("findByPosition returns the matching entry")
    void findByPosition_matching_returnsEntry() {
        park(sampleEntry("state-projection", "sub:dev:1", 42L, EVENT_ID_A,
                "Cause", "msg", 1, T0, T0, null));

        Optional<DeadLetter> result = findByPosition("state-projection", 42L);

        assertThat(result).isPresent();
        assertThat(result.get().eventPosition()).isEqualTo(42L);
    }

    @Test
    @DisplayName("findByPosition returns empty for missing entry")
    void findByPosition_missing_returnsEmpty() {
        // Park an unrelated entry so the table is non-empty.
        park(sampleEntry("state-projection", "sub:dev:1", 100L, EVENT_ID_A,
                "Cause", "msg", 1, T0, T0, null));

        assertThat(findByPosition("state-projection", 999L)).isEmpty();
        assertThat(findByPosition("unknown-sub", 100L)).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 5: Counts
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("countBySubscriber returns the parked-entry count")
    void countBySubscriber_returnsCount() {
        park(sampleEntry("state-projection", "sub:dev:1", 100L, EVENT_ID_A,
                "Cause", "m", 1, T0, T0, null));
        park(sampleEntry("state-projection", "sub:dev:2", 101L, EVENT_ID_B,
                "Cause", "m", 1, T0, T0, null));
        park(sampleEntry("state-projection", "sub:dev:3", 102L, EVENT_ID_A,
                "Cause", "m", 1, T0, T0, null));

        assertThat(countBySubscriber("state-projection")).isEqualTo(3);
    }

    @Test
    @DisplayName("countBySubscriber returns 0 for unknown subscriber")
    void countBySubscriber_unknown_returnsZero() {
        assertThat(countBySubscriber("never-seen")).isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 6: Nullable diagnostics
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null diagnostics survives the round trip as null")
    void nullDiagnostics_roundTripsAsNull() {
        park(sampleEntry("state-projection", "sub:dev:1", 100L, EVENT_ID_A,
                "Cause", "m", 1, T0, T0, null));

        DeadLetter row = findByPosition("state-projection", 100L).orElseThrow();
        assertThat(row.diagnostics()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 7: Validation
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("park rejects null DeadLetter with NullPointerException")
    void park_nullDeadLetter_throwsNPE() {
        assertThatThrownBy(this::parkNullRejected)
                .isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Builds a {@link DeadLetter} with the supplied fields and the unassigned
     * {@code dlqId} sentinel. The store assigns the real {@code dlqId} on
     * persistence.
     */
    protected static DeadLetter sampleEntry(
            String subscriberId,
            String sequenceKey,
            long eventPosition,
            Ulid eventId,
            String causeClass,
            String causeMessage,
            int attemptCount,
            Instant firstSeenAt,
            Instant lastAttemptAt,
            String diagnostics) {
        return new DeadLetter(
                DeadLetter.UNASSIGNED_DLQ_ID,
                subscriberId,
                sequenceKey,
                eventPosition,
                eventId,
                causeClass,
                causeMessage,
                attemptCount,
                firstSeenAt,
                lastAttemptAt,
                diagnostics);
    }
}
