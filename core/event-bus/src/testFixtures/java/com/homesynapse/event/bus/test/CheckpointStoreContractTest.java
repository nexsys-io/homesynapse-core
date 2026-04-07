/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus.test;

import com.homesynapse.event.bus.CheckpointStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Abstract contract test for {@link CheckpointStore}.
 *
 * <p>Defines the behavioral contract that ALL {@code CheckpointStore} implementations
 * must satisfy. Both {@code InMemoryCheckpointStore} (test fixture) and the future
 * {@code SqliteCheckpointStore} (persistence module) extend this class and inherit
 * the same 9-method test suite.</p>
 *
 * <p>This follows the Axon {@code AbstractEventStorageEngineTest} pattern used
 * throughout HomeSynapse: an abstract contract test defines "correct," and each
 * implementation provides the factory wiring via abstract methods.</p>
 *
 * <p>The contract validated here covers:</p>
 * <ul>
 *   <li>Read-back of unknown subscribers (returns 0)</li>
 *   <li>Write-then-read round trips</li>
 *   <li>Overwrite semantics (latest checkpoint wins)</li>
 *   <li>Per-subscriber isolation (independent positions)</li>
 *   <li>Position 0 as a valid checkpoint value</li>
 *   <li>Rejection of negative positions</li>
 *   <li>Null subscriber ID rejection on both methods</li>
 *   <li>Full {@code long} range support (Long.MAX_VALUE)</li>
 * </ul>
 *
 * <p>Subclasses must implement {@link #store()} and {@link #resetStore()}.
 * This abstract class calls {@link #resetStore()} in {@code @BeforeEach}
 * to ensure test isolation.</p>
 *
 * @see CheckpointStore
 */
@DisplayName("CheckpointStore Contract")
public abstract class CheckpointStoreContractTest {

    /** Subclass constructor. */
    protected CheckpointStoreContractTest() {
        // Abstract — subclasses provide implementation.
    }

    /**
     * Returns the {@link CheckpointStore} under test.
     *
     * <p>Called by every test method — must return a consistent instance
     * within a single test execution.</p>
     */
    protected abstract CheckpointStore store();

    /**
     * Resets the store to an empty state for test isolation.
     * Called in {@link #setUp()}.
     */
    protected abstract void resetStore();

    @BeforeEach
    void setUp() {
        resetStore();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 1: Unknown subscriber behavior
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link CheckpointStore#readCheckpoint(String)} returns 0
     * for a subscriber that has never checkpointed, meaning "start from the
     * beginning of the event log."
     */
    @Test
    @DisplayName("readCheckpoint returns 0 for unknown subscriber")
    void readCheckpoint_unknownSubscriber_returnsZero() {
        long position = store().readCheckpoint("never-seen-subscriber");

        assertThat(position).isEqualTo(0L);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 2: Basic round-trip
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies basic write-then-read round trip: writing a position and reading
     * it back returns the same value.
     */
    @Test
    @DisplayName("writeCheckpoint then readCheckpoint returns written position")
    void writeCheckpoint_thenRead_returnsWrittenPosition() {
        store().writeCheckpoint("state-projection", 42);

        long position = store().readCheckpoint("state-projection");

        assertThat(position).isEqualTo(42L);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 3: Overwrite semantics
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that writing a new checkpoint for the same subscriber overwrites
     * the previous value. Only the latest checkpoint is stored.
     */
    @Test
    @DisplayName("writeCheckpoint overwrites previous value")
    void writeCheckpoint_overwritesPreviousValue() {
        store().writeCheckpoint("state-projection", 10);
        store().writeCheckpoint("state-projection", 20);

        long position = store().readCheckpoint("state-projection");

        assertThat(position).isEqualTo(20L);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 4: Per-subscriber isolation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that checkpoints for different subscribers are independent.
     * Writing a checkpoint for one subscriber does not affect another.
     */
    @Test
    @DisplayName("multiple subscribers have independent checkpoints")
    void writeCheckpoint_multipleSubscribers_independent() {
        store().writeCheckpoint("state-projection", 100);
        store().writeCheckpoint("automation-engine", 50);

        assertThat(store().readCheckpoint("state-projection")).isEqualTo(100L);
        assertThat(store().readCheckpoint("automation-engine")).isEqualTo(50L);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 5: Position zero is valid
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that position 0 is a valid checkpoint value. A subscriber that
     * explicitly checkpoints at 0 means "I've registered but processed nothing yet."
     * This is distinct from an unknown subscriber which also returns 0 from
     * {@code readCheckpoint}, but the store should accept and persist the value.
     */
    @Test
    @DisplayName("position 0 is a valid checkpoint value")
    void writeCheckpoint_positionZero_isValid() {
        store().writeCheckpoint("state-projection", 0);

        long position = store().readCheckpoint("state-projection");

        assertThat(position).isEqualTo(0L);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 6: Negative position rejection
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link CheckpointStore#writeCheckpoint(String, long)} throws
     * {@link IllegalArgumentException} when given a negative global position.
     * Negative positions are invalid — {@code globalPosition} is a monotonically
     * increasing sequence starting at 1 (SQLite rowid).
     */
    @Test
    @DisplayName("writeCheckpoint rejects negative position")
    void writeCheckpoint_negativePosition_throwsIllegalArgument() {
        assertThatThrownBy(() -> store().writeCheckpoint("state-projection", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 7: Null subscriber ID — readCheckpoint
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link CheckpointStore#readCheckpoint(String)} throws
     * {@link NullPointerException} when given a null subscriber ID.
     */
    @Test
    @DisplayName("readCheckpoint rejects null subscriberId")
    void readCheckpoint_nullSubscriberId_throwsNPE() {
        assertThatThrownBy(() -> store().readCheckpoint(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 8: Null subscriber ID — writeCheckpoint
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link CheckpointStore#writeCheckpoint(String, long)} throws
     * {@link NullPointerException} when given a null subscriber ID.
     */
    @Test
    @DisplayName("writeCheckpoint rejects null subscriberId")
    void writeCheckpoint_nullSubscriberId_throwsNPE() {
        assertThatThrownBy(() -> store().writeCheckpoint(null, 10))
                .isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 9: Large position round-trip
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that the store handles the full {@code long} range. Writing
     * {@link Long#MAX_VALUE} and reading it back must return the same value.
     * The store must not truncate to {@code int} or lose precision.
     */
    @Test
    @DisplayName("Long.MAX_VALUE round-trips correctly")
    void writeCheckpoint_largePosition_roundTrips() {
        store().writeCheckpoint("state-projection", Long.MAX_VALUE);

        long position = store().readCheckpoint("state-projection");

        assertThat(position).isEqualTo(Long.MAX_VALUE);
    }
}
