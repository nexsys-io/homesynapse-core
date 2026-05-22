/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.bus.EventBus;
import com.homesynapse.state.ReadinessSource;
import com.homesynapse.state.StateQueryService;

import io.javalin.Javalin;

import java.time.Clock;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Public gateway for installing REST-layer request filters and endpoint
 * handlers onto the embedded HTTP server.
 *
 * <p>The filter and handler implementations ({@link ReadinessFilter},
 * {@link ListEntitiesEndpoint}, {@link GetEntityEndpoint},
 * {@link GetEntityStateEndpoint}, {@link DlqStatusEndpoint},
 * {@link ProjectionStatusEndpoint}) are package-private per DEC-M3-16 —
 * their Javalin-specific signatures must not appear in the rest-api
 * module's exported public API because {@code io.javalin} is not
 * re-exported ({@code requires} without {@code transitive}). This class
 * provides the public wiring surface: each method accepts the Javalin
 * application instance and the HomeSynapse dependencies needed, constructs
 * the package-private implementations, and registers them on the correct
 * paths.</p>
 *
 * <p>The {@code javalinApp} parameter is typed as {@link Object} to avoid
 * exposing {@code io.javalin.Javalin} in the method signatures. Callers
 * (i.e., the composition root in the lifecycle module) pass their
 * {@code Javalin} instance; the methods cast internally. The lifecycle
 * module already declares {@code requires io.javalin} independently, so
 * the cast is always safe at the call site.</p>
 *
 * <p>The same {@link Object}-erasure applies to the {@code bus} parameter
 * of {@link #installAdminEndpoints} — {@link EventBus} comes from a
 * non-transitive {@code requires com.homesynapse.event.bus} in rest-api's
 * module-info, so it must not appear on the exported method signature.
 * The lifecycle module already declares its own {@code requires
 * com.homesynapse.event.bus} (transitively via {@code requires
 * com.homesynapse.event.bus} in the lifecycle module-info), so the
 * internal cast is safe.</p>
 *
 * <p>Thread safety: stateless utility class. The filter/handler instances
 * it creates are documented as thread-safe in their own Javadoc.</p>
 *
 * @see ReadinessFilter
 * @see ListEntitiesEndpoint
 * @see GetEntityEndpoint
 * @see GetEntityStateEndpoint
 * @see DlqStatusEndpoint
 * @see ProjectionStatusEndpoint
 */
public final class RestFilters {

    private RestFilters() {
        // utility class
    }

    /**
     * Installs the readiness gate as a Javalin {@code before("/api/*")}
     * handler.
     *
     * <p>The gate rejects all {@code /api/*} requests with
     * {@code 503 Service Unavailable} until the State Projection reaches
     * {@code SubscriberMode.LIVE}. See {@link ReadinessFilter} for the full
     * response shape and threading model.</p>
     *
     * @param javalinApp      the Javalin application instance (must be a
     *                        {@link io.javalin.Javalin}); never {@code null}
     * @param readinessSource the source of subscriber lifecycle mode;
     *                        never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a
     *         {@link io.javalin.Javalin} instance
     */
    public static void installReadinessGate(Object javalinApp,
                                            ReadinessSource readinessSource) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(readinessSource, "readinessSource");
        Javalin app = (Javalin) javalinApp;
        app.before("/api/*", new ReadinessFilter(readinessSource));
    }

    /**
     * Registers the M3.6e.2 entity query endpoints on the given Javalin
     * application instance:
     * <ul>
     *   <li>{@code GET /api/v1/entities} — paginated list summary</li>
     *   <li>{@code GET /api/v1/entities/{entityId}} — single entity</li>
     *   <li>{@code GET /api/v1/entities/{entityId}/state} — detailed state</li>
     * </ul>
     *
     * <p>All three endpoints live under {@code /api/*} and are therefore
     * gated by the {@link ReadinessFilter} installed via
     * {@link #installReadinessGate(Object, ReadinessSource)} — they will
     * return {@code 503} until the State Projection reaches
     * {@code SubscriberMode.LIVE}.</p>
     *
     * @param javalinApp           the Javalin application instance (must be
     *                             a {@link io.javalin.Javalin}); never
     *                             {@code null}
     * @param queryService         the materialized state query service;
     *                             never {@code null}
     * @param viewPositionSupplier supplier for the projection's current
     *                             cursor position (typically
     *                             {@code stateProjection::cursorPosition});
     *                             never {@code null}
     * @param clock                injected clock for response timestamps;
     *                             never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a
     *         {@link io.javalin.Javalin} instance
     */
    public static void installEntityQueryEndpoints(Object javalinApp,
                                                   StateQueryService queryService,
                                                   LongSupplier viewPositionSupplier,
                                                   Clock clock) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(queryService, "queryService");
        Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        Objects.requireNonNull(clock, "clock");
        Javalin app = (Javalin) javalinApp;
        app.get("/api/v1/entities",
                new ListEntitiesEndpoint(queryService, viewPositionSupplier, clock));
        app.get("/api/v1/entities/{entityId}",
                new GetEntityEndpoint(queryService, viewPositionSupplier, clock));
        app.get("/api/v1/entities/{entityId}/state",
                new GetEntityStateEndpoint(queryService, viewPositionSupplier, clock));
    }

    /**
     * Registers the M3.6e.2 admin/operational endpoints on the given Javalin
     * application instance:
     * <ul>
     *   <li>{@code GET /internal/dlq} — per-subscriber DLQ status</li>
     *   <li>{@code GET /internal/projection} — state-projection status</li>
     * </ul>
     *
     * <p>These endpoints live under {@code /internal/*} and are
     * intentionally NOT gated by {@link ReadinessFilter} — operators need
     * them precisely during REPLAY/COLD/TRANSITION when they are
     * investigating why the projection is not yet LIVE (settled decision
     * SD-5 from the M3.6e.2 brief).</p>
     *
     * <p>The {@code bus} parameter is typed as {@link Object} so the
     * exported public API does not leak {@link EventBus} from the
     * non-transitive {@code requires com.homesynapse.event.bus} edge.
     * Callers pass their {@code EventBus} instance and this method casts
     * internally.</p>
     *
     * @param javalinApp           the Javalin application instance (must be
     *                             a {@link io.javalin.Javalin}); never
     *                             {@code null}
     * @param bus                  the event bus (must be an
     *                             {@link EventBus}); never {@code null}
     * @param readinessSource      source of projection lifecycle mode;
     *                             never {@code null}
     * @param queryService         the materialized state query service;
     *                             never {@code null}
     * @param viewPositionSupplier supplier for the projection's current
     *                             cursor position; never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a
     *         {@link io.javalin.Javalin} instance, or if {@code bus} is not
     *         an {@link EventBus} instance
     */
    public static void installAdminEndpoints(Object javalinApp,
                                             Object bus,
                                             ReadinessSource readinessSource,
                                             StateQueryService queryService,
                                             LongSupplier viewPositionSupplier) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(readinessSource, "readinessSource");
        Objects.requireNonNull(queryService, "queryService");
        Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        Javalin app = (Javalin) javalinApp;
        EventBus eventBus = (EventBus) bus;
        app.get("/internal/dlq", new DlqStatusEndpoint(eventBus));
        app.get("/internal/projection",
                new ProjectionStatusEndpoint(
                        readinessSource, queryService, viewPositionSupplier));
    }
}
