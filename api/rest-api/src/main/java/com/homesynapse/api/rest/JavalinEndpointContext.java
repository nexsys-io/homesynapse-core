/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import io.javalin.http.Context;

/**
 * Production adapter from Javalin's {@link Context} to the package-private
 * {@link EndpointContext} SPI used by M3.6e.2 endpoint handlers.
 *
 * <p>Forwards each call to the equivalent {@link Context} method. The
 * adapter exists so handler classes can be unit-tested through a recording
 * stub without instantiating Javalin's thick {@code Context} interface.</p>
 *
 * <p>Package-private final — same visibility discipline as
 * {@link ReadinessFilter.ContextResponder} (Javalin types must not leak
 * into the rest-api module's exported public API because
 * {@code requires io.javalin} is non-transitive).</p>
 */
final class JavalinEndpointContext implements EndpointContext {

    private final Context ctx;

    /**
     * Constructs a new adapter over the given Javalin context.
     *
     * @param ctx the Javalin request context; never {@code null}
     */
    JavalinEndpointContext(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public String pathParam(String name) {
        return ctx.pathParam(name);
    }

    @Override
    public String queryParam(String name) {
        return ctx.queryParam(name);
    }

    @Override
    public String body() {
        return ctx.body();
    }

    @Override
    public String requestHeader(String name) {
        return ctx.header(name);
    }

    @Override
    public void status(int code) {
        ctx.status(code);
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
