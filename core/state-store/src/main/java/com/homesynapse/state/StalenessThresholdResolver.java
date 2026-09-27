/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.platform.identity.EntityId;

import java.time.Duration;
import java.util.Optional;

/**
 * Resolves an entity's staleness threshold — Doc 03 §3.8's threshold chain (AMD-11), the
 * seam the {@link StateProjection} consults on every {@code state_reported} (IR-61).
 *
 * <p>Doc 03 §3.8 resolves the threshold from three sources in priority order:</p>
 * <ol>
 *   <li>a per-entity override (§9 {@code staleness_overrides});</li>
 *   <li>a capability-based default — the {@code expected_report_interval} a capability
 *       declares ({@link com.homesynapse.device.Capability#expectedReportInterval()});</li>
 *   <li>a global default (§9 {@code default_staleness_threshold}).</li>
 * </ol>
 *
 * <p>On every {@code state_reported} the projection writes
 * {@code staleAfter = eventTime + threshold} from the envelope's event-time stamp when this
 * resolver answers, and {@code staleAfter = null} — the entity is never stale — when it
 * answers empty. AMD-53-INV-02: {@code staleAfter} is event-time-derived and deterministic,
 * a target for real-time comparison; {@code stale} itself is derived at read time against
 * the real clock, never here.</p>
 *
 * <p>{@link RegistryStalenessResolver} is the production implementation. {@link #none()}
 * resolves nothing: it is the resolver of every construction path that names none, so a
 * pre-IR-61 caller keeps {@code staleAfter == null} byte-identically.</p>
 *
 * <p>Implementations are called on the projection's subscriber thread for every report:
 * thread-safe, non-blocking, free of I/O, and never throwing for an unknown entity.</p>
 *
 * @see StateProjection
 * @see EntityState#staleAfter()
 * @since 1.0
 */
@FunctionalInterface
public interface StalenessThresholdResolver {

    /**
     * Resolves the staleness threshold for an entity.
     *
     * @param entityId the entity whose threshold to resolve; never {@code null}
     * @return the resolved threshold, or empty when no source applies (never stale)
     */
    Optional<Duration> thresholdFor(EntityId entityId);

    /**
     * Returns the resolver that resolves nothing — every entity is never stale.
     *
     * @return a resolver whose {@link #thresholdFor} is always empty; never {@code null}
     */
    static StalenessThresholdResolver none() {
        return entityId -> Optional.empty();
    }
}
