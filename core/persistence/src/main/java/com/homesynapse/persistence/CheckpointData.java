/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.EntityState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Package-private wrapper for deserialized State Projection checkpoint data.
 *
 * <p>{@link CheckpointSerializer#deserialize(byte[])} returns one of these.
 * {@link SqliteStateStore} consumes it to repopulate its in-memory state map
 * on startup and to publish the real {@code projectionVersion} that the
 * {@link com.homesynapse.state.StateProjection} uses for reconciliation
 * checks (AMD-41 §3.2.4).</p>
 *
 * <p>The empty case (no prior checkpoint or zero-length payload) is signaled
 * by {@link #empty()}: an empty state map, {@code projectionVersion = 0},
 * and {@code null} reconciliation metadata. The projection treats
 * {@code projectionVersion = 0} as "version unknown — accept current
 * checkpoint position as-is" since no real projection registers version 0
 * (the minimum valid version is 1).</p>
 *
 * @param stateMap              materialized entity state at checkpoint time;
 *                              never {@code null} (may be empty)
 * @param projectionVersion     projection version embedded in the checkpoint
 *                              data; {@code 0} for the empty sentinel
 * @param reconciledAt          when reconciliation last ran;
 *                              {@code null} when reconciliation has not occurred
 * @param reconciledFromVersion prior projection version at reconciliation;
 *                              {@code null} when {@code reconciledAt == null}
 * @param reconciledToVersion   projection version after reconciliation;
 *                              {@code null} when {@code reconciledAt == null}
 */
record CheckpointData(
        Map<EntityId, EntityState> stateMap,
        int projectionVersion,
        Instant reconciledAt,
        Integer reconciledFromVersion,
        Integer reconciledToVersion
) {

    /**
     * Compact constructor copying {@code stateMap} so callers cannot mutate
     * the record's view after construction.
     */
    CheckpointData {
        Objects.requireNonNull(stateMap, "stateMap must not be null");
        stateMap = new LinkedHashMap<>(stateMap);
    }

    /**
     * Returns the empty / no-prior-checkpoint sentinel: empty state map,
     * {@code projectionVersion = 0}, null reconciliation metadata.
     *
     * @return a fresh empty checkpoint
     */
    static CheckpointData empty() {
        return new CheckpointData(new LinkedHashMap<>(), 0, null, null, null);
    }
}
