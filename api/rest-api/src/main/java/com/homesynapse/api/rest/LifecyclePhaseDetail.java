/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Detail record for a single phase in the four-phase command lifecycle.
 *
 * <p>Present in {@link CommandStatusResponse#lifecycle()} only for phases that
 * have completed (Doc 09 §4.5). An absent key in the lifecycle map means the
 * phase has not yet occurred — there are no sentinel values or placeholder
 * entries for incomplete phases.</p>
 *
 * <p>Phase-specific details vary by phase:</p>
 * <ul>
 *   <li>{@link CommandLifecyclePhase#ACCEPTED ACCEPTED} — {@link #details()} is
 *       {@code null} (no additional information beyond the timestamp).</li>
 *   <li>{@link CommandLifecyclePhase#DISPATCHED DISPATCHED} — {@code details}
 *       includes {@code integration_id}.</li>
 *   <li>{@link CommandLifecyclePhase#ACKNOWLEDGED ACKNOWLEDGED} — {@code details}
 *       includes {@code result}.</li>
 *   <li>{@link CommandLifecyclePhase#CONFIRMED CONFIRMED} — {@code details}
 *       includes {@code match_type}.</li>
 * </ul>
 *
 * <p>Thread-safe (immutable record). The details map, when present, is
 * unmodifiable.</p>
 *
 * @param at      when this phase completed, never {@code null}
 * @param eventId the event that represents this phase, never {@code null}
 * @param details phase-specific details, {@code null} for phases with no
 *                additional information (e.g., {@code ACCEPTED}); when present,
 *                the map is unmodifiable
 *
 * @see CommandStatusResponse
 * @see CommandLifecyclePhase
 * @see <a href="Doc 09 §4.5">Command Status Endpoint</a>
 */
public record LifecyclePhaseDetail(Instant at, String eventId, Map<String, Object> details) {

    /**
     * Creates a new lifecycle phase detail with validation of required fields.
     *
     * <p>The {@code details} map, if non-null, is made unmodifiable via
     * {@link Map#copyOf(Map)}.</p>
     *
     * @throws NullPointerException if {@code at} or {@code eventId} is {@code null}
     */
    public LifecyclePhaseDetail {
        Objects.requireNonNull(at, "at must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        if (details != null) {
            details = Map.copyOf(details);
        }
    }
}
