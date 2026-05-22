/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Production {@link StateQueryService} backed by a live {@link StateStore}.
 *
 * <p>{@code MaterializedStateQueryService} is the read-only query facade over
 * the {@link StateProjection}'s materialized state. All reads are lock-free
 * lookups against the underlying {@link StateStore} (typically a
 * {@code ConcurrentHashMap}-backed adapter). Read consistency follows the
 * three-tier model documented on {@link StateQueryService}: per-entity reads
 * are consistent, batch reads are weakly consistent, snapshot reads are fully
 * consistent.</p>
 *
 * <h2>Staleness recomputation (Doc 03 §3.8, AMD-11)</h2>
 *
 * <p>The {@link StateProjection} writes {@link EntityState#stale} unconditionally
 * as {@code false} — the materialized record is a pure data carrier and the
 * real staleness answer depends on when the question is asked. This class
 * recomputes the {@code stale} flag at read time from
 * {@link EntityState#staleAfter} and the injected {@link Clock}:</p>
 * <ul>
 *   <li>{@code staleAfter == null} → {@code stale = false} (no staleness contract,
 *       typical for actuators and event-driven reporters).</li>
 *   <li>{@code clock.instant().isAfter(staleAfter)} → {@code stale = true}.</li>
 *   <li>Otherwise → {@code stale = false}.</li>
 * </ul>
 *
 * <p>This is HomeSynapse's #1 architectural differentiator at the query layer
 * — no other smart home platform derives staleness at read time from the
 * underlying timestamp model.</p>
 *
 * <h2>View position</h2>
 *
 * <p>The {@link StateStore} port is a pure key-value surface and does not
 * carry the projection's cursor position. The view position is sourced via a
 * {@link LongSupplier} provided at construction — the composition root wires
 * this to {@link StateProjection#cursorPosition()}. This keeps the query
 * service decoupled from the projection's concrete type (DEC-M3-16).</p>
 *
 * <h2>Snapshot replaying flag</h2>
 *
 * <p>{@link StateSnapshot#replaying()} is derived from the
 * {@link ReadinessSource}: {@code true} whenever
 * {@code readinessSource.mode() != LIVE}. Consumers can use this to surface
 * a "catching up" UI affordance distinct from {@link #isReady()}'s boolean.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Fully thread-safe. All state is held by the injected {@link StateStore}
 * (which the contract requires to be lock-free for concurrent reads). The
 * {@link ReadinessSource}, {@link LongSupplier}, and {@link Clock} are
 * expected to be safe for concurrent invocation; the composition root's
 * implementations satisfy this (atomic reference, single {@code long} read,
 * fixed clock).</p>
 *
 * <p>Package-private and final per DEC-M3-16 — consumers see the
 * {@link StateQueryService} interface; the composition root reaches the
 * concrete class only at construction.</p>
 *
 * @see StateQueryService
 * @see StateStore
 * @see ReadinessSource
 * @see StateProjection#cursorPosition()
 */
final class MaterializedStateQueryService implements StateQueryService {

    private final StateStore stateStore;
    private final ReadinessSource readinessSource;
    private final LongSupplier viewPosition;
    private final Clock clock;

    /**
     * Constructs a new query service over the given backing store.
     *
     * @param stateStore     the materialized state store; never {@code null}
     * @param readinessSource the source of subscriber lifecycle mode; never
     *                       {@code null}
     * @param viewPosition   supplier of the projection's current cursor
     *                       position; never {@code null}. The composition root
     *                       wires this to
     *                       {@link StateProjection#cursorPosition()}.
     * @param clock          injected clock used for staleness recomputation;
     *                       never {@code null} (DEC-M3-09 /
     *                       {@code NO_DIRECT_TIME_ACCESS})
     */
    MaterializedStateQueryService(
            StateStore stateStore,
            ReadinessSource readinessSource,
            LongSupplier viewPosition,
            Clock clock) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.readinessSource = Objects.requireNonNull(readinessSource, "readinessSource");
        this.viewPosition = Objects.requireNonNull(viewPosition, "viewPosition");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<EntityState> getState(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId");
        return stateStore.get(entityId).map(this::recomputeStale);
    }

    @Override
    public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
        Objects.requireNonNull(entityIds, "entityIds");
        // LinkedHashMap preserves the caller-visible iteration order based on
        // requested ids and tolerates the (theoretically allowed) null
        // attribute values that EntityState.attributes() may contain.
        // Map.copyOf rejects nulls and would defeat that contract.
        LinkedHashMap<EntityId, EntityState> result = new LinkedHashMap<>();
        for (EntityId id : entityIds) {
            stateStore.get(id).ifPresent(state -> result.put(id, recomputeStale(state)));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public StateSnapshot getSnapshot() {
        Map<EntityId, EntityState> all = stateStore.getAll();
        LinkedHashMap<EntityId, EntityState> recomputed = new LinkedHashMap<>(all.size());
        for (Map.Entry<EntityId, EntityState> entry : all.entrySet()) {
            recomputed.put(entry.getKey(), recomputeStale(entry.getValue()));
        }
        SubscriberMode mode = readinessSource.mode();
        return new StateSnapshot(
                Collections.unmodifiableMap(recomputed),
                viewPosition.getAsLong(),
                clock.instant(),
                mode != SubscriberMode.LIVE,
                Set.of());
    }

    @Override
    public long getViewPosition() {
        return viewPosition.getAsLong();
    }

    @Override
    public boolean isReady() {
        return readinessSource.mode() == SubscriberMode.LIVE;
    }

    /**
     * Returns the input {@link EntityState} with its {@code stale} flag
     * recomputed from {@code staleAfter} and the injected clock. If the
     * stored flag already matches the derived value, the original record is
     * returned unchanged (no allocation).
     */
    private EntityState recomputeStale(EntityState state) {
        Instant staleAfter = state.staleAfter();
        boolean derivedStale = staleAfter != null && clock.instant().isAfter(staleAfter);
        if (state.stale() == derivedStale) {
            return state;
        }
        return new EntityState(
                state.entityId(),
                state.attributes(),
                state.availability(),
                state.stateVersion(),
                state.lastChanged(),
                state.lastUpdated(),
                state.lastReported(),
                state.staleAfter(),
                derivedStale);
    }
}
