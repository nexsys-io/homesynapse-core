/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.state.ReadinessSource;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin {@code before} handler that rejects requests with
 * {@code 503 Service Unavailable} until the State Projection reaches
 * {@link SubscriberMode#LIVE}.
 *
 * <p>Registered on the {@code /api/*} path during composition root startup
 * (M3.6e.1). On every matching request, the filter reads the projection's
 * current lifecycle mode from the injected {@link ReadinessSource}. If the
 * mode is anything other than {@code LIVE} — {@code COLD}, {@code REPLAY},
 * {@code TRANSITION}, or {@code SUSPENDED} — the filter writes an RFC 9457
 * problem detail response, sets two diagnostic headers, and returns without
 * advancing the request chain (Javalin treats a status-set {@code before}
 * handler as the terminal response).</p>
 *
 * <h2>Response shape</h2>
 *
 * <p>When rejecting:</p>
 * <ul>
 *   <li>Status: {@code 503 Service Unavailable}</li>
 *   <li>Header: {@code X-HomeSynapse-Projection-State: <MODE>} (e.g.,
 *       {@code REPLAY}) — machine-readable diagnostic for orchestrators and
 *       monitoring tools.</li>
 *   <li>Header: {@code Retry-After: 5} (seconds) — fixed advisory value per
 *       PLAN-M3 §10. Not computed from projection progress.</li>
 *   <li>JSON body: RFC 9457 problem detail
 *       ({@link ProblemType#STATE_STORE_REPLAYING}) with {@code type},
 *       {@code status}, {@code title}, and {@code detail} fields.</li>
 * </ul>
 *
 * <h2>Testability</h2>
 *
 * <p>{@code io.javalin.http.Context} is a thick interface with
 * implementation-internal initialization, so the response side is factored
 * into the {@link Responder} SPI (package-private). The production adapter
 * is {@link ContextResponder} — tests use a recording stub. The pure
 * decision logic lives in {@link #apply(Responder)}; {@link #handle(Context)}
 * is a one-line wrapper.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless beyond the immutable {@link ReadinessSource} reference.
 * Javalin invokes handlers from request virtual threads; the
 * {@link ReadinessSource} is expected to be safe for concurrent invocation
 * (the composition root's implementation delegates to an
 * {@code AtomicReference}-backed mode FSM).</p>
 *
 * @see ReadinessSource
 * @see ProblemType#STATE_STORE_REPLAYING
 */
final class ReadinessFilter implements Handler {

    /** Fixed advisory retry hint in seconds (PLAN-M3 §10). */
    static final String RETRY_AFTER_SECONDS = "5";

    /** Diagnostic header carrying the projection's current lifecycle mode. */
    static final String PROJECTION_STATE_HEADER = "X-HomeSynapse-Projection-State";

    private static final Logger log = LoggerFactory.getLogger(ReadinessFilter.class);

    private final ReadinessSource readinessSource;

    /**
     * Constructs a new readiness gate.
     *
     * @param readinessSource the source of subscriber lifecycle mode; never
     *                        {@code null}. The composition root provides the
     *                        production implementation that delegates to
     *                        {@code StateProjection.currentMode()}.
     */
    public ReadinessFilter(ReadinessSource readinessSource) {
        this.readinessSource = Objects.requireNonNull(readinessSource, "readinessSource");
    }

    @Override
    public void handle(Context ctx) {
        apply(new ContextResponder(ctx));
    }

    /**
     * Evaluates the readiness gate against the given {@link Responder}.
     *
     * <p>Returns {@code true} when the request was rejected (status, headers,
     * and body were written); {@code false} when the projection is LIVE and
     * the request should continue down the chain.</p>
     *
     * <p>Package-private so unit tests can drive the decision logic with a
     * recording responder rather than a thick Javalin {@code Context} stub.</p>
     *
     * @param responder the response sink (production: a Javalin Context
     *                  adapter; tests: a recording stub); never {@code null}
     * @return {@code true} if the request was rejected, {@code false} if it
     *         should pass through
     */
    boolean apply(Responder responder) {
        SubscriberMode mode = readinessSource.mode();
        if (mode == SubscriberMode.LIVE) {
            return false;
        }
        log.debug("Readiness gate rejecting request: projection mode={}", mode);
        responder.status(503);
        responder.header(PROJECTION_STATE_HEADER, mode.name());
        responder.header("Retry-After", RETRY_AFTER_SECONDS);
        responder.json(problemDetail(mode));
        return true;
    }

    /**
     * Builds the RFC 9457 problem detail body for a non-LIVE mode response.
     * Uses {@link LinkedHashMap} to keep the field order stable in the
     * serialized JSON ({@code type}, {@code status}, {@code title},
     * {@code detail}) — improves operator readability when scanning logs.
     */
    private static Map<String, Object> problemDetail(SubscriberMode mode) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>(4);
        body.put("type", ProblemType.STATE_STORE_REPLAYING.typeUri());
        body.put("status", 503);
        body.put("title", ProblemType.STATE_STORE_REPLAYING.title());
        body.put("detail",
                "State projection is in " + mode.name()
                        + " mode; query results are not yet available.");
        return body;
    }

    /**
     * Narrow response SPI used by {@link ReadinessFilter#apply}. Mirrors the
     * subset of {@link Context} actually consumed by the filter
     * ({@code status}, {@code header}, {@code json}) so unit tests can drive
     * the filter without instantiating a Javalin {@code Context}.
     */
    interface Responder {
        /** Set the HTTP response status code. */
        void status(int status);

        /** Set an HTTP response header. */
        void header(String name, String value);

        /** Set the JSON response body (serialized by the underlying engine). */
        void json(Object body);
    }

    /**
     * Production {@link Responder} adapter over a Javalin {@link Context}.
     * Forwards each call to the equivalent Context method.
     */
    private static final class ContextResponder implements Responder {
        private final Context ctx;

        ContextResponder(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void status(int status) {
            ctx.status(status);
        }

        @Override
        public void header(String name, String value) {
            ctx.header(name, value);
        }

        @Override
        public void json(Object body) {
            ctx.json(body);
        }
    }
}
