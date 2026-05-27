/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.SubscriberSnapshot;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Javalin handler for {@code GET /internal/dlq} — dead letter queue status
 * (operational tooling, NOT a public API).
 *
 * <p>Returns a snapshot of every active subscriber's in-memory DLQ ring
 * depth plus its mode, rolling crash-window counter, and the oldest parked
 * entry's timestamp (M3.7). The data is sourced directly from
 * {@link EventBus#subscribers()}; the bus itself already aggregates the
 * per-subscriber state into a {@link SubscriberSnapshot} record.</p>
 *
 * <h2>Response shape (M3.7)</h2>
 * <pre>{@code
 * {
 *   "subscribers": [
 *     {
 *       "subscriberId": "state_projection",
 *       "mode": "LIVE",
 *       "dlqDepth": 0,
 *       "crashCount": 0,
 *       "oldestParkedAt": null
 *     },
 *     {
 *       "subscriberId": "automation",
 *       "mode": "LIVE",
 *       "dlqDepth": 3,
 *       "crashCount": 1,
 *       "oldestParkedAt": "2026-01-01T00:00:05Z"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code oldestParkedAt} is serialized as an ISO-8601 instant via Jackson's
 * {@code JavaTimeModule}, or {@code null} when the subscriber's in-memory
 * DLQ ring is empty.</p>
 *
 * <h2>M3.7 closure of the M3.6e.2 D-2 deviation</h2>
 *
 * <p>The original PLAN-M3 sketch called for an {@code oldestParkedAt} field
 * but the M3.6e.2 {@link SubscriberSnapshot} record only carried 5 fields
 * with no timestamp data — the M3.6e.2 endpoint omitted the field and
 * documented the gap as deviation D-2. M3.7 extends
 * {@link SubscriberSnapshot} with a 6th nullable {@code oldestParkedAt}
 * component (sourced from {@code SubscriberDlq.oldestParkedAt()}, ring head),
 * closing the deviation and aligning the response shape with the original
 * design intent.</p>
 *
 * <h2>NOT gated by readiness</h2>
 *
 * <p>{@code /internal/*} is intentionally outside the
 * {@code ReadinessFilter}'s {@code before("/api/*")} path — operators need
 * DLQ visibility during REPLAY (when the projection is precisely the thing
 * they're waiting on). This is settled decision SD-5.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless. {@link EventBus#subscribers()} is documented thread-safe
 * (per {@code core/event-bus/MODULE_CONTEXT.md} cross-module contracts —
 * "All methods on this interface may be called concurrently").</p>
 *
 * @see RestFilters#installAdminEndpoints(Object, Object, com.homesynapse.state.ReadinessSource, com.homesynapse.state.StateQueryService, java.util.function.LongSupplier)
 * @see SubscriberSnapshot
 */
final class DlqStatusEndpoint implements Handler {

    private final EventBus bus;

    /**
     * Constructs a new DLQ status handler.
     *
     * @param bus the event bus; never {@code null}
     */
    DlqStatusEndpoint(EventBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @Override
    public void handle(Context ctx) {
        apply(new JavalinEndpointContext(ctx));
    }

    /**
     * Pure handler logic — package-private so tests can drive the endpoint
     * with a recording {@link EndpointContext} stub.
     *
     * @param ctx the request/response SPI; never {@code null}
     */
    void apply(EndpointContext ctx) {
        List<SubscriberSnapshot> snapshots = bus.subscribers();
        List<Map<String, Object>> entries = new ArrayList<>(snapshots.size());
        for (SubscriberSnapshot snapshot : snapshots) {
            Map<String, Object> entry = new LinkedHashMap<>(5);
            entry.put("subscriberId", snapshot.subscriberId());
            entry.put("mode", snapshot.mode().name());
            entry.put("dlqDepth", snapshot.dlqDepth());
            entry.put("crashCount", snapshot.crashCount());
            entry.put("oldestParkedAt", snapshot.oldestParkedAt());
            entries.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>(1);
        body.put("subscribers", entries);

        ctx.status(200);
        ctx.json(body);
    }
}
