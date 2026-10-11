/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.state.EntityLink;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.RecoveryCardQueryService;
import com.homesynapse.state.RecoveryCardRecord;
import com.homesynapse.state.StaleRecordBound;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * <p><strong>v1.1.7 (AVAIL-API-1, AMD-103 §3):</strong> {@link #appendRecoveryKeys} appends
 * the eleven recovery-card keys AFTER the frozen keys, beside {@code availability} and never
 * under it, for the entity's owning device — the newest {@code probe_answered}'s instant,
 * window and counts, the contract's five fields, and R-G's {@code recordDueBy} /
 * {@code recordOverdueSince} from {@link StaleRecordBound} (Q10: computed here, never on the
 * dashboard). The null state is a law: while the recovery-card projection is not LIVE every
 * one is {@code null}; the {@code probe*} keys are null with no record, the contract keys
 * with no contract, the two {@code record*} keys with no contract. {@code probeLink} is NOT
 * served (Q12). The list read's {@code summarise} appends the same keys through the same
 * method, so the three reads agree byte for byte.</p>
 *
 * <p>Stateless; package-private — {@code java.util} and the state module's types
 * only (no module edge).</p>
 */
final class EntityStateJson {

    /** The v1.1.7 keys, in wire order (AVAIL-API-1; FIELDS §3's proposed names). */
    static final List<String> RECOVERY_KEYS = List.of(
            "probeAnsweredAt", "probeWindowStart", "probesAnswered", "probesMissed",
            "availabilityClass", "reportIntervalSeconds", "silenceLimitSeconds",
            "missesToDark", "recordIntervalSeconds", "recordDueBy", "recordOverdueSince");

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

    /**
     * Renders the record as the read's {@code data} object at v1.1.7: the twelve frozen keys,
     * then the eleven recovery-card keys for the entity's owning device.
     *
     * @param state    the materialized state; never {@code null}
     * @param deviceId the entity's owning device from the LIVE registry, or {@code null}
     *                 (no device: every recovery key null)
     * @param cards    the recovery-card read interface; never {@code null}
     * @param now      the core's clock at the read (R-G's {@code recordOverdueSince});
     *                 never {@code null}
     * @return an insertion-ordered map carrying the twenty-three keys
     */
    static Map<String, Object> render(EntityState state, DeviceId deviceId,
            RecoveryCardQueryService cards, Instant now) {
        Map<String, Object> json = render(state);
        appendRecoveryKeys(json, state, deviceId, cards, now);
        return json;
    }

    /**
     * Appends the eleven v1.1.7 keys (AMD-103 §3; AVAIL-API-1 §4.7) — every one {@code null}
     * while the projection is not LIVE or the entity owns no device.
     *
     * @param json     the row or data object, in wire order; never {@code null}
     * @param state    the materialized state (its {@code lastReported} feeds the bound)
     * @param deviceId the owning device, or {@code null}
     * @param cards    the recovery-card read interface; never {@code null}
     * @param now      the core's clock at the read; never {@code null}
     */
    static void appendRecoveryKeys(Map<String, Object> json, EntityState state, DeviceId deviceId,
            RecoveryCardQueryService cards, Instant now) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(cards, "cards");
        Objects.requireNonNull(now, "now");
        Optional<RecoveryCardRecord> record = cards.isLive() && deviceId != null
                ? cards.recordFor(deviceId) : Optional.empty();
        RecoveryCardRecord row = record.orElse(null);
        boolean probe = row != null && row.hasProbe();
        boolean contract = row != null && row.hasContract();
        json.put("probeAnsweredAt", probe ? iso(row.probeAt()) : null);
        json.put("probeWindowStart", probe ? iso(row.probeWindowStart()) : null);
        json.put("probesAnswered", probe ? row.probesAnswered() : null);
        json.put("probesMissed", probe ? row.probesMissed() : null);
        json.put("availabilityClass", contract ? row.availabilityClass() : null);
        json.put("reportIntervalSeconds", contract ? row.reportIntervalSeconds() : null);
        json.put("silenceLimitSeconds", contract ? row.silenceLimitSeconds() : null);
        json.put("missesToDark", contract ? row.missesToDark() : null);
        json.put("recordIntervalSeconds", contract ? row.recordIntervalSeconds() : null);
        if (contract) {
            int asked = cards.askedCount();
            json.put("recordDueBy", StaleRecordBound.dueBy(row, state.lastReported(), asked,
                    StaleRecordBound.SWEEP).map(Instant::toString).orElse(null));
            json.put("recordOverdueSince", StaleRecordBound.overdueSince(row,
                    state.lastReported(), asked, StaleRecordBound.SWEEP, now)
                    .map(Instant::toString).orElse(null));
        } else {
            json.put("recordDueBy", null);
            json.put("recordOverdueSince", null);
        }
    }

    /**
     * {@code meta.recoveryViewPosition}: the projection's position while LIVE, else
     * {@code null} (the third instant of A1's consistency window).
     *
     * @param cards the recovery-card read interface; never {@code null}
     * @return the position, or {@code null}
     */
    static Long recoveryViewPosition(RecoveryCardQueryService cards) {
        Objects.requireNonNull(cards, "cards");
        return cards.isLive() ? Long.valueOf(cards.position()) : null;
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
