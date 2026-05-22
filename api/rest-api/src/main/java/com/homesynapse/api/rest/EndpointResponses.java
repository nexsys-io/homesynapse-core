/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared response helpers for the M3.6e.2 endpoint handlers.
 *
 * <p>Centralises RFC 9457 problem detail construction so {@link GetEntityEndpoint}
 * and {@link GetEntityStateEndpoint} (and future handlers) produce identical
 * error bodies. The shape mirrors {@link ReadinessFilter}'s problem detail —
 * stable field order ({@code type}, {@code status}, {@code title},
 * {@code detail}) via {@link LinkedHashMap} so log scans across endpoints
 * see consistent layout.</p>
 *
 * <p>Package-private utility class — not part of the rest-api module's
 * exported API.</p>
 */
final class EndpointResponses {

    private EndpointResponses() {
        // utility class
    }

    /**
     * Writes an RFC 9457 problem detail response onto the given context.
     * Sets the status, the JSON body, and no headers (callers may add
     * headers afterwards if needed).
     *
     * @param ctx     the response sink; never {@code null}
     * @param type    the problem type; supplies status, title, and type URI;
     *                never {@code null}
     * @param detail  human-readable description of what went wrong;
     *                never {@code null}
     */
    static void problem(EndpointContext ctx, ProblemType type, String detail) {
        ctx.status(type.defaultStatus());
        ctx.json(problemBody(type, detail));
    }

    /**
     * Builds the RFC 9457 problem detail body. Stable field order via
     * {@link LinkedHashMap}.
     *
     * @param type   the problem type; never {@code null}
     * @param detail the human-readable detail; never {@code null}
     * @return an ordered map representing the problem detail body
     */
    static Map<String, Object> problemBody(ProblemType type, String detail) {
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("type", type.typeUri());
        body.put("status", type.defaultStatus());
        body.put("title", type.title());
        body.put("detail", detail);
        return body;
    }
}
