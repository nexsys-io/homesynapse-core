/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Javalin handler for {@code GET /api/v1/entities/{entityId}} — single
 * entity lookup.
 *
 * <h2>Path parameter</h2>
 *
 * <p>{@code entityId} — a 26-character Crockford Base32 ULID. Parsed via
 * {@link Ulid#parse(String)} → {@link EntityId#of(Ulid)}. Parse failures
 * return {@code 400 Invalid Parameters} with an RFC 9457 problem detail
 * body (per the brief's {@code What to Watch Out For} item 4: do NOT let
 * the {@link IllegalArgumentException} propagate as 500).</p>
 *
 * <h2>Response (200)</h2>
 * <pre>{@code
 * {
 *   "data": { "entityId": "...", "availability": "...", "attributes": {...}, "stale": false, ... },
 *   "meta": { "viewPosition": 12345, "timestamp": "2026-05-22T..." }
 * }
 * }</pre>
 *
 * <p>The {@code data} object is the {@link EntityState} record itself,
 * serialised by Javalin's built-in Jackson. {@link EntityState#attributes()}
 * may contain {@code null} values (per the contract documented in
 * state-store {@code MODULE_CONTEXT.md}); Jackson serialises {@code null}
 * values directly — do <em>not</em> call {@code Map.copyOf()} on
 * attributes, which would reject them.</p>
 *
 * <h2>Response (404)</h2>
 *
 * <p>RFC 9457 problem detail keyed by {@link ProblemType#NOT_FOUND} when
 * the entity does not exist in the materialised view.</p>
 *
 * <h2>Response (400)</h2>
 *
 * <p>RFC 9457 problem detail keyed by {@link ProblemType#INVALID_PARAMETERS}
 * when the {@code entityId} path segment is not a valid ULID.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless — same threading discipline as {@link ListEntitiesEndpoint}.</p>
 *
 * @see RestFilters#installEntityQueryEndpoints(Object, StateQueryService, com.homesynapse.device.EntityRegistry, LongSupplier, java.time.Clock)
 * @see EndpointContext
 */
final class GetEntityEndpoint implements Handler {

    private final StateQueryService queryService;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    /**
     * Constructs a new handler.
     *
     * @param queryService         the materialized state query service;
     *                             never {@code null}
     * @param viewPositionSupplier supplier for the projection's current
     *                             cursor position; never {@code null}
     * @param clock                injected clock; never {@code null}
     */
    GetEntityEndpoint(StateQueryService queryService,
                      LongSupplier viewPositionSupplier,
                      Clock clock) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
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
        String raw = ctx.pathParam("entityId");
        EntityId entityId;
        try {
            entityId = EntityId.of(Ulid.parse(raw));
        } catch (IllegalArgumentException ex) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Path parameter 'entityId' is not a valid ULID: " + raw);
            return;
        }

        Optional<EntityState> maybeState = queryService.getState(entityId);
        if (maybeState.isEmpty()) {
            EndpointResponses.problem(ctx, ProblemType.NOT_FOUND,
                    "Entity not found: " + raw);
            return;
        }

        long viewPosition = viewPositionSupplier.getAsLong();
        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", viewPosition);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", maybeState.get());
        body.put("meta", meta);

        ctx.status(200);
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER,
                Long.toString(viewPosition));
        ctx.json(body);
    }
}
