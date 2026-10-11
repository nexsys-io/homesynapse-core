/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.RecoveryCardQueryService;
import com.homesynapse.state.StateQueryService;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.time.Instant;
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
 * <h2>Availability detail (J1 / LINK-READ-2) and the one instant shape (IR-132)</h2>
 *
 * <p>The {@code data} object renders through
 * {@link EntityStateJson#render(EntityState)} (CONFIG-ERROR-1 §4.4): the record's
 * components by name, in their order, every instant as {@code Instant.toString()}
 * (ISO-8601 UTC — the same rendering as the list read and {@code meta.timestamp});
 * the J1 keys {@code availabilityReason}, {@code lastSeenAt} and {@code link} ride
 * with it, {@code null} where the projection holds none. A dark entity's record
 * therefore says why, when it was last heard and how its link read at that frame.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless — same threading discipline as {@link ListEntitiesEndpoint}.</p>
 *
 * @see GetEntityEndpoint
 * @see RestFilters#installEntityQueryEndpoints(Object, StateQueryService, com.homesynapse.device.EntityRegistry, LongSupplier, RecoveryCardQueryService, java.time.Clock)
 */
final class GetEntityStateEndpoint implements Handler {

    private final StateQueryService queryService;
    private final LongSupplier viewPositionSupplier;
    private final EntityRegistry entityRegistry;
    private final RecoveryCardQueryService recoveryCards;
    private final Clock clock;

    /**
     * The pre-AVAIL-API-1 shape (test-facing): no registry, no recovery-card projection —
     * every v1.1.7 key renders {@code null}, {@code meta.recoveryViewPosition} null.
     */
    GetEntityStateEndpoint(StateQueryService queryService,
                           LongSupplier viewPositionSupplier,
                           Clock clock) {
        this(queryService, viewPositionSupplier, new InMemoryEntityRegistry(),
                RecoveryCardQueryService.notLive(), clock);
    }

    /**
     * Constructs a new handler.
     *
     * @param queryService         the materialized state query service;
     *                             never {@code null}
     * @param viewPositionSupplier supplier for the projection's current
     *                             cursor position; never {@code null}
     * @param entityRegistry       the LIVE entity registry the owning device is read
     *                             from (v1.1.7, AVAIL-API-1); never {@code null}
     * @param recoveryCards        the recovery-card read interface (AVAIL-API-1);
     *                             never {@code null}
     * @param clock                injected clock; never {@code null}
     */
    GetEntityStateEndpoint(StateQueryService queryService,
                           LongSupplier viewPositionSupplier,
                           EntityRegistry entityRegistry,
                           RecoveryCardQueryService recoveryCards,
                           Clock clock) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
        this.viewPositionSupplier =
                Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.recoveryCards = Objects.requireNonNull(recoveryCards, "recoveryCards");
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
        Instant now = clock.instant();
        Map<String, Object> meta = new LinkedHashMap<>(3);
        meta.put("viewPosition", viewPosition);
        meta.put("timestamp", now.toString());
        // v1.1.7 (AVAIL-API-1): the recovery-card projection's position, null while not LIVE.
        meta.put("recoveryViewPosition", EntityStateJson.recoveryViewPosition(recoveryCards));

        // v1.1.7 (AVAIL-API-1, AMD-103 §3): the owning device from the LIVE registry (the
        // list read's CG-2 reading) feeds the eleven recovery-card keys after the frozen ones.
        DeviceId deviceId = entityRegistry.findEntity(entityId).map(Entity::deviceId).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", EntityStateJson.render(maybeState.get(), deviceId, recoveryCards, now));
        body.put("meta", meta);

        ctx.status(200);
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER,
                Long.toString(viewPosition));
        ctx.json(body);
    }
}
