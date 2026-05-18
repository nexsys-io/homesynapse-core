/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.platform.identity.EntityId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent in-memory implementation of {@link StateStore} for tests and
 * the M3.5a vertical slice.
 *
 * <p>Backed by a {@link ConcurrentHashMap} keyed by {@link EntityId}. Reads
 * are lock-free and safe for any thread (including virtual threads).
 * Writes are atomic per-key. The replacement {@code SqliteStateStore}
 * landing in M3.5b implements the same contract.</p>
 *
 * <p>Lives in the {@code testFixtures} source set's primary package
 * ({@code com.homesynapse.state}) rather than the {@code .test} sub-package:
 * the {@code .test} convention is reserved for abstract contract test bases.</p>
 */
public final class InMemoryStateStore implements StateStore {

    private final ConcurrentHashMap<EntityId, EntityState> backing = new ConcurrentHashMap<>();

    /**
     * Default constructor — required by {@code -Xlint:all -Werror}.
     */
    public InMemoryStateStore() {
        // No initialization needed.
    }

    @Override
    public Optional<EntityState> get(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        return Optional.ofNullable(backing.get(entityId));
    }

    @Override
    public void put(EntityId entityId, EntityState state) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        backing.put(entityId, state);
    }

    @Override
    public Map<EntityId, EntityState> getAll() {
        // Snapshot copy to preserve "unmodifiable view" semantics.
        return Collections.unmodifiableMap(new LinkedHashMap<>(backing));
    }

    @Override
    public void clear() {
        backing.clear();
    }
}
