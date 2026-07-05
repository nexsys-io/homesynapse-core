/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.AttributeSchema;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.IntValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
 * <h2>Query-time brightness percent (Doc 08 §3.5, M9.4b §2.3)</h2>
 *
 * <p>The materialized {@code brightness} attribute is the CANONICAL 0–254
 * level; Doc 08 §3.5 pins "percentage derived at query time". Every read path
 * appends the derived {@code brightness_percent} key when the entity's
 * brightness {@link AttributeSchema} (resolved via the injected
 * {@link EntityRegistry} supplier — deferred to read time, so construction
 * order never matters) carries numeric bounds and the materialized value is
 * numeric. Derived at read, NEVER stored, NEVER an event. Every miss —
 * registry absent, entity unknown, schema boundless, value null, degraded, or
 * non-numeric — yields the undecorated result; the read path never throws.</p>
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

    private static final Logger LOG =
            LoggerFactory.getLogger(MaterializedStateQueryService.class);

    /** The canonical attribute the percent derives from (Doc 08 §3.5). */
    private static final String BRIGHTNESS_KEY = "brightness";

    /** The derived read-only key (chosen name, snake_case attribute convention). */
    private static final String BRIGHTNESS_PERCENT_KEY = "brightness_percent";

    /**
     * The registry-less supplier the 4-arg {@code StateQueryService.materialized}
     * overload passes — decoration disabled. Lives here (not on the interface)
     * so no synthetic lambda method lands on the interface's reflective surface.
     */
    static final Supplier<EntityRegistry> NO_REGISTRY = () -> null;

    private final StateStore stateStore;
    private final ReadinessSource readinessSource;
    private final LongSupplier viewPosition;
    private final Supplier<EntityRegistry> entityRegistry;
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
     * @param entityRegistry supplier of the registry the brightness-percent
     *                       decoration resolves attribute schemas from; the
     *                       supplier itself is never {@code null} but MAY
     *                       return {@code null} (no registry — decoration
     *                       skipped). Deferred to read time so construction
     *                       order never matters (M9.4b §2.3).
     * @param clock          injected clock used for staleness recomputation;
     *                       never {@code null} (DEC-M3-09 /
     *                       {@code NO_DIRECT_TIME_ACCESS})
     */
    MaterializedStateQueryService(
            StateStore stateStore,
            ReadinessSource readinessSource,
            LongSupplier viewPosition,
            Supplier<EntityRegistry> entityRegistry,
            Clock clock) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.readinessSource = Objects.requireNonNull(readinessSource, "readinessSource");
        this.viewPosition = Objects.requireNonNull(viewPosition, "viewPosition");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<EntityState> getState(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId");
        return stateStore.get(entityId).map(this::readView);
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
            stateStore.get(id).ifPresent(state -> result.put(id, readView(state)));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public StateSnapshot getSnapshot() {
        Map<EntityId, EntityState> all = stateStore.getAll();
        LinkedHashMap<EntityId, EntityState> recomputed = new LinkedHashMap<>(all.size());
        for (Map.Entry<EntityId, EntityState> entry : all.entrySet()) {
            recomputed.put(entry.getKey(), readView(entry.getValue()));
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
     * The single per-result read projection: staleness recomputation followed
     * by the query-time {@code brightness_percent} decoration (M9.4b §2.3).
     */
    private EntityState readView(EntityState state) {
        return decorateBrightnessPercent(recomputeStale(state));
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

    /**
     * Appends the derived {@code brightness_percent} key (Doc 08 §3.5:
     * "percentage derived at query time") when the entity's brightness
     * {@link AttributeSchema} carries numeric bounds and the materialized
     * value is numeric. Total by construction — every miss returns the
     * undecorated input; a query service that fails a read over a weird
     * schema would be worse than no percent, so the registry consultation
     * additionally degrades (with a DEBUG) instead of propagating.
     */
    private EntityState decorateBrightnessPercent(EntityState state) {
        Map<String, AttributeValue> attributes = state.attributes();
        if (attributes == null || !attributes.containsKey(BRIGHTNESS_KEY)) {
            return state;
        }
        AttributeValue value = attributes.get(BRIGHTNESS_KEY);
        if (value == null || !(value.rawValue() instanceof Number number)) {
            return state;   // never reported, degraded, or non-numeric — no decoration
        }
        AttributeSchema schema;
        try {
            schema = brightnessSchemaFor(state.entityId());
        } catch (RuntimeException ex) {
            LOG.debug("brightness_percent decoration skipped for {}: registry read failed ({})",
                    state.entityId(), ex.getMessage());
            return state;
        }
        if (schema == null || schema.minimum() == null || schema.maximum() == null) {
            return state;
        }
        double min = schema.minimum().doubleValue();
        double max = schema.maximum().doubleValue();
        if (max == min) {
            return state;   // degenerate span — never divide by zero
        }
        int percent = (int) Math.round((number.doubleValue() - min) * 100.0 / (max - min));
        // Rebuild null-tolerantly: EntityState.attributes() may carry null values
        // (schema-declared, never reported) — Map.copyOf would reject them.
        LinkedHashMap<String, AttributeValue> decorated = new LinkedHashMap<>(attributes);
        decorated.put(BRIGHTNESS_PERCENT_KEY, new IntValue(percent));
        return new EntityState(
                state.entityId(),
                Collections.unmodifiableMap(decorated),
                state.availability(),
                state.stateVersion(),
                state.lastChanged(),
                state.lastUpdated(),
                state.lastReported(),
                state.staleAfter(),
                state.stale());
    }

    /**
     * Resolves the entity's brightness schema: the first capability instance
     * whose attribute map contains the {@code brightness} key. {@code null}
     * when the registry is absent, the entity is unknown, or no capability
     * declares the attribute.
     */
    private AttributeSchema brightnessSchemaFor(EntityId entityId) {
        EntityRegistry registry = entityRegistry.get();
        if (registry == null) {
            return null;
        }
        Optional<Entity> entity = registry.findEntity(entityId);
        if (entity.isEmpty()) {
            return null;
        }
        for (CapabilityInstance capability : entity.get().capabilities()) {
            AttributeSchema schema = capability.attributes().get(BRIGHTNESS_KEY);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }
}
