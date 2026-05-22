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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Javalin handler for {@code GET /internal/projection} — state-projection
 * lifecycle status.
 *
 * <h2>Response shape</h2>
 * <pre>{@code
 * {
 *   "mode": "LIVE",
 *   "viewPosition": 12345,
 *   "entityCount": 42,
 *   "ready": true
 * }
 * }</pre>
 *
 * <p>Field sourcing:</p>
 * <ul>
 *   <li>{@code mode} — {@link ReadinessSource#mode()}'s
 *       {@link SubscriberMode#name()}.</li>
 *   <li>{@code viewPosition} — the injected {@link LongSupplier} (typically
 *       {@code stateProjection::cursorPosition}).</li>
 *   <li>{@code entityCount} — {@link StateQueryService#getSnapshot()}'s
 *       {@code states().size()}. {@link StateQueryService} has no dedicated
 *       count method; the snapshot is the closest publicly-available shape.</li>
 *   <li>{@code ready} — {@code true} only when the projection mode is
 *       {@link SubscriberMode#LIVE}.</li>
 * </ul>
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
 * {@link ReadinessSource#mode()} is documented thread-safe.</p>
 *
 * @see RestFilters#installAdminEndpoints(Object, Object, ReadinessSource, StateQueryService, LongSupplier)
 */
final class ProjectionStatusEndpoint implements Handler {

    private final ReadinessSource readinessSource;
    private final StateQueryService queryService;
    private final LongSupplier viewPositionSupplier;

    /**
     * Constructs a new projection status handler.
     *
     * @param readinessSource      source of projection lifecycle mode;
     *                             never {@code null}
     * @param queryService         the materialized state query service;
     *                             never {@code null}
     * @param viewPositionSupplier supplier for the projection's current
     *                             cursor position; never {@code null}
     */
    ProjectionStatusEndpoint(ReadinessSource readinessSource,
                             StateQueryService queryService,
                             LongSupplier viewPositionSupplier) {
        this.readinessSource = Objects.requireNonNull(readinessSource, "readinessSource");
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.viewPositionSupplier =
                Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
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
        int entityCount = queryService.getSnapshot().states().size();

        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("mode", mode.name());
        body.put("viewPosition", viewPosition);
        body.put("entityCount", entityCount);
        body.put("ready", mode == SubscriberMode.LIVE);

        ctx.status(200);
        ctx.json(body);
    }
}
