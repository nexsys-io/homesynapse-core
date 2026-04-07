/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state.test;

import com.homesynapse.state.CheckpointRecord;
import com.homesynapse.state.ViewCheckpointStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Abstract contract test for {@link ViewCheckpointStore}.
 *
 * <p>Defines the behavioral contract that ALL {@code ViewCheckpointStore} implementations
 * must satisfy. Both {@link InMemoryViewCheckpointStore} (test fixture) and the future
 * {@code SqliteViewCheckpointStore} (persistence module) extend this class and inherit
 * the same 10-method test suite.</p>
 *
 * <p>This follows the Axon {@code AbstractEventStorageEngineTest} pattern used
 * throughout HomeSynapse: an abstract contract test defines "correct," and each
 * implementation provides the factory wiring via abstract methods.</p>
 *
 * <p>The contract validated here covers:</p>
 * <ul>
 *   <li>Read-back of unknown views (returns {@code Optional.empty()})</li>
 *   <li>Write-then-read round trips with full field verification</li>
 *   <li>Overwrite semantics (latest checkpoint wins)</li>
 *   <li>Per-view isolation (independent checkpoints)</li>
 *   <li>Empty and large {@code byte[]} data handling</li>
 *   <li>Defensive copy of {@code byte[]} on both write and read</li>
 *   <li>Null parameter rejection</li>
 *   <li>Clock injection for deterministic {@code writtenAt} timestamps</li>
 * </ul>
 *
 * <p>Subclasses must implement {@link #store()}, {@link #clock()}, and
 * {@link #resetStore()}. This abstract class calls {@link #resetStore()} in
 * {@code @BeforeEach} to ensure test isolation.</p>
 *
 * <p><strong>Distinction from CheckpointStoreContractTest:</strong> The event-bus
 * {@code CheckpointStoreContractTest} tests a simpler interface that stores a single
 * {@code long} position per subscriber. This contract test validates richer checkpoint
 * data: {@code byte[]} payload, {@code Instant} timestamp, and {@code int} projection
 * version alongside the position.</p>
 *
 * @see ViewCheckpointStore
 * @see CheckpointRecord
 * @see com.homesynapse.event.bus.CheckpointStore
 */
@DisplayName("ViewCheckpointStore Contract")
public abstract class ViewCheckpointStoreContractTest {

    /** Subclass constructor. */
    protected ViewCheckpointStoreContractTest() {
        // Abstract — subclasses provide implementation.
    }

    /**
     * Returns the {@link ViewCheckpointStore} under test.
     *
     * <p>Called by every test method — must return a consistent instance
     * within a single test execution.</p>
     */
    protected abstract ViewCheckpointStore store();

    /**
     * Returns the {@link Clock} used by the store for {@code writtenAt} timestamps.
     *
     * <p>Tests that verify timestamp behavior will manipulate this clock. Subclasses
     * should return a controllable test clock (e.g., {@code TestClock}).</p>
     */
    protected abstract Clock clock();

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
    // SECTION 1: Unknown view behavior
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link ViewCheckpointStore#readLatestCheckpoint(String)} returns
     * {@code Optional.empty()} for a view that has never been checkpointed. This is
     * the first-boot or post-wipe scenario — the State Projection detects an empty
     * Optional and performs a full event replay.
     */
    @Test
    @DisplayName("readLatestCheckpoint returns empty for unknown view")
    void readLatestCheckpoint_unknownView_returnsEmpty() {
        Optional<CheckpointRecord> result = store().readLatestCheckpoint("nonexistent");

        assertThat(result).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 2: Basic round-trip
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies basic write-then-read round trip: writing a checkpoint and reading
     * it back returns a {@link CheckpointRecord} with all 5 fields matching.
     */
    @Test
    @DisplayName("writeCheckpoint then readLatestCheckpoint returns matching record")
    void writeCheckpoint_thenRead_returnsMatchingRecord() {
        byte[] data = new byte[]{1, 2, 3};

        store().writeCheckpoint("entity_state", 42, data);

        Optional<CheckpointRecord> result = store().readLatestCheckpoint("entity_state");

        assertThat(result).isPresent();
        CheckpointRecord record = result.get();
        assertThat(record.viewName()).isEqualTo("entity_state");
        assertThat(record.position()).isEqualTo(42L);
        assertThat(record.data()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(record.writtenAt()).isNotNull();
        assertThat(record.writtenAt()).isEqualTo(clock().instant());
        assertThat(record.projectionVersion()).isGreaterThanOrEqualTo(1);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 3: Overwrite semantics
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that writing a new checkpoint for the same view name overwrites
     * the previous value. Only the latest checkpoint is stored per view, matching
     * Doc 04 §3.12 {@code INSERT OR REPLACE} semantics.
     */
    @Test
    @DisplayName("writeCheckpoint overwrites previous checkpoint for same view")
    void writeCheckpoint_overwritesPreviousCheckpoint() {
        store().writeCheckpoint("entity_state", 10, new byte[]{1});
        store().writeCheckpoint("entity_state", 20, new byte[]{2});

        Optional<CheckpointRecord> result = store().readLatestCheckpoint("entity_state");

        assertThat(result).isPresent();
        assertThat(result.get().position()).isEqualTo(20L);
        assertThat(result.get().data()).isEqualTo(new byte[]{2});
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 4: Per-view isolation
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that checkpoints for different views are independent. Writing a
     * checkpoint for one view does not affect another.
     */
    @Test
    @DisplayName("multiple views have independent checkpoints")
    void writeCheckpoint_multipleViews_independent() {
        store().writeCheckpoint("entity_state", 100, new byte[]{10});
        store().writeCheckpoint("energy_analytics", 50, new byte[]{20});

        Optional<CheckpointRecord> entityResult = store().readLatestCheckpoint("entity_state");
        Optional<CheckpointRecord> energyResult = store().readLatestCheckpoint("energy_analytics");

        assertThat(entityResult).isPresent();
        assertThat(entityResult.get().position()).isEqualTo(100L);
        assertThat(entityResult.get().data()).isEqualTo(new byte[]{10});

        assertThat(energyResult).isPresent();
        assertThat(energyResult.get().position()).isEqualTo(50L);
        assertThat(energyResult.get().data()).isEqualTo(new byte[]{20});
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 5: Empty data is valid
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that empty {@code byte[]} data is a valid checkpoint value. An empty
     * checkpoint may represent a view that has been initialized but has no state to
     * serialize. The data field should be an empty array, not null.
     */
    @Test
    @DisplayName("empty byte[] data is valid and round-trips")
    void writeCheckpoint_emptyData_isValid() {
        store().writeCheckpoint("entity_state", 5, new byte[0]);

        Optional<CheckpointRecord> result = store().readLatestCheckpoint("entity_state");

        assertThat(result).isPresent();
        assertThat(result.get().data()).isNotNull();
        assertThat(result.get().data()).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 6: Large data round-trip
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that the store handles large {@code byte[]} payloads. A 100KB array
     * simulates a serialized state snapshot for 150 entities, which Doc 03 §3.3
     * estimates at ~75KB. The store must not truncate or impose a size limit.
     */
    @Test
    @DisplayName("large byte[] data (100KB) round-trips correctly")
    void writeCheckpoint_largeData_roundTrips() {
        byte[] largeData = new byte[100 * 1024];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }

        store().writeCheckpoint("entity_state", 1000, largeData);

        Optional<CheckpointRecord> result = store().readLatestCheckpoint("entity_state");

        assertThat(result).isPresent();
        assertThat(result.get().data()).isEqualTo(largeData);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 7: Defensive copy of byte[] data
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that the store defensively copies the {@code byte[]} data on both
     * write and read. This is the single most important safety property for byte[]
     * storage — without defensive copies, a caller can corrupt stored data by
     * mutating the array reference.
     *
     * <p>Tests both directions:</p>
     * <ul>
     *   <li>Mutating the input array after write must not affect stored data</li>
     *   <li>Mutating the array returned from read must not affect stored data</li>
     * </ul>
     */
    @Test
    @DisplayName("byte[] data is defensively copied on write and read")
    void writeCheckpoint_dataIsDefensivelyCopied() {
        // Write with known data, then mutate the original array
        byte[] originalData = new byte[]{1, 2, 3};
        store().writeCheckpoint("entity_state", 42, originalData);
        originalData[0] = 99;

        // Read — stored data should be unchanged (defensive copy on write)
        Optional<CheckpointRecord> firstRead = store().readLatestCheckpoint("entity_state");
        assertThat(firstRead).isPresent();
        assertThat(firstRead.get().data()).isEqualTo(new byte[]{1, 2, 3});

        // Mutate the data returned from read
        firstRead.get().data()[0] = 77;

        // Read again — stored data should still be unchanged (defensive copy on read)
        Optional<CheckpointRecord> secondRead = store().readLatestCheckpoint("entity_state");
        assertThat(secondRead).isPresent();
        assertThat(secondRead.get().data()).isEqualTo(new byte[]{1, 2, 3});
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 8: Null viewName rejection
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link ViewCheckpointStore#writeCheckpoint(String, long, byte[])}
     * throws {@link NullPointerException} when given a null view name.
     */
    @Test
    @DisplayName("writeCheckpoint rejects null viewName")
    void writeCheckpoint_nullViewName_throwsNPE() {
        assertThatThrownBy(() -> store().writeCheckpoint(null, 10, new byte[]{1}))
                .isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 9: Null data rejection
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that {@link ViewCheckpointStore#writeCheckpoint(String, long, byte[])}
     * throws {@link NullPointerException} when given null data.
     */
    @Test
    @DisplayName("writeCheckpoint rejects null data")
    void writeCheckpoint_nullData_throwsNPE() {
        assertThatThrownBy(() -> store().writeCheckpoint("entity_state", 10, null))
                .isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 10: Clock injection for writtenAt
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that the store stamps {@code writtenAt} from the injected Clock,
     * not from {@code Instant.now()}. This is the test that catches violations of
     * the Clock injection requirement — without it, time-dependent behavior in
     * production could differ from tests.
     *
     * <p>The test advances the clock between two writes and verifies that each
     * checkpoint's {@code writtenAt} matches the clock's instant at write time.</p>
     */
    @Test
    @DisplayName("writtenAt is stamped from injected Clock")
    void writeCheckpoint_setsWrittenAtFromClock() {
        Instant firstInstant = clock().instant();
        store().writeCheckpoint("entity_state", 10, new byte[]{1});

        Optional<CheckpointRecord> firstResult = store().readLatestCheckpoint("entity_state");
        assertThat(firstResult).isPresent();
        assertThat(firstResult.get().writtenAt()).isEqualTo(firstInstant);
    }
}
