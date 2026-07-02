/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.state.ReadinessSource;
import com.homesynapse.state.StateQueryService;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Javalin handler for {@code GET /internal/projection} — state-projection
 * lifecycle status, serialized to the frozen v1.1.1 dashboard contract §A4
 * (M7.5c-a envelope conformance, DRIFT-1 adjudication 2026-07-02).
 *
 * <h2>Response shape</h2>
 * <pre>{@code
 * {
 *   "data": {
 *     "mode": "LIVE",
 *     "viewPosition": 12345,
 *     "lagEvents": 0,
 *     "projectionVersion": 5,
 *     "entityCount": 42,
 *     "ready": true
 *   },
 *   "meta": { "viewPosition": 12345, "timestamp": "2026-07-02T..." }
 * }
 * }</pre>
 *
 * <p>The first four {@code data} fields are the FROZEN §A4 contract; the
 * dashboard polls {@code meta.viewPosition} at 1–2s as its change-detection
 * cursor (freeze §0 — EVERY read carries {@code meta}). {@code entityCount}
 * and {@code ready} are the ruled ADDITIVE extras (retained per the DRIFT-1
 * adjudication — clients tolerate extras).</p>
 *
 * <p>Field sourcing:</p>
 * <ul>
 *   <li>{@code mode} — {@link ReadinessSource#mode()}'s
 *       {@link SubscriberMode#name()}.</li>
 *   <li>{@code viewPosition} — the injected {@link LongSupplier} (typically
 *       {@code stateProjection::cursorPosition}). The same sampled value is
 *       echoed in {@code meta.viewPosition} so the body is internally
 *       consistent.</li>
 *   <li>{@code lagEvents} — log head minus projection cursor, clamped at 0
 *       (the two suppliers are sampled sequentially, so the projection can
 *       legitimately advance past an already-sampled head). Head comes from
 *       the injected log-head {@link LongSupplier} (typically
 *       {@code eventStore::latestPosition}, wired from the composition
 *       root — a {@code java.base} supplier, so NO event-model module edge).</li>
 *   <li>{@code projectionVersion} — the running code's projection version,
 *       injected from the composition root (the same constant it passes to
 *       {@code StateProjection.create(...)}; M4.0b-5, AMD-53).</li>
 *   <li>{@code entityCount} — {@link StateQueryService#getSnapshot()}'s
 *       {@code states().size()}. {@link StateQueryService} has no dedicated
 *       count method; the snapshot is the closest publicly-available shape.</li>
 *   <li>{@code ready} — {@code true} only when the projection mode is
 *       {@link SubscriberMode#LIVE}.</li>
 * </ul>
 *
 * <p>Caching mirrors the conforming reads (freeze §0): weak ETag
 * {@code W/"{viewPosition}"} plus the {@code X-HomeSynapse-View-Position}
 * diagnostic header (the M7.5a/b pattern — no {@code ETagProvider} impl
 * exists; format inline).</p>
 *
 * <h2>NOT gated by readiness</h2>
 *
 * <p>{@code /internal/*} is outside {@link ReadinessFilter}'s
 * {@code before("/api/*")} path — operators need this endpoint precisely
 * during REPLAY/COLD/TRANSITION when they're investigating "is the
 * projection making progress?". Settled decision SD-5 from the brief.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless. {@link StateQueryService} reads are lock-free,
 * {@link ReadinessSource#mode()} is documented thread-safe, and both
 * {@link LongSupplier}s are single-{@code long} reads.</p>
 *
 * @see RestFilters#installAdminEndpoints(Object, Object, ReadinessSource,
 *      StateQueryService, LongSupplier, LongSupplier, int, Clock)
 */
final class ProjectionStatusEndpoint implements Handler {

    private final ReadinessSource readinessSource;
    private final StateQueryService queryService;
    private final LongSupplier viewPositionSupplier;
    private final LongSupplier logHeadSupplier;
    private final int projectionVersion;
    private final Clock clock;

    /**
     * Constructs a new projection status handler.
     *
     * @param readinessSource      source of projection lifecycle mode;
     *                             never {@code null}
     * @param queryService         the materialized state query service;
     *                             never {@code null}
     * @param viewPositionSupplier supplier for the projection's current
     *                             cursor position; never {@code null}
     * @param logHeadSupplier      supplier for the event log's head position
     *                             (typically {@code eventStore::latestPosition});
     *                             never {@code null}
     * @param projectionVersion    the running code's projection version
     *                             (the composition root's constant; must be ≥ 1)
     * @param clock                injected clock for response timestamps
     *                             (DEC-M3-09 / {@code NO_DIRECT_TIME_ACCESS});
     *                             never {@code null}
     */
    ProjectionStatusEndpoint(ReadinessSource readinessSource,
                             StateQueryService queryService,
                             LongSupplier viewPositionSupplier,
                             LongSupplier logHeadSupplier,
                             int projectionVersion,
                             Clock clock) {
        this.readinessSource = Objects.requireNonNull(readinessSource, "readinessSource");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.viewPositionSupplier =
                Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        this.logHeadSupplier = Objects.requireNonNull(logHeadSupplier, "logHeadSupplier");
        this.projectionVersion = projectionVersion;
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
        SubscriberMode mode = readinessSource.mode();
        long viewPosition = viewPositionSupplier.getAsLong();
        long logHead = logHeadSupplier.getAsLong();
        long lagEvents = Math.max(0L, logHead - viewPosition);
        int entityCount = queryService.getSnapshot().states().size();

        Map<String, Object> data = new LinkedHashMap<>(6);
        data.put("mode", mode.name());
        data.put("viewPosition", viewPosition);
        data.put("lagEvents", lagEvents);
        data.put("projectionVersion", projectionVersion);
        data.put("entityCount", entityCount);
        data.put("ready", mode == SubscriberMode.LIVE);

        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", viewPosition);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", data);
        body.put("meta", meta);

        ctx.status(200);
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER, Long.toString(viewPosition));
        // Projection status is a mutable State-Query-plane view; a weak ETag keyed on
        // the projection position mirrors the conforming reads (no ETagProvider impl
        // exists; format inline, the M7.5a ListRunsEndpoint pattern).
        ctx.header("ETag", "W/\"" + viewPosition + "\"");
        ctx.json(body);
    }
}
