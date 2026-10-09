/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.state.EntityLink;
import com.homesynapse.state.EntityState;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The wire rendering of an {@link EntityState} for the single-entity reads
 * ({@code GET /api/v1/entities/{id}} and {@code GET /api/v1/entities/{id}/state})
 * — IR-132 (CONFIG-ERROR-1 §4.4): every instant renders {@code Instant.toString()}
 * (ISO-8601 UTC), the rendering the FROZEN v1.1 contract gives the list read's
 * {@code lastReported} and every {@code meta.timestamp}.
 *
 * <p>Handing the record itself to Javalin's default mapper wrote each
 * {@code Instant} as epoch seconds with a decimal ({@code 1791423298.1036468} at
 * BC8), so one field had two shapes across the three entity reads; the dashboard's
 * contract ({@code contract.ts}) typed them {@code string | null} throughout — this
 * restores the contract, it does not change it.</p>
 *
 * <p>The keys are the record's components in their declared order (a snapshot test
 * may pin order); {@code entityId} via {@code toString()} and {@code availability}
 * via {@code name()} as the list read renders them; {@code attributes} is handed
 * through as the object it is (its typed-value envelope untouched, {@code null}
 * values included); {@code link} is the list read's {@code {lqi, rssiDbm, at}}
 * shape, duplicated here on purpose — the list read's bytes are frozen and its own
 * {@code linkJson} is not widened. Deliberately NOT the global mapper: a
 * {@code JavaTimeModule} / {@code WRITE_DATES_AS_TIMESTAMPS} change at
 * {@code Javalin.create} would re-render every {@code Instant} on every endpoint
 * (DLQ status, runs, the causal chain) — a contract-wide change, not this one.</p>
 *
 * <p>Stateless; package-private — {@code java.util} and the state module's types
 * only (no module edge).</p>
 */
final class EntityStateJson {

    private EntityStateJson() {
        // Utility class — no instances.
    }

    /**
     * Renders the record as the read's {@code data} object.
     *
     * @param state the materialized state; never {@code null}
     * @return an insertion-ordered map carrying the twelve component keys
     */
    static Map<String, Object> render(EntityState state) {
        Objects.requireNonNull(state, "state");
        Map<String, Object> json = new LinkedHashMap<>(12);
        json.put("entityId", state.entityId().toString());
        json.put("attributes", state.attributes());
        json.put("availability", state.availability().name());
        json.put("stateVersion", state.stateVersion());
        json.put("lastChanged", iso(state.lastChanged()));
        json.put("lastUpdated", iso(state.lastUpdated()));
        json.put("lastReported", iso(state.lastReported()));
        json.put("staleAfter", iso(state.staleAfter()));
        json.put("stale", state.stale());
        json.put("availabilityReason", state.availabilityReason());
        json.put("lastSeenAt", iso(state.lastSeenAt()));
        json.put("link", state.link() == null ? null : linkJson(state.link()));
        return json;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** The link reading's wire object — the list read's shape, duplicated (§4.4). */
    private static Map<String, Object> linkJson(EntityLink link) {
        Map<String, Object> json = new LinkedHashMap<>(3);
        json.put("lqi", link.lqi());
        json.put("rssiDbm", link.rssiDbm());
        json.put("at", link.at().toString());
        return json;
    }
}
