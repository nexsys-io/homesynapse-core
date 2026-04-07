/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus.test;

import com.homesynapse.event.bus.CheckpointStore;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, in-memory implementation of {@link CheckpointStore} for use in
 * test infrastructure.
 *
 * <p>This is NOT a stub. It is a fully contract-complete implementation that
 * satisfies the same 9-method {@link CheckpointStoreContractTest} that the
 * future {@code SqliteCheckpointStore} will also satisfy. Behavioral equivalence
 * between this in-memory implementation and the SQLite implementation is what
 * validates the interface design.</p>
 *
 * <p><strong>Thread safety:</strong> All mutable state is stored in a
 * {@link ConcurrentHashMap}, which provides thread-safe reads and writes without
 * explicit locking. This is sufficient because all operations are single-step
 * atomic operations on the map — no multi-step invariants need to be maintained
 * across operations.</p>
 *
 * <p><strong>Reset contract:</strong> {@link #reset()} clears all stored
 * checkpoints. It is intended to be called between tests for isolation.</p>
 *
 * @see CheckpointStore
 * @see CheckpointStoreContractTest
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentHashMap<String, Long> checkpoints = new ConcurrentHashMap<>();

    /** Creates a new empty in-memory checkpoint store. */
    public InMemoryCheckpointStore() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @Override
    public long readCheckpoint(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        return checkpoints.getOrDefault(subscriberId, 0L);
    }

    @Override
    public void writeCheckpoint(String subscriberId, long globalPosition) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        if (globalPosition < 0) {
            throw new IllegalArgumentException(
                    "globalPosition must be >= 0, got " + globalPosition);
        }
        checkpoints.put(subscriberId, globalPosition);
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
