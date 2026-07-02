/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.SubscriberSnapshot;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Javalin handler for {@code GET /internal/dlq} — dead letter queue status
 * (operational tooling, NOT a public API), serialized to the frozen v1.1.1
 * dashboard contract §A5 (M7.5c-a envelope conformance, DRIFT-1 adjudication
 * 2026-07-02).
 *
 * <h2>Response shape</h2>
 * <pre>{@code
 * {
 *   "data": {
 *     "depth": 3,
 *     "parkedSubscribers": ["automation"],
 *     "subscribers": [
 *       {
 *         "subscriberId": "state_projection",
 *         "mode": "LIVE",
 *         "dlqDepth": 0,
 *         "crashCount": 0,
 *         "oldestParkedAt": null
 *       },
 *       {
 *         "subscriberId": "automation",
 *         "mode": "LIVE",
 *         "dlqDepth": 3,
 *         "crashCount": 1,
 *         "oldestParkedAt": "2026-01-01T00:00:05Z"
 *       }
 *     ]
 *   },
 *   "meta": { "viewPosition": 12345, "timestamp": "2026-07-02T..." }
 * }
 * }</pre>
 *
 * <p>{@code depth} and {@code parkedSubscribers} are the FROZEN §A5 contract;
 * the per-subscriber {@code subscribers} detail (the pre-freeze M3.7 shape) is
 * the ruled ADDITIVE extra — it is cheap (the same one-pass projection) and
 * clients tolerate extras. The dashboard polls {@code meta.viewPosition} at
 * 1–2s as its change-detection cursor (freeze §0 — EVERY read carries
 * {@code meta}).</p>
 *
 * <p>Derivation (from {@link EventBus#subscribers()}, the same source the
 * pre-freeze endpoint read): {@code depth} is the sum of every subscriber's
 * in-memory DLQ ring depth; {@code parkedSubscribers} lists the
 * {@code subscriberId} of each subscriber whose ring is non-empty
 * ({@code dlqDepth > 0}) in bus order. "Parked" is the DLQ-entry vocabulary
 * ({@link SubscriberSnapshot#oldestParkedAt()} — M3.7); {@code SubscriberMode}
 * has no PARKED value, and a SUSPENDED subscriber remains visible via the
 * additive {@code subscribers[].mode} field.</p>
 *
 * <p>{@code oldestParkedAt} is rendered as an ISO-8601 string via
 * {@code Instant.toString()} (the conforming M7.5a/b pattern — no reliance on
 * Jackson's {@code JavaTimeModule}), or {@code null} when the subscriber's
 * in-memory DLQ ring is empty.</p>
 *
 * <p>Caching mirrors the conforming reads (freeze §0): weak ETag
 * {@code W/"{viewPosition}"} plus the {@code X-HomeSynapse-View-Position}
 * diagnostic header.</p>
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
 * "All methods on this interface may be called concurrently"); the
 * {@link LongSupplier} is a single-{@code long} read.</p>
 *
 * @see RestFilters#installAdminEndpoints(Object, Object,
 *      com.homesynapse.state.ReadinessSource,
 *      com.homesynapse.state.StateQueryService, LongSupplier, LongSupplier,
 *      int, Clock)
 * @see SubscriberSnapshot
 */
final class DlqStatusEndpoint implements Handler {

    private final EventBus bus;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    /**
     * Constructs a new DLQ status handler.
     *
     * @param bus                  the event bus; never {@code null}
     * @param viewPositionSupplier supplier for the projection's current
     *                             cursor position (feeds {@code meta.viewPosition});
     *                             never {@code null}
     * @param clock                injected clock for response timestamps
     *                             (DEC-M3-09 / {@code NO_DIRECT_TIME_ACCESS});
     *                             never {@code null}
     */
    DlqStatusEndpoint(EventBus bus, LongSupplier viewPositionSupplier, Clock clock) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.viewPositionSupplier =
                Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        int depth = 0;
        List<String> parkedSubscribers = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>(snapshots.size());
        for (SubscriberSnapshot snapshot : snapshots) {
            depth += snapshot.dlqDepth();
            if (snapshot.dlqDepth() > 0) {
                parkedSubscribers.add(snapshot.subscriberId());
            }
            Map<String, Object> entry = new LinkedHashMap<>(5);
            entry.put("subscriberId", snapshot.subscriberId());
            entry.put("mode", snapshot.mode().name());
            entry.put("dlqDepth", snapshot.dlqDepth());
            entry.put("crashCount", snapshot.crashCount());
            entry.put("oldestParkedAt",
                    snapshot.oldestParkedAt() == null
                            ? null : snapshot.oldestParkedAt().toString());
            entries.add(entry);
        }

        Map<String, Object> data = new LinkedHashMap<>(3);
        data.put("depth", depth);
        data.put("parkedSubscribers", parkedSubscribers);
        data.put("subscribers", entries);

        long viewPosition = viewPositionSupplier.getAsLong();
        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", viewPosition);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", data);
        body.put("meta", meta);

        ctx.status(200);
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER, Long.toString(viewPosition));
        // DLQ status is a mutable operational view; a weak ETag keyed on the projection
        // position mirrors the conforming reads (no ETagProvider impl exists; format
        // inline, the M7.5a ListRunsEndpoint pattern).
        ctx.header("ETag", "W/\"" + viewPosition + "\"");
        ctx.json(body);
    }
}
