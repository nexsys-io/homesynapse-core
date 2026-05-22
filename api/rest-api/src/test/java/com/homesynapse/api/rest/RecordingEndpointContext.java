/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Test recording implementation of {@link EndpointContext}.
 *
 * <p>Mirrors the {@code RecordingResponder} pattern from
 * {@link ReadinessFilterTest} but adds path-param / query-param sourcing
 * so the M3.6e.2 endpoint handler tests can drive both the request and
 * response sides of the SPI through a single recording stub.</p>
 *
 * <p>Request inputs are seeded via {@link #withPathParam} and
 * {@link #withQueryParam}; response writes ({@code status}, {@code header},
 * {@code json}) are captured for later assertion.</p>
 */
final class RecordingEndpointContext implements EndpointContext {

    private final Map<String, String> pathParams = new HashMap<>();
    private final Map<String, String> queryParams = new HashMap<>();

    Integer statusSet;
    final Map<String, String> headers = new LinkedHashMap<>();
    Object body;

    RecordingEndpointContext() {
    }

    /**
     * Seeds a path parameter value. Returns {@code this} for fluent setup.
     */
    RecordingEndpointContext withPathParam(String name, String value) {
        Objects.requireNonNull(name, "name");
        pathParams.put(name, value);
        return this;
    }

    /**
     * Seeds a query parameter value. Returns {@code this} for fluent setup.
     */
    RecordingEndpointContext withQueryParam(String name, String value) {
        Objects.requireNonNull(name, "name");
        queryParams.put(name, value);
        return this;
    }

    @Override
    public String pathParam(String name) {
        return pathParams.get(name);
    }

    @Override
    public String queryParam(String name) {
        return queryParams.get(name);
    }

    @Override
    public void status(int code) {
        this.statusSet = code;
    }

    @Override
    public void header(String name, String value) {
        this.headers.put(name, value);
    }

    @Override
    public void json(Object body) {
        this.body = body;
    }
}
