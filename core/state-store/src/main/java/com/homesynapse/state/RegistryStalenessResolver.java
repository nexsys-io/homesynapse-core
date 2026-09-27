/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.platform.identity.EntityId;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The production {@link StalenessThresholdResolver}: Doc 03 §3.8's threshold chain over
 * the entity registry and the capability catalog (IR-61).
 *
 * <h2>The chain</h2>
 *
 * <ol>
 *   <li>the entity's override, when it has one;</li>
 *   <li>the SMALLEST {@link Capability#expectedReportInterval() declared interval} among
 *       the entity's capabilities that the catalog knows — any {@code state_reported} on
 *       the entity resets {@code staleAfter}, so the most frequently reporting capability
 *       sets the honest expectation: fresh means the device said anything within the
 *       shortest interval any of its capabilities declared (a plug with
 *       {@code power_meter} 1200 s and {@code energy_meter} 7200 s → 1200 s). The
 *       per-attribute staleness AMD will replace this sentence;</li>
 *   <li>the global default;</li>
 *   <li>otherwise empty — {@code staleAfter} stays {@code null}, never stale.</li>
 * </ol>
 *
 * <h2>One source of truth</h2>
 *
 * <p>A {@link CapabilityInstance} on an {@link Entity} carries the capability's id, never
 * the {@link Capability} object, so this resolver indexes
 * {@code capabilityId → expectedReportInterval()} from the catalog it is handed
 * ({@code StandardCapabilities.all()} at the composition root): the declaration on the
 * capability is the only place a number lives. An id the catalog does not know (a
 * {@code CustomCapability}) contributes nothing. The resolver READS the registry and
 * stores nothing about entities (Doc 03 §2.2: the registry is the structural
 * authority).</p>
 *
 * <h2>The replay-order property</h2>
 *
 * <p>The registry projection and the state projection are independent bus subscribers
 * that replay concurrently at boot, so a {@code state_reported} replayed BEFORE its
 * entity's registration resolves through the registry miss to the global default —
 * empty when there is none — and its {@code staleAfter} stays {@code null} until that
 * entity's NEXT report (seconds for a plug; up to an hour for a sensor). Harmless and
 * self-healing, and a pure function of the log order; it is stated here, not fixed.</p>
 *
 * <h2>Configuration</h2>
 *
 * <p>{@code globalDefault} is an {@link Optional} by deliberate choice: the §9
 * {@code default_staleness_threshold} key is {@code null} by default, and that absence is
 * spelled {@link Optional#empty()} rather than a nullable parameter — a {@code null}
 * Optional is rejected. Every duration (each override, the global default, each catalog
 * declaration) must be non-negative, and a catalog that declares one capability id twice
 * with different intervals is rejected: both are construction-time failures, never a
 * report-time one.</p>
 *
 * <p>Thread-safe: immutable after construction; each call reads the registry's current
 * view, which the {@link EntityRegistry} contract makes safe for concurrent reads. No
 * lock, no I/O, no allocation beyond the returned {@link Optional}.</p>
 *
 * @see StalenessThresholdResolver
 * @see Capability#expectedReportInterval()
 * @since 1.0
 */
public final class RegistryStalenessResolver implements StalenessThresholdResolver {

    private final EntityRegistry registry;
    private final Map<String, Duration> declaredIntervals;
    private final Map<EntityId, Duration> overrides;
    private final Optional<Duration> globalDefault;

    /**
     * Creates the resolver and indexes the catalog's declared intervals.
     *
     * @param registry      the entity registry the chain reads; never {@code null}
     * @param catalog       the capability catalog indexed by id; never {@code null}
     * @param overrides     per-entity thresholds (source 1); never {@code null}
     * @param globalDefault the global default (source 3), {@link Optional#empty()} for
     *                      none; never {@code null}
     * @throws IllegalArgumentException if any duration is negative, or the catalog
     *                                  declares one id twice with different intervals
     */
    public RegistryStalenessResolver(EntityRegistry registry, Collection<Capability> catalog,
            Map<EntityId, Duration> overrides, Optional<Duration> globalDefault) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(catalog, "catalog");
        Map<String, Duration> index = new HashMap<>();
        for (Capability capability : catalog) {
            Optional<Duration> declared = capability.expectedReportInterval();
            if (declared.isEmpty()) {
                continue;
            }
            String capabilityId = capability.capabilityId();
            Duration interval = requireNonNegative(declared.get(),
                    "the interval capability " + capabilityId + " declares");
            Duration prior = index.putIfAbsent(capabilityId, interval);
            if (prior != null && !prior.equals(interval)) {
                throw new IllegalArgumentException("the catalog declares capability "
                        + capabilityId + " twice with different intervals: " + prior
                        + " and " + interval);
            }
        }
        this.declaredIntervals = Map.copyOf(index);
        this.overrides = Map.copyOf(Objects.requireNonNull(overrides, "overrides"));
        this.overrides.forEach((entityId, threshold) ->
                requireNonNegative(threshold, "the override for entity " + entityId));
        this.globalDefault = Objects.requireNonNull(globalDefault, "globalDefault");
        globalDefault.ifPresent(threshold -> requireNonNegative(threshold, "globalDefault"));
    }

    @Override
    public Optional<Duration> thresholdFor(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId");
        Duration override = overrides.get(entityId);
        if (override != null) {
            return Optional.of(override);
        }
        Optional<Entity> entity = registry.findEntity(entityId);
        if (entity.isPresent()) {
            Duration smallest = null;
            for (CapabilityInstance capability : entity.get().capabilities()) {
                String capabilityId = capability.capabilityId();
                Duration declared = (capabilityId == null)
                        ? null : declaredIntervals.get(capabilityId);
                if (declared != null && (smallest == null || declared.compareTo(smallest) < 0)) {
                    smallest = declared;
                }
            }
            if (smallest != null) {
                return Optional.of(smallest);
            }
        }
        return globalDefault;
    }

    private static Duration requireNonNegative(Duration threshold, String what) {
        Objects.requireNonNull(threshold, what);
        if (threshold.isNegative()) {
            throw new IllegalArgumentException(what + " must be non-negative, got " + threshold);
        }
        return threshold;
    }
}
