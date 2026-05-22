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
 * depth plus its mode and rolling crash-window counter. The data is
 * sourced directly from {@link EventBus#subscribers()}; the bus itself
 * already aggregates the per-subscriber state into a {@link SubscriberSnapshot}
 * record.</p>
 *
 * <h2>Response shape</h2>
 * <pre>{@code
 * {
 *   "subscribers": [
 *     {
 *       "subscriberId": "state_projection",
 *       "mode": "LIVE",
 *       "dlqDepth": 0,
 *       "crashCount": 0
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Deviation from the brief's example shape</h2>
 *
 * <p>The PLAN-M3 sketch in the M3.6e.2 brief shows {@code parkedCount} and
 * {@code oldestParkedAt} fields. The actual production event-bus snapshot
 * record carries {@code dlqDepth} (in-memory ring) and {@code crashCount}
 * (rolling 10-minute window) and does not expose a "oldest parked at"
 * timestamp — see {@link SubscriberSnapshot} (5 fields:
 * {@code subscriberId}, {@code mode}, {@code checkpoint}, {@code dlqDepth},
 * {@code crashCount}). The brief explicitly invites the coder to adjust
 * the response shape to "what's actually available on
 * {@code SubscriberSnapshot} or {@code EventBus}". This endpoint exposes
 * exactly those fields. Adding {@code oldestParkedAt} would require
 * tracking parked-at timestamps in {@code SubscriberDlq} — a follow-up
 * enhancement, not M3.6e.2 scope.</p>
 *
 * <h2>NOT gated by readiness</h2>
 *
 * <p>{@code /internal/*} is intentionally outside the
 * {@code ReadinessFilter}'s {@code before("/api/*")} path — operators need
 * DLQ visibility during REPLAY (when the projection is precisely the thing
 * they're waiting on). This is settled decision SD-5 from the brief.</p>
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
            Map<String, Object> entry = new LinkedHashMap<>(4);
            entry.put("subscriberId", snapshot.subscriberId());
            entry.put("mode", snapshot.mode().name());
            entry.put("dlqDepth", snapshot.dlqDepth());
            entry.put("crashCount", snapshot.crashCount());
            entries.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>(1);
        body.put("subscribers", entries);

        ctx.status(200);
        ctx.json(body);
    }
}
