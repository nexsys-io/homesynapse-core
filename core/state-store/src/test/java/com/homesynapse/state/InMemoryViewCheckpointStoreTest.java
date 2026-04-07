/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.state.test.InMemoryViewCheckpointStore;
import com.homesynapse.state.test.ViewCheckpointStoreContractTest;
import com.homesynapse.test.TestClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wires {@link InMemoryViewCheckpointStore} into the
 * {@link ViewCheckpointStoreContractTest} abstract contract test suite.
 *
 * <p>This concrete test class provides factory methods that connect the in-memory
 * implementation to the 10-method contract test. Additionally, it includes a
 * supplementary test that verifies clock advancement behavior — this test requires
 * {@link TestClock#advance(Duration)} which the abstract contract test cannot invoke
 * because it only has access to {@link Clock}.</p>
 *
 * @see ViewCheckpointStoreContractTest
 * @see InMemoryViewCheckpointStore
 */
class InMemoryViewCheckpointStoreTest extends ViewCheckpointStoreContractTest {

    private final TestClock testClock = TestClock.at(Instant.parse("2026-04-07T12:00:00Z"));
    private InMemoryViewCheckpointStore store;

    /** Creates a new test instance. */
    InMemoryViewCheckpointStoreTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    protected ViewCheckpointStore store() {
        return store;
    }

    @Override
    protected Clock clock() {
        return testClock;
    }

    @Override
    protected void resetStore() {
        store = new InMemoryViewCheckpointStore(testClock);
    }

    // ──────────────────────────────────────────────────────────────────
    // Supplementary test: clock advancement
    // ──────────────────────────────────────────────────────────────────

    /**
     * Verifies that advancing the clock between writes produces different
     * {@code writtenAt} timestamps. This test requires {@link TestClock} and
     * cannot live in the abstract contract test.
     */
    @Test
    @DisplayName("advancing clock changes writtenAt on subsequent checkpoint")
    void writeCheckpoint_afterClockAdvance_updatesWrittenAt() {
        Instant firstInstant = testClock.peek();
        store().writeCheckpoint("entity_state", 10, new byte[]{1});

        Optional<CheckpointRecord> firstResult = store().readLatestCheckpoint("entity_state");
        assertThat(firstResult).isPresent();
        assertThat(firstResult.get().writtenAt()).isEqualTo(firstInstant);

        // Advance the clock by 1 hour
        testClock.advance(Duration.ofHours(1));
        Instant secondInstant = testClock.peek();
        store().writeCheckpoint("entity_state", 20, new byte[]{2});

        Optional<CheckpointRecord> secondResult = store().readLatestCheckpoint("entity_state");
        assertThat(secondResult).isPresent();
        assertThat(secondResult.get().writtenAt()).isEqualTo(secondInstant);
        assertThat(secondResult.get().writtenAt()).isNotEqualTo(firstInstant);
    }
}
