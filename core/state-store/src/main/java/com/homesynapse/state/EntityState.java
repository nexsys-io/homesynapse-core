/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.AttributeValue;
import com.homesynapse.platform.identity.EntityId;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable materialized state of a single entity at a point in time.
 *
 * <p>{@code EntityState} is the primary data unit that flows through dashboards,
 * automation evaluation, and API responses. It is the output of the State Store's
 * event projection — each incoming event (state reports, availability changes,
 * commands) is folded into the current {@code EntityState} for the affected entity.</p>
 *
 * <h2>Attribute Map</h2>
 *
 * <p>The {@code attributes} map is keyed by attribute name (e.g., {@code "brightness"},
 * {@code "temperature_c"}) and valued by {@link AttributeValue} instances. A {@code null}
 * value in the map indicates an attribute that exists in the entity's capability schema
 * but has never received a report — this is not an error condition. The map itself is
 * unmodifiable.</p>
 *
 * <h2>Three-Timestamp Model</h2>
 *
 * <p>Three timestamps track different aspects of entity activity:</p>
 * <ul>
 *   <li>{@code lastChanged} — when an attribute's canonical value last <em>changed</em>
 *       (driven by {@code state_changed} events). This is the timestamp consumers use
 *       for "last activity" displays.</li>
 *   <li>{@code lastUpdated} — when the projection last processed <em>any</em> event for
 *       this entity, including no-op reports where the value did not change. This
 *       tracks projection currency.</li>
 *   <li>{@code lastReported} — when the entity's integration adapter last sent a
 *       {@code state_reported} event, regardless of whether it produced a state change.
 *       This is needed for staleness detection: a sensor that reports the same
 *       temperature every 30 seconds is not stale, even though {@code lastChanged}
 *       may be hours old.</li>
 * </ul>
 *
 * <h2>State Version and Idempotency</h2>
 *
 * <p>The {@code stateVersion} field advances on every processed event — not just
 * mutations. A {@code state_reported} event that matches the current canonical state
 * still advances {@code stateVersion}. This makes {@code stateVersion} a reliable
 * idempotency cursor for consumers that need to detect whether the projection has
 * processed new events since their last read.</p>
 *
 * <h2>Staleness</h2>
 *
 * <p>The {@code staleAfter} field is nullable. When {@code null}, the entity is never
 * considered stale (default for actuators and event-driven reporters). When non-null,
 * {@code stale} is {@code true} if {@code Instant.now().isAfter(staleAfter)}. The
 * {@code stale} field is derived at read time from {@code staleAfter} and the wall
 * clock (Doc 03 §3.8 AMD-11).</p>
 *
 * <p>Defined in Doc 03 §4.1.</p>
 *
 * @param entityId the unique identifier for this entity, never {@code null}
 * @param attributes the current attribute values keyed by attribute name; unmodifiable.
 *        Values may be {@code null} for attributes that have never received a report.
 * @param availability the current runtime availability status, never {@code null}
 * @param stateVersion monotonically increasing version, advances on every processed event
 * @param lastChanged when an attribute value last changed (driven by {@code state_changed}
 *        events), never {@code null}
 * @param lastUpdated when the projection last processed any event for this entity,
 *        never {@code null}
 * @param lastReported when the integration adapter last sent a {@code state_reported}
 *        event for this entity, never {@code null}
 * @param staleAfter the instant after which this entity is considered stale, or
 *        {@code null} if staleness detection is disabled for this entity
 * @param stale whether this entity is currently stale, derived from {@code staleAfter}
 *        and the wall clock at read time
 * @see StateQueryService
 * @see StateSnapshot
 * @see Availability
 * @see com.homesynapse.device.AttributeValue
 * @see com.homesynapse.event.EventEnvelope
 * @since 1.0
 */
public record EntityState(
        EntityId entityId,
        Map<String, AttributeValue> attributes,
        Availability availability,
        long stateVersion,
        Instant lastChanged,
        Instant lastUpdated,
        Instant lastReported,
        Instant staleAfter,
        boolean stale
) { }
