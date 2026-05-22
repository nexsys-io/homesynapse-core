/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Javalin handler for {@code GET /api/v1/entities} — paginated listing of
 * materialized entity summaries.
 *
 * <h2>Query parameters</h2>
 * <ul>
 *   <li>{@code limit} — integer, default 50, clamped silently to
 *       {@code [1, 100]}. Malformed values fall back to the default.</li>
 *   <li>{@code sort} — {@code ASC} or {@code DESC}, default {@code ASC},
 *       case-insensitive. Sort is by entity ULID string (which is also
 *       lexicographic-chronological per LTD-04).</li>
 * </ul>
 *
 * <h2>Response (200)</h2>
 * <pre>{@code
 * {
 *   "data": [ { "entityId": "...", "availability": "...", "stale": false } ],
 *   "meta": { "viewPosition": 12345, "timestamp": "2026-05-22T..." }
 * }
 * }</pre>
 *
 * <p>Header: {@code X-HomeSynapse-View-Position: {viewPosition}}.</p>
 *
 * <h2>Behavioural contract</h2>
 *
 * <p>Pulls a fully-consistent {@link StateSnapshot} from
 * {@link StateQueryService#getSnapshot()} so the entity set in {@code data}
 * is internally consistent. Returns an empty {@code data} array (NOT 404)
 * when no entities exist. The {@code stale} field on each summary reflects
 * read-time staleness recomputation performed by
 * {@code MaterializedStateQueryService} before the snapshot is returned.</p>
 *
 * <h2>Why the spec calls it {@code subjectType} but the response omits it</h2>
 *
 * <p>The PLAN-M3 §10.6 example response sketch includes a {@code subjectType}
 * field, but {@link EntityState} (the production record returned by
 * {@link StateQueryService#getSnapshot()}) carries no such field. The
 * canonical {@code SubjectType} lives on each event's {@code SubjectRef},
 * not on the materialised state. Adding it would require either a
 * registry-lookup cross-call (out of scope for M3.6e.2) or a synthetic
 * inference from attributes (premature). The summary therefore exposes
 * {@code entityId}, {@code availability}, and {@code stale} — the three
 * fields that {@link EntityState} actually carries and that operators
 * routinely inspect when scanning entity lists.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless. All state accessed through the injected
 * {@link StateQueryService} (lock-free reads) and {@link LongSupplier}
 * (single-{@code long} read). Safe to call from any number of Jetty
 * worker threads concurrently.</p>
 *
 * @see RestFilters#installEntityQueryEndpoints(Object, StateQueryService, LongSupplier, java.time.Clock)
 * @see EndpointContext
 */
final class ListEntitiesEndpoint implements Handler {

    /** Default {@code limit} when the client does not supply one. */
    static final int DEFAULT_LIMIT = 50;

    /** Maximum {@code limit} — values above this are silently clamped down. */
    static final int MAX_LIMIT = 100;

    /** Diagnostic header carrying the projection's current view position. */
    static final String VIEW_POSITION_HEADER = "X-HomeSynapse-View-Position";

    private final StateQueryService queryService;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    /**
     * Constructs a new handler over the given collaborators.
     *
     * @param queryService         the materialized state query service;
     *                             never {@code null}
     * @param viewPositionSupplier supplier for the projection's current
     *                             cursor position; never {@code null}
     * @param clock                injected clock for response timestamps
     *                             (DEC-M3-09 / {@code NO_DIRECT_TIME_ACCESS});
     *                             never {@code null}
     */
    ListEntitiesEndpoint(StateQueryService queryService,
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
        int limit = parseLimit(ctx.queryParam("limit"));
        boolean ascending = parseSortAscending(ctx.queryParam("sort"));

        StateSnapshot snapshot = queryService.getSnapshot();
        long viewPosition = viewPositionSupplier.getAsLong();

        Comparator<EntityState> comparator = Comparator.comparing(
                e -> e.entityId().toString());
        if (!ascending) {
            comparator = comparator.reversed();
        }

        List<Map<String, Object>> summaries = new ArrayList<>();
        snapshot.states().values().stream()
                .sorted(comparator)
                .limit(limit)
                .forEach(state -> summaries.add(summarise(state)));

        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", viewPosition);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", summaries);
        body.put("meta", meta);

        ctx.status(200);
        ctx.header(VIEW_POSITION_HEADER, Long.toString(viewPosition));
        ctx.json(body);
    }

    private static Map<String, Object> summarise(EntityState state) {
        Map<String, Object> summary = new LinkedHashMap<>(3);
        summary.put("entityId", state.entityId().toString());
        summary.put("availability", state.availability().name());
        summary.put("stale", state.stale());
        return summary;
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            return DEFAULT_LIMIT;
        }
        if (parsed < 1) {
            return 1;
        }
        return Math.min(parsed, MAX_LIMIT);
    }

    private static boolean parseSortAscending(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return !"DESC".equals(raw.trim().toUpperCase(Locale.ROOT));
    }
}
