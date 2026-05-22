/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Test fake {@link StateQueryService} for the M3.6e.2 endpoint handler tests.
 *
 * <p>Configurable through {@link #put(EntityState)} (one entity at a time).
 * {@link #getSnapshot()} returns the accumulated map plus a configurable
 * view position and mode. {@link #getState(EntityId)} and
 * {@link #getStates(Set)} read from the same backing map.</p>
 *
 * <p>Test-only — not part of the rest-api module's exported API.</p>
 */
final class FakeStateQueryService implements StateQueryService {

    private final LinkedHashMap<EntityId, EntityState> states = new LinkedHashMap<>();
    private long viewPosition = 0L;
    private boolean replaying = false;
    private Instant snapshotTime = Instant.EPOCH;

    FakeStateQueryService() {
    }

    FakeStateQueryService put(EntityState state) {
        states.put(state.entityId(), state);
        return this;
    }

    FakeStateQueryService withViewPosition(long viewPosition) {
        this.viewPosition = viewPosition;
        return this;
    }

    FakeStateQueryService withReplaying(boolean replaying) {
        this.replaying = replaying;
        return this;
    }

    FakeStateQueryService withSnapshotTime(Instant snapshotTime) {
        this.snapshotTime = snapshotTime;
        return this;
    }

    @Override
    public Optional<EntityState> getState(EntityId entityId) {
        return Optional.ofNullable(states.get(entityId));
    }

    @Override
    public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
        LinkedHashMap<EntityId, EntityState> result = new LinkedHashMap<>();
        for (EntityId id : entityIds) {
            EntityState s = states.get(id);
            if (s != null) {
                result.put(id, s);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public StateSnapshot getSnapshot() {
        return new StateSnapshot(
                Collections.unmodifiableMap(new LinkedHashMap<>(states)),
                viewPosition,
                snapshotTime,
                replaying,
                Set.of());
    }

    @Override
    public long getViewPosition() {
        return viewPosition;
    }

    @Override
    public boolean isReady() {
        return !replaying;
    }

    /**
     * Convenience subscriber-mode mirror for tests that wire a
     * {@link com.homesynapse.state.ReadinessSource} alongside this fake.
     * Not part of the {@link StateQueryService} contract; provided here
     * so a single test fixture can drive both consumers consistently.
     */
    SubscriberMode mode() {
        return replaying ? SubscriberMode.REPLAY : SubscriberMode.LIVE;
    }
}
