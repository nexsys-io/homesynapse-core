/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.state.ReadinessSource;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin handler for {@code GET}/{@code HEAD /health} — the unauthenticated
 * loopback readiness bit (R-9 / E3-HEALTH, 2026-08-22).
 *
 * <p>{@code 200} exactly when the State Projection is {@link SubscriberMode#LIVE};
 * {@code 503} for every other mode ({@code COLD}, {@code REPLAY},
 * {@code TRANSITION}, {@code SUSPENDED}), carrying the readiness gate's own
 * diagnostic headers — {@code X-HomeSynapse-Projection-State: <MODE>} and
 * {@code Retry-After: 5} ({@link ReadinessFilter#PROJECTION_STATE_HEADER},
 * {@link ReadinessFilter#RETRY_AFTER_SECONDS}) — so the probe and the
 * {@code /api/*} gate speak ONE readiness vocabulary, read from the SAME
 * {@link ReadinessSource}. The semantics are byte-identical to what the packaged
 * probe measured before R-9 ({@code GET /api/v1/entities} answered 200 only once
 * the gate saw LIVE); only the credential is gone.</p>
 *
 * <h2>Body</h2>
 *
 * <p>Exactly {@code {"status":"<SubscriberMode.name()>"}} in BOTH arms — one key,
 * one enum word. No {@code {data, meta}} envelope: the envelope is the DATA-route
 * contract (every read carries {@code meta.viewPosition}); a probe body is not a
 * data route, and a loopback peer can already infer the same word from the 503
 * the gate returns on any {@code /api/*} request (INV-SE-02 holds in substance —
 * no data route is ever unauthenticated). No version, no home id, no counts: a
 * readiness BIT. {@code Cache-Control: no-store} on every response.</p>
 *
 * <h2>Auth posture</h2>
 *
 * <p>The exemption lives in the auth filter, not here:
 * {@code RestFilters.isHealthProbeRequest} admits {@code GET}/{@code HEAD} on
 * exactly {@code /health} from a LOOPBACK literal (R-H1 LOOPBACK-ONLY); an
 * off-loopback caller authenticates like any client. The route is registered
 * AFTER {@code installAuth} and sits outside the {@code /api/*} readiness gate by
 * path — it evaluates the gate's predicate itself. Both {@code GET} and
 * {@code HEAD} are registered explicitly: Javalin 6's automatic HEAD-for-GET
 * answers 200 WITHOUT running the handler, which would read "ready" during
 * REPLAY.</p>
 *
 * <p>No identity is read and nothing is logged above DEBUG — the unit's probe
 * polls every 2 s (LTD-15: an INFO per poll is journal spam).</p>
 *
 * <p>Thread safety: stateless beyond the immutable {@link ReadinessSource}
 * reference (the composition root's {@code AtomicReference}-backed mode FSM).</p>
 *
 * @see RestFilters#installHealthEndpoint(Object, ReadinessSource)
 * @see ReadinessFilter
 */
final class HealthEndpoint implements Handler {

    /** The route — exact: {@code /health/}, {@code /healthz}, {@code /api/health} are NOT exempt. */
    static final String PATH = "/health";

    private static final Logger log = LoggerFactory.getLogger(HealthEndpoint.class);

    private final ReadinessSource readinessSource;

    /**
     * Constructs the readiness bit.
     *
     * @param readinessSource the source of the State Projection's lifecycle mode —
     *                        the SAME instance the readiness gate reads; never
     *                        {@code null}
     */
    HealthEndpoint(ReadinessSource readinessSource) {
        this.readinessSource = Objects.requireNonNull(readinessSource, "readinessSource");
    }

    @Override
    public void handle(Context ctx) {
        apply(new JavalinEndpointContext(ctx));
    }

    /**
     * Pure handler logic — package-private so the unit test drives it through a
     * recording {@link EndpointContext}. The mode is read on every call, never
     * cached.
     *
     * @param ctx the response sink; never {@code null}
     */
    void apply(EndpointContext ctx) {
        SubscriberMode mode = readinessSource.mode();
        if (mode == SubscriberMode.LIVE) {
            ctx.status(200);
        } else {
            log.debug("/health not ready: projection mode={}", mode);
            ctx.status(503);
            ctx.header(ReadinessFilter.PROJECTION_STATE_HEADER, mode.name());
            ctx.header("Retry-After", ReadinessFilter.RETRY_AFTER_SECONDS);
        }
        ctx.header("Cache-Control", "no-store");
        ctx.json(Map.of("status", mode.name()));
    }
}
