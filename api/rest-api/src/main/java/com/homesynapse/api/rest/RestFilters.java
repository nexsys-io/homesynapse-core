/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.automation.ExplanationService;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.state.ReadinessSource;
import com.homesynapse.state.StateQueryService;

import io.javalin.Javalin;
import io.javalin.http.Context;

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

    /** Request attribute key carrying the authenticated identity to downstream handlers. */
    static final String IDENTITY_ATTRIBUTE = "hs.api.identity";

    /**
     * Installs the catch-all authentication + rate-limiting filter (the C1 close,
     * AB-1). The filter runs as a Javalin {@code before(*)} handler — before
     * <em>any</em> route resolves, covering {@code /api/*}, {@code /internal/*},
     * and every other path (INV-SE-02; the {@code /internal/*} admin routes sit
     * outside the readiness gate but MUST still be authenticated). It is registered
     * <em>before</em> {@link #installReadinessGate(Object, ReadinessSource)} so it
     * precedes the {@code /api/*} readiness gate.
     *
     * <p>Per request, in order:</p>
     * <ol>
     *   <li><strong>Canonicalize the path</strong> and reject {@code ..} /
     *       encoded-traversal / control sequences <em>before</em> the auth decision
     *       (R-δ AX-1 / CVE-2023-27482) → 400.</li>
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
