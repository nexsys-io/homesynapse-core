/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.state.ReadinessSource;

import io.javalin.Javalin;

/**
 * Public gateway for installing REST-layer request filters onto the
 * embedded HTTP server.
 *
 * <p>The filter implementations ({@link ReadinessFilter}, and future
 * additions like rate-limiting or auth middleware) are package-private
 * per DEC-M3-16 — their Javalin-specific signatures must not appear in
 * the rest-api module's exported public API because {@code io.javalin}
 * is not re-exported ({@code requires} without {@code transitive}).
 * This class provides the public wiring surface: each method accepts
 * the Javalin application instance and the HomeSynapse dependencies
 * needed by the filter, constructs the package-private implementation,
 * and registers it on the correct path.</p>
 *
 * <p>The {@code javalinApp} parameter is typed as {@link Object} to
 * avoid exposing {@code io.javalin.Javalin} in the method signature.
 * Callers (i.e., the composition root in the lifecycle module) pass
 * their {@code Javalin} instance; this method casts internally.
 * The lifecycle module already declares {@code requires io.javalin}
 * independently, so the cast is always safe at the call site.</p>
 *
 * <p>Thread safety: stateless utility class. The filter instances it
 * creates are documented as thread-safe in their own Javadoc.</p>
 *
 * @see ReadinessFilter
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
     * {@code 503 Service Unavailable} until the State Projection
     * reaches {@code SubscriberMode.LIVE}. See {@link ReadinessFilter}
     * for the full response shape and threading model.</p>
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
        Javalin app = (Javalin) javalinApp;
        app.before("/api/*", new ReadinessFilter(readinessSource));
    }
}
