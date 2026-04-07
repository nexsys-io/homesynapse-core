/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state.test;

import com.homesynapse.state.CheckpointRecord;
import com.homesynapse.state.ViewCheckpointStore;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, Clock-injected, in-memory implementation of {@link ViewCheckpointStore}
 * for use in test infrastructure.
 *
 * <p>This is NOT a stub. It is a fully contract-complete implementation that satisfies
 * the same 10-method {@link ViewCheckpointStoreContractTest} that the future
 * {@code SqliteViewCheckpointStore} will also satisfy. Behavioral equivalence between
 * this in-memory implementation and the SQLite implementation is what validates the
 * interface design.</p>
 *
 * <p><strong>Thread safety:</strong> All mutable state is stored in a
 * {@link ConcurrentHashMap}, which provides thread-safe reads and writes without
 * explicit locking. This is sufficient because all operations are single-step
 * atomic operations on the map.</p>
 *
 * <p><strong>Defensive copy:</strong> The {@code byte[]} data passed to
 * {@link #writeCheckpoint(String, long, byte[])} is cloned on write. The data
 * returned from {@link #readLatestCheckpoint(String)} is cloned on read. This
 * prevents aliasing bugs where a caller mutates the array and corrupts stored
 * checkpoint data.</p>
 *
 * <p><strong>Reset contract:</strong> {@link #reset()} clears all stored checkpoints.
 * It is intended to be called between tests for isolation.</p>
 *
 * @see ViewCheckpointStore
 * @see ViewCheckpointStoreContractTest
 */
public class InMemoryViewCheckpointStore implements ViewCheckpointStore {

    private final Clock clock;
    private final ConcurrentHashMap<String, CheckpointRecord> checkpoints = new ConcurrentHashMap<>();

    /**
     * Creates a new in-memory view checkpoint store with the given clock for
     * {@code writtenAt} timestamp assignment.
     *
     * <p>Clock injection is mandatory. The clock is used to set
     * {@link CheckpointRecord#writtenAt()} on each written checkpoint. For
     * deterministic tests, use a controllable test clock.</p>
     *
     * @param clock the clock to use for timestamp assignment; never {@code null}
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public InMemoryViewCheckpointStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void writeCheckpoint(String viewName, long position, byte[] data) {
        Objects.requireNonNull(viewName, "viewName must not be null");
        Objects.requireNonNull(data, "data must not be null");

        // Defensive copy — prevent aliasing with caller's array
        byte[] copy = data.clone();
        CheckpointRecord record = new CheckpointRecord(
                viewName, position, copy, clock.instant(), 1);
        checkpoints.put(viewName, record);
    }

    @Override
    public Optional<CheckpointRecord> readLatestCheckpoint(String viewName) {
        Objects.requireNonNull(viewName, "viewName must not be null");

        CheckpointRecord record = checkpoints.get(viewName);
        if (record == null) {
            return Optional.empty();
        }

        // Defensive copy — prevent aliasing with stored array
        CheckpointRecord defensiveCopy = new CheckpointRecord(
                record.viewName(),
                record.position(),
                record.data().clone(),
                record.writtenAt(),
                record.projectionVersion());
        return Optional.of(defensiveCopy);
    }

    /**
     * Clears all stored checkpoints, resetting to an empty state.
     *
     * <p>Intended to be called between tests for isolation via the contract
     * test's {@code resetStore()} method.</p>
     */
    public void reset() {
        checkpoints.clear();
    }
}
