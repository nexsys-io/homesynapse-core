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
 * Javalin handler for {@code GET /api/v1/entities/{entityId}/state} —
 * detailed entity state.
 *
 * <p>Per PLAN-M3 §10.6 the distinction between this endpoint and
 * {@link GetEntityEndpoint} is cosmetic for MVP — both delegate to
 * {@link StateQueryService#getState(EntityId)} and return the same
 * {@link EntityState} record. Future tiers may return a different
 * projection (e.g., richer attribute metadata) from this endpoint without
 * affecting {@code GetEntityEndpoint}'s contract.</p>
 *
 * <h2>Why these two endpoints exist as separate classes</h2>
 *
 * <p>Future divergence: the M5+ automation surface plans a "summary view"
 * for {@code GET /entities/{id}} (entityId + availability + a small set of
 * always-rendered attributes for hot-path dashboards) while
 * {@code GET /entities/{id}/state} stays as the full record. Keeping the
 * two as separate handler classes lets that future change land as edits to
 * a single file rather than a route-level fork inside a shared class.</p>
 *
 * <h2>Staleness recomputation</h2>
 *
 * <p>{@link EntityState#stale()} reflects the value computed at read time
 * by {@code MaterializedStateQueryService} from {@code staleAfter} and the
 * injected clock (Doc 03 §3.8 / AMD-11). The handler does not re-derive it.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless — same threading discipline as {@link ListEntitiesEndpoint}.</p>
 *
 * @see GetEntityEndpoint
 * @see RestFilters#installEntityQueryEndpoints(Object, StateQueryService, com.homesynapse.device.EntityRegistry, LongSupplier, java.time.Clock)
 */
final class GetEntityStateEndpoint implements Handler {

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
    GetEntityStateEndpoint(StateQueryService queryService,
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
