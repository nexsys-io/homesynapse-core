/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.automation.ExplanationService;
import com.homesynapse.automation.StandardCommandValidator;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.state.ReadinessSource;
import com.homesynapse.state.StateQueryService;

import io.javalin.Javalin;
import io.javalin.http.Context;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Public gateway for installing REST-layer request filters and endpoint
 * handlers onto the embedded HTTP server.
 *
 * <p>The filter and handler implementations ({@link ReadinessFilter},
 * {@link HealthEndpoint}, {@link ListEntitiesEndpoint}, {@link GetEntityEndpoint},
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
 * @see HealthEndpoint
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
     * Registers the R-9 unauthenticated loopback readiness bit — {@code GET /health}
     * and {@code HEAD /health} — served by ONE {@link HealthEndpoint} over the same
     * {@link ReadinessSource} the {@link #installReadinessGate readiness gate} reads
     * (200 ⇔ {@code SubscriberMode.LIVE}, 503 otherwise, body
     * {@code {"status":"<mode>"}}). Closes escalation E3: the packaged unit's
     * {@code ExecStartPost} probe no longer needs the pairing artifact.
     *
     * <p>Register AFTER {@link #installAuth(Object, AuthMiddleware, RateLimiter)} —
     * the exemption lives in the filter ({@code isHealthProbeRequest}: GET/HEAD,
     * the exact path, a LOOPBACK literal only — R-H1), not here: this method
     * registers a route, never a filter, so auth-before-bind (AB-1) is unchanged.
     * The route is NOT under the {@code /api/*} readiness gate by path; the handler
     * evaluates the same predicate itself.</p>
     *
     * <p>{@code HEAD} is registered explicitly (verified at the Javalin 6.7.0
     * bytecode, {@code DefaultTasks}' HTTP task): a HEAD with no HEAD entry but a
     * GET entry is answered with a bare 200 WITHOUT running the GET handler — a
     * passes-but-false "ready" during REPLAY for any HEAD-based prober. With its
     * own entry the same handler runs; Jetty drops the body for HEAD.</p>
     *
     * @param javalinApp      the Javalin application instance (must be a
     *                        {@link io.javalin.Javalin}); never {@code null}
     * @param readinessSource the source of the projection's lifecycle mode — the
     *                        same instance handed to the readiness gate; never
     *                        {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a
     *         {@link io.javalin.Javalin} instance
     */
    public static void installHealthEndpoint(Object javalinApp,
                                             ReadinessSource readinessSource) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(readinessSource, "readinessSource");
        Javalin app = (Javalin) javalinApp;
        HealthEndpoint health = new HealthEndpoint(readinessSource);
        app.get(HealthEndpoint.PATH, health);
        app.head(HealthEndpoint.PATH, health);
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
     * <p>v1.1.3 (CG-2/CG-3, 2026-09-05): the list rows additionally carry
     * {@code deviceId} and {@code lastReported}. The {@code entityRegistry}
     * parameter feeds {@code deviceId} and MUST be the SAME instance the
     * registry projection writes (the composition root guarantees it — never
     * a fresh registry); it is read-only here ({@code findEntity}) and is
     * handed to the list endpoint only. {@link EntityRegistry} appears directly
     * on this exported signature (unlike the {@code Object}-erased registry of
     * {@link #installCommandEndpoints}) because {@code com.homesynapse.device}
     * reaches this module's consumers through {@code requires transitive
     * com.homesynapse.state} → {@code requires transitive com.homesynapse.device}
     * — no module-info change.</p>
     *
     * @param javalinApp           the Javalin application instance (must be
     *                             a {@link io.javalin.Javalin}); never
     *                             {@code null}
     * @param queryService         the materialized state query service;
     *                             never {@code null}
     * @param entityRegistry       the LIVE entity registry the list rows'
     *                             {@code deviceId} is read from; never
     *                             {@code null}
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
                                                   EntityRegistry entityRegistry,
                                                   LongSupplier viewPositionSupplier,
                                                   Clock clock) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(queryService, "queryService");
        Objects.requireNonNull(entityRegistry, "entityRegistry");
        Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        Objects.requireNonNull(clock, "clock");
        Javalin app = (Javalin) javalinApp;
        app.get("/api/v1/entities",
                new ListEntitiesEndpoint(queryService, entityRegistry, viewPositionSupplier,
                        clock));
        app.get("/api/v1/entities/{entityId}",
                new GetEntityEndpoint(queryService, viewPositionSupplier, clock));
        app.get("/api/v1/entities/{entityId}/state",
                new GetEntityStateEndpoint(queryService, viewPositionSupplier, clock));
    }

    /**
     * Registers the M3.6e.2 admin/operational endpoints on the given Javalin
     * application instance:
     * <ul>
     *   <li>{@code GET /internal/dlq} — DLQ status (frozen v1.1.1 §A5)</li>
     *   <li>{@code GET /internal/projection} — state-projection status
     *       (frozen v1.1.1 §A4)</li>
     * </ul>
     *
     * <p>M7.5c-a conformed both responses to the frozen {@code {data, meta}}
     * envelope (DRIFT-1 adjudication 2026-07-02); the handlers therefore need
     * the projection cursor, the log head, the projection version, and a
     * {@link Clock} — all threaded from the composition root as
     * {@code java.base} types (NO new module edge).</p>
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
     *                             cursor position (typically
     *                             {@code stateProjection::cursorPosition});
     *                             never {@code null}
     * @param logHeadSupplier      supplier for the event log's head position
     *                             (typically {@code eventStore::latestPosition}
     *                             — feeds the frozen A4 {@code lagEvents});
     *                             never {@code null}
     * @param projectionVersion    the running code's projection version (the
     *                             same constant the composition root passes to
     *                             {@code StateProjection.create(...)})
     * @param clock                injected clock for response timestamps;
     *                             never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a
     *         {@link io.javalin.Javalin} instance, or if {@code bus} is not
     *         an {@link EventBus} instance
     */
    public static void installAdminEndpoints(Object javalinApp,
                                             Object bus,
                                             ReadinessSource readinessSource,
                                             StateQueryService queryService,
                                             LongSupplier viewPositionSupplier,
                                             LongSupplier logHeadSupplier,
                                             int projectionVersion,
                                             Clock clock) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(readinessSource, "readinessSource");
        Objects.requireNonNull(queryService, "queryService");
        Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        Objects.requireNonNull(logHeadSupplier, "logHeadSupplier");
        Objects.requireNonNull(clock, "clock");
        Javalin app = (Javalin) javalinApp;
        EventBus eventBus = (EventBus) bus;
        app.get("/internal/dlq",
                new DlqStatusEndpoint(eventBus, viewPositionSupplier, clock));
        app.get("/internal/projection",
                new ProjectionStatusEndpoint(readinessSource, queryService,
                        viewPositionSupplier, logHeadSupplier, projectionVersion, clock));
    }

    /**
     * Registers the R-6 token-admin surface (2026-08-22) on the given Javalin
     * application instance — for full-access token-holders and the pairing
     * wizard's future hand-off:
     * <ul>
     *   <li>{@code GET /internal/tokens} — every stored token's public summary
     *       (never a hash, never a raw token)</li>
     *   <li>{@code POST /internal/tokens} — mint; the raw token is returned ONCE</li>
     *   <li>{@code DELETE /internal/tokens/{keyId}} — revoke (204 / 404)</li>
     * </ul>
     *
     * <p>Same {@code /internal/*} class as {@link #installAdminEndpoints}: behind
     * the {@link #installAuth(Object, AuthMiddleware, RateLimiter) catch-all auth
     * filter}, outside the readiness gate; each handler then requires
     * {@link ApiKeyClaims#fullAccess()} as the second layer (403 otherwise). No
     * {@code meta.viewPosition}/ETag — these are not projection reads. Register
     * AFTER {@code installAuth} (the lifecycle composition root does so). The
     * operator path that needs NO token ({@code rotate} after a disclosure) is the
     * request file consumed by {@link OpaqueTokenStore#processOperatorRequests()},
     * not this surface. See {@link TokenAdminEndpoints}.</p>
     *
     * @param javalinApp the Javalin application instance (must be a
     *                   {@link io.javalin.Javalin}); never {@code null}
     * @param store      the token store the auth filter validates against —
     *                   the SAME instance, so a revoke here is a 403 on the next
     *                   request; never {@code null}
     * @param clock      injected clock for {@code meta.timestamp}; never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a
     *         {@link io.javalin.Javalin} instance
     */
    public static void installTokenAdminEndpoints(Object javalinApp,
                                                  OpaqueTokenStore store,
                                                  Clock clock) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(clock, "clock");
        Javalin app = (Javalin) javalinApp;
        TokenAdminEndpoints endpoints = new TokenAdminEndpoints(store, clock);
        app.get(TokenAdminEndpoints.COLLECTION_PATH, endpoints.listHandler());
        app.post(TokenAdminEndpoints.COLLECTION_PATH, endpoints.mintHandler());
        app.delete(TokenAdminEndpoints.ITEM_PATH, endpoints.revokeHandler());
    }

    /**
     * Registers the M7.5a run-query (causal read) endpoints on the given Javalin
     * application instance:
     * <ul>
     *   <li>{@code GET /api/v1/runs} — the "why did this fire?" terminal-run list</li>
     *   <li>{@code GET /api/v1/runs/{runId}/causal-chain} — the causal-chain detail tree</li>
     * </ul>
     *
     * <p>Both endpoints live under {@code /api/*} and therefore inherit the
     * {@link #installAuth(Object, AuthMiddleware, RateLimiter) bearer-token auth} filter and
     * the {@link #installReadinessGate(Object, ReadinessSource) 503 readiness gate} — register
     * this AFTER both of those (the lifecycle composition root does so).</p>
     *
     * <p>The {@code explanationService} parameter is typed as {@link Object} so the exported
     * public API does not leak {@code com.homesynapse.automation.ExplanationService} from the
     * non-transitive {@code requires com.homesynapse.automation} edge (DEC-M3-16, the same
     * {@link Object}-erasure as {@link #installAdminEndpoints}'s {@code bus} param). The
     * lifecycle module declares its own {@code requires com.homesynapse.automation}, so the
     * internal cast is safe at the call site.</p>
     *
     * @param javalinApp           the Javalin application instance (must be a
     *                             {@link io.javalin.Javalin}); never {@code null}
     * @param explanationService   the run explanation projection (must be an
     *                             {@code ExplanationService}); never {@code null}
     * @param viewPositionSupplier supplier for the projection's current cursor position
     *                             (typically {@code stateProjection::cursorPosition}); never
     *                             {@code null}
     * @param clock                injected clock for response timestamps; never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a {@link io.javalin.Javalin}, or
     *         if {@code explanationService} is not an {@code ExplanationService}
     */
    public static void installRunQueryEndpoints(Object javalinApp,
                                                Object explanationService,
                                                LongSupplier viewPositionSupplier,
                                                Clock clock) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(explanationService, "explanationService");
        Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        Objects.requireNonNull(clock, "clock");
        Javalin app = (Javalin) javalinApp;
        ExplanationService explanations = (ExplanationService) explanationService;
        app.get("/api/v1/runs",
                new ListRunsEndpoint(explanations, viewPositionSupplier, clock));
        app.get("/api/v1/runs/{runId}/causal-chain",
                new GetRunCausalChainEndpoint(explanations, viewPositionSupplier, clock));
    }

    /**
     * Installs the automation read endpoints (M7.5b): {@code GET /api/v1/automations} (the
     * component-based automation list) and {@code GET /api/v1/automations/{id}/non-firing} (the
     * "why did this <em>not</em> fire?" verdict). The sibling of
     * {@link #installRunQueryEndpoints(Object, Object, LongSupplier, Clock)} — both consume the
     * <em>same</em> {@link ExplanationService} (M7.5b adds {@code explainNonFiring}/
     * {@code listAutomations} to it) across the same query-service boundary.
     *
     * <p>Both endpoints live under {@code /api/*} and therefore inherit the bearer-token auth
     * filter and the 503 readiness gate — register this AFTER both (the lifecycle composition root
     * does so).</p>
     *
     * <p>The {@code explanationService} parameter is typed as {@link Object} so the exported public
     * API does not leak {@code com.homesynapse.automation.ExplanationService} from the
     * non-transitive {@code requires com.homesynapse.automation} edge (DEC-M3-16; the same
     * {@link Object}-erasure as {@link #installRunQueryEndpoints}). The lifecycle module declares
     * its own {@code requires com.homesynapse.automation}, so the internal cast is safe at the call
     * site.</p>
     *
     * @param javalinApp           the Javalin application instance (must be a
     *                             {@link io.javalin.Javalin}); never {@code null}
     * @param explanationService   the explanation projection (must be an {@code ExplanationService});
     *                             never {@code null}
     * @param viewPositionSupplier supplier for the projection's current cursor position
     *                             (typically {@code stateProjection::cursorPosition}); never {@code null}
     * @param clock                injected clock for response timestamps; never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a {@link io.javalin.Javalin}, or if
     *         {@code explanationService} is not an {@code ExplanationService}
     */
    public static void installAutomationQueryEndpoints(Object javalinApp,
                                                       Object explanationService,
                                                       LongSupplier viewPositionSupplier,
                                                       Clock clock) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(explanationService, "explanationService");
        Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        Objects.requireNonNull(clock, "clock");
        Javalin app = (Javalin) javalinApp;
        ExplanationService explanations = (ExplanationService) explanationService;
        app.get("/api/v1/automations",
                new ListAutomationsEndpoint(explanations, viewPositionSupplier, clock));
        app.get("/api/v1/automations/{id}/non-firing",
                new GetNonFiringEndpoint(explanations, viewPositionSupplier, clock));
    }

    /**
     * Registers the CMD-API command write surface (Doc 09 §4.3–§4.5):
     * <ul>
     *   <li>{@code POST /api/v1/entities/{entityId}/commands} — issue a command
     *       (202 = the {@code command_issued} event is durable, INV-ES-04)</li>
     *   <li>{@code GET /api/v1/commands/{commandId}} — the four-phase lifecycle
     *       status assembled from the command's correlation chain</li>
     * </ul>
     *
     * <p>Both routes live under {@code /api/*} and therefore inherit the
     * {@link #installAuth(Object, AuthMiddleware, RateLimiter) bearer-token auth}
     * filter and the {@link #installReadinessGate(Object, ReadinessSource) 503
     * readiness gate} — register this AFTER both (the lifecycle composition
     * root does so).</p>
     *
     * <p>The {@code eventPublisher}, {@code entityRegistry}, and
     * {@code eventStore} parameters are typed as {@link Object} so the exported
     * public API does not leak {@code com.homesynapse.event.EventPublisher} /
     * {@code EventStore} / {@code com.homesynapse.device.EntityRegistry}, which
     * reach this module through automation's {@code requires transitive}
     * closure, not through an exported edge of rest-api's own (DEC-M3-16 —
     * the {@link #installAdminEndpoints} {@code bus} precedent). The validator,
     * idempotency cache, and both handlers are constructed internally.</p>
     *
     * @param javalinApp                   the Javalin application instance
     *                                     (must be a {@link io.javalin.Javalin});
     *                                     never {@code null}
     * @param eventPublisher               the event publisher (must be an
     *                                     {@link EventPublisher}); never
     *                                     {@code null}
     * @param entityRegistry               the entity registry (must be an
     *                                     {@link EntityRegistry}); never
     *                                     {@code null}
     * @param eventStore                   the event store (must be an
     *                                     {@link EventStore}); never {@code null}
     * @param defaultConfirmationTimeoutMs the config-sourced confirmation
     *                                     timeout fallback — the SAME value the
     *                                     action executor receives (Doc 07 §9)
     * @param viewPositionSupplier         supplier for the projection's current
     *                                     cursor position; never {@code null}
     * @param clock                        injected clock for response
     *                                     timestamps and the idempotency TTL;
     *                                     never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a
     *         {@link io.javalin.Javalin}, or the erased parameters are not the
     *         documented types
     */
    public static void installCommandEndpoints(Object javalinApp,
                                               Object eventPublisher,
                                               Object entityRegistry,
                                               Object eventStore,
                                               int defaultConfirmationTimeoutMs,
                                               LongSupplier viewPositionSupplier,
                                               Clock clock) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(eventPublisher, "eventPublisher");
        Objects.requireNonNull(entityRegistry, "entityRegistry");
        Objects.requireNonNull(eventStore, "eventStore");
        Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        Objects.requireNonNull(clock, "clock");
        Javalin app = (Javalin) javalinApp;
        EventPublisher publisher = (EventPublisher) eventPublisher;
        EntityRegistry registry = (EntityRegistry) entityRegistry;
        EventStore store = (EventStore) eventStore;
        IdempotencyCache idempotencyCache = new IdempotencyCache(clock);
        app.post("/api/v1/entities/{entityId}/commands",
                new IssueCommandEndpoint(publisher, registry,
                        new StandardCommandValidator(registry), idempotencyCache,
                        defaultConfirmationTimeoutMs, viewPositionSupplier, clock));
        app.get("/api/v1/commands/{commandId}",
                new GetCommandStatusEndpoint(store, registry, viewPositionSupplier, clock));
    }

    /** Request attribute key carrying the authenticated identity to downstream handlers. */
    static final String IDENTITY_ATTRIBUTE = "hs.api.identity";

    /**
     * Installs the catch-all authentication + rate-limiting filter (the C1 close,
     * AB-1). The filter runs as a Javalin {@code before(*)} handler — before
     * <em>any</em> route resolves, covering {@code /api/*}, {@code /internal/*},
     * and every other path (INV-SE-02; the {@code /internal/*} admin routes sit
     * outside the readiness gate but MUST still be authenticated) — with EXACTLY
     * TWO exemptions, each with its own invariant:
     * <ul>
     *   <li>the posture-(A) static-shell allowlist (DASH-SERVE, ruled 2026-07-26):
     *       {@code GET}/{@code HEAD} on {@code /}, {@code /dashboard}, and
     *       {@code /dashboard/**} ({@link #isPublicShellRequest}) — the shell is
     *       inert public bytes (the same trust class as a downloaded app binary);</li>
     *   <li>the loopback readiness probe (R-9 / E3-HEALTH, R-H1 LOOPBACK-ONLY, ruled
     *       2026-08-22): {@code GET}/{@code HEAD} on exactly {@code /health} from a
     *       LOOPBACK address literal ({@link #isHealthProbeRequest}) — a readiness
     *       bit for the unit's {@code ExecStartPost} probe: one enum word, no data;
     *       off loopback it needs a token like any route.</li>
     * </ul>
     * The shared invariant: no DATA route is ever unauthenticated. The two
     * early-returns in {@code authorize()} are independently reversible — removing
     * either restores the unconditional guard for its path. It is registered
     * <em>before</em> {@link #installReadinessGate(Object, ReadinessSource)} so it
     * precedes the {@code /api/*} readiness gate.
     *
     * <p>Per request, in order:</p>
     * <ol>
     *   <li><strong>Canonicalize the path</strong> and reject {@code ..} /
     *       encoded-traversal / control sequences <em>before</em> the auth decision
     *       (R-δ AX-1 / CVE-2023-27482) → 400.</li>
     *   <li><strong>Exempt the static shell, then the loopback probe</strong> — a
     *       {@code GET}/{@code HEAD} shell request, or a {@code GET}/{@code HEAD}
     *       {@code /health} from loopback, returns here (no identity, no rate-limit
     *       key); every other request continues.</li>
     *   <li><strong>Authenticate</strong> via {@link AuthMiddleware} → 401 (missing/
     *       malformed header) or 403 (invalid/expired/revoked token).</li>
     *   <li><strong>Rate-limit</strong> the authenticated key via {@link RateLimiter}
     *       → 429 + {@code Retry-After} when exhausted.</li>
     * </ol>
     *
     * <p>On any of these the filter throws an {@link ApiException}; the registered
     * exception handler serializes it as an RFC 9457 {@code application/problem+json}
     * body (with a {@code correlation_id}) and the matched endpoint never runs.
     * Throwing — not merely setting a status — is what halts the pipeline, so an
     * unauthenticated request can never reach a handler.</p>
     *
     * @param javalinApp     the Javalin application instance (must be a
     *                       {@link io.javalin.Javalin}); never {@code null}
     * @param authMiddleware the bearer-token auth middleware; never {@code null}
     * @param rateLimiter    the per-key rate limiter; never {@code null}
     * @throws ClassCastException if {@code javalinApp} is not a
     *         {@link io.javalin.Javalin} instance
     */
    public static void installAuth(Object javalinApp,
                                   AuthMiddleware authMiddleware,
                                   RateLimiter rateLimiter) {
        Objects.requireNonNull(javalinApp, "javalinApp");
        Objects.requireNonNull(authMiddleware, "authMiddleware");
        Objects.requireNonNull(rateLimiter, "rateLimiter");
        Javalin app = (Javalin) javalinApp;
        app.exception(ApiException.class, RestFilters::writeProblem);
        app.before(ctx -> authorize(ctx, authMiddleware, rateLimiter));
    }

    private static void authorize(Context ctx,
                                  AuthMiddleware authMiddleware,
                                  RateLimiter rateLimiter) {
        if (!isPathSafe(ctx.path())) {
            throw problem(ProblemType.INVALID_PARAMETERS,
                    "request path contains an illegal traversal or control sequence");
        }
        // Order is load-bearing: traversal/control rejection FIRST (a
        // `GET /dashboard/../internal/dlq` dies at the gate before either exemption
        // can see it), the two exemptions second, authentication third, rate-limit
        // fourth. Each early-return is independently reversible.
        if (isPublicShellRequest(ctx.method().name(), ctx.path())) {
            return;   // posture (A): the static shell serves without auth; no identity, no rate-limit key
        }
        if (isHealthProbeRequest(ctx.method().name(), ctx.path(), ctx.ip())) {
            return;   // R-9: the loopback readiness bit — one enum word, no data; no identity, no rate-limit key
        }
        ApiKeyIdentity identity = authMiddleware.authenticate(ctx.header("Authorization"));
        RateLimitResult limit = rateLimiter.check(identity.keyId());
        if (!limit.allowed()) {
            ctx.header("Retry-After", Long.toString(limit.retryAfterSeconds()));
            throw problem(ProblemType.RATE_LIMITED,
                    "rate limit exceeded; retry after " + limit.retryAfterSeconds() + " seconds");
        }
        ctx.attribute(IDENTITY_ATTRIBUTE, identity);
    }

    /**
     * The posture-(A) static-shell exemption (ruled 2026-07-26): TRUE exactly for
     * GET/HEAD requests to the inert public shell — "/", "/dashboard", or
     * "/dashboard/**". Every other method and every other path — notably every
     * /api/* and /internal/* route — remains token-guarded. The exemption's own
     * invariant: no DATA route is ever unauthenticated; the shell is inert public
     * bytes (the same trust class as a downloaded app binary); removing the single
     * early-return in authorize() restores the unconditional guard (one-line-
     * reversible). Runs strictly AFTER isPathSafe — the traversal gate still
     * precedes every auth decision.
     *
     * @param method the HTTP method name (Javalin 6's {@code ctx.method()} is an
     *               enum — callers pass {@code .name()}; string-typed here for
     *               unit-testability)
     * @param path   the decoded request path; {@code null} is rejected (the
     *               {@code isPathSafe} null posture)
     * @return {@code true} exactly when the request is for the public shell
     */
    static boolean isPublicShellRequest(String method, String path) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        if (path == null) {
            return false;
        }
        return "/".equals(path) || "/dashboard".equals(path) || path.startsWith("/dashboard/");
    }

    /**
     * The R-9 loopback readiness-probe exemption (R-H1 LOOPBACK-ONLY, ruled
     * 2026-08-22): TRUE exactly for GET/HEAD on EXACTLY {@code /health} (no prefix,
     * no trailing slash — {@code /health/}, {@code /healthz}, {@code /api/health}
     * stay guarded) from a LOOPBACK address literal ({@link #isLoopbackLiteral}).
     * Off loopback — the future LAN opt-in — {@code /health} needs a token like any
     * route: a LAN monitoring tool authenticates like any client. Null anything →
     * false. Runs strictly AFTER {@code isPathSafe}, like the shell exemption.
     *
     * <p>Disclosed residue (the DASH-SERVE form): an exempted request bypasses
     * rate-limiting (no key); the surface is loopback-bound by default; a reverse
     * proxy or tunnel on the same host (the bench's {@code cloudflared} →
     * {@code 127.0.0.1:7070}) presents AS loopback, so {@code /health} is reachable
     * through it unauthenticated — the disclosure is one enum word, and the shell is
     * already reachable the same way.</p>
     *
     * @param method        the HTTP method name ({@code ctx.method().name()} — the
     *                      Javalin-6 {@code HandlerType} enum's exact names)
     * @param path          the decoded request path
     * @param remoteAddress the peer address as Jetty reports it — Javalin's
     *                      {@code ctx.ip()}, i.e. the default context resolver's
     *                      {@code HttpServletRequest.getRemoteAddr()}: always a
     *                      literal, an IPv6 one possibly bracketed
     * @return {@code true} exactly when the request is the loopback readiness probe
     */
    static boolean isHealthProbeRequest(String method, String path, String remoteAddress) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        if (!HealthEndpoint.PATH.equals(path)) {
            return false;
        }
        return isLoopbackLiteral(remoteAddress);
    }

    /**
     * {@code true} exactly when {@code address} is an IP address LITERAL of the
     * loopback range — {@code 127/8} as a strict dotted quad, or an IPv6 literal
     * that is loopback ({@code ::1}, {@code 0:0:0:0:0:0:0:1}, the IPv4-mapped
     * {@code ::ffff:127.0.0.1}), optionally bracketed ({@code [::1]} — Jetty's
     * {@code getRemoteAddr()} form for IPv6) and/or carrying a {@code %zone}.
     *
     * <p>A hostname is NEVER handed to {@link InetAddress}: a resolver call from the
     * auth filter would be a DNS-triggered stall on every request. The IPv4 arm is
     * parsed here (exactly four decimal octets, no leading zeros, first octet 127)
     * without touching {@code InetAddress} at all. The IPv6 arm admits only the
     * literal charset ({@code [0-9A-Fa-f:.]}, first char a hex digit or {@code :}):
     * for such a colon-bearing string JDK 21's {@code InetAddress.getAllByName}
     * either parses a literal or throws {@link UnknownHostException} — it never
     * consults a resolver (the literal branch at {@code InetAddress.java:1635–:1663}).
     * Null, blank, or empty after stripping → false (the JDK maps an EMPTY host to
     * loopback — it must never be passed). Nothing here throws.</p>
     *
     * @param address the peer address literal, may be {@code null}
     * @return {@code true} for a loopback literal; {@code false} for anything else
     */
    static boolean isLoopbackLiteral(String address) {
        if (address == null) {
            return false;
        }
        String literal = address;
        int end = literal.length() - 1;
        if (end >= 1 && literal.charAt(0) == '[' && literal.charAt(end) == ']') {
            literal = literal.substring(1, end);
        }
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.isEmpty()) {
            return false;
        }
        if (literal.indexOf(':') < 0) {
            return isLoopbackDottedQuad(literal);
        }
        char first = literal.charAt(0);
        if (!isAsciiHexDigit(first) && first != ':') {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (!isAsciiHexDigit(c) && c != ':' && c != '.') {
                return false;
            }
        }
        try {
            return InetAddress.getByName(literal).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Strict dotted quad: exactly four decimal octets 0–255, no leading zeros, first octet 127. */
    private static boolean isLoopbackDottedQuad(String literal) {
        String[] octets = literal.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3
                    || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return false;
            }
            int value = 0;
            for (int j = 0; j < octet.length(); j++) {
                char c = octet.charAt(j);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255 || (i == 0 && value != 127)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Canonicalization gate: rejects path traversal and control characters before
     * the auth decision. {@code ctx.path()} is already URL-decoded by Javalin/Jetty,
     * so a {@code ..} segment surfaces here whether sent raw or single-encoded; the
     * residual {@code %2e}/{@code %2f}/{@code %5c} checks defend against ambiguous
     * double-decoding (defense-in-depth on top of Jetty's own normalization).
     *
     * @param path the decoded request path
     * @return {@code true} if the path is safe to resolve
     */
    static boolean isPathSafe(String path) {
        if (path == null) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c == '\\') {
                return false;   // control chars (incl. NUL), raw whitespace, backslash
            }
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return !(lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c"));
    }

    /**
     * Builds an {@link ApiException} for the given problem type (used by the auth
     * filter and {@link StandardAuthMiddleware}). The {@code correlation_id} is a
     * placeholder here; {@link #writeProblem} replaces it with the request's
     * {@code X-Correlation-ID} when one is present.
     *
     * @param type   the problem type (drives status, title, type URI)
     * @param detail the Register-C, human-readable detail
     * @return the structured exception; never {@code null}
     */
    static ApiException problem(ProblemType type, String detail) {
        return new ApiException(new ProblemDetail(
                type, type.title(), type.defaultStatus(), detail,
                null, UUID.randomUUID().toString(), null));
    }

    /** Serializes an {@link ApiException} as an RFC 9457 {@code application/problem+json} response. */
    private static void writeProblem(ApiException exception, Context ctx) {
        ProblemDetail detail = exception.problemDetail();
        String correlationId = resolveCorrelationId(ctx, detail);
        ctx.status(detail.status());
        if (detail.type() == ProblemType.AUTHENTICATION_REQUIRED) {
            ctx.header("WWW-Authenticate", "Bearer");
        }
        ctx.header("X-Correlation-ID", correlationId);
        ctx.json(problemBody(detail, correlationId, ctx.path()));
        // Override the application/json content type set by ctx.json(...).
        ctx.contentType("application/problem+json");
    }

    private static String resolveCorrelationId(Context ctx, ProblemDetail detail) {
        String header = ctx.header("X-Correlation-ID");
        return (header != null && !header.isBlank()) ? header : detail.correlationId();
    }

    private static Map<String, Object> problemBody(
            ProblemDetail detail, String correlationId, String instance) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>(6);
        body.put("type", detail.type().typeUri());
        body.put("title", detail.title());
        body.put("status", detail.status());
        body.put("detail", detail.detail());
        body.put("instance", instance);
        body.put("correlation_id", correlationId);
        return body;
    }
}
