/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.automation.RunExplanation;

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
 * <p>Since v1.1.3 (CG-1) it also carries the ONE shared rendering of a subject
 * reference ({@link #subjectRefMap}) so the causal chain, the non-firing read and
 * the automation list cannot drift apart on the {@code {type, id}} shape.</p>
 *
 * <p>Package-private utility class — not part of the rest-api module's
 * exported API.</p>
 */
final class EndpointResponses {

    /** The RFC 9457 media type every non-2xx body carries (Doc 09 §3.8). */
    static final String PROBLEM_JSON = "application/problem+json";

    private EndpointResponses() {
        // utility class
    }

    /**
     * Writes an RFC 9457 problem detail response onto the given context.
     * Sets the status, the JSON body, and the {@code Content-Type:
     * application/problem+json} header (R-C / F-V2, 2026-08-22 — the media type
     * is the contract's discriminator, Doc 09 §3.8; before this the endpoint-level
     * problems reached the wire as {@code application/json} while only the
     * exception path sent {@code problem+json}). Callers may add further headers
     * afterwards.
     *
     * <p>The header is set AFTER {@link EndpointContext#json(Object)} on purpose:
     * Javalin's {@code Context.json(...)} sets {@code application/json} itself
     * (6.7.0 bytecode: {@code json → contentType(APPLICATION_JSON) → result}),
     * so a header written before it would be overwritten. Jetty routes a
     * {@code setHeader("Content-Type", …)} to {@code setContentType}, which is
     * why a plain response header carries the override — the same order the
     * exception path uses in {@code RestFilters.writeProblem}.</p>
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
        ctx.header("Content-Type", PROBLEM_JSON);
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

    /**
     * The frozen {@code {type, id}} wire map of a {@link RunExplanation.SubjectRefView} — ONE
     * rendering shared by every read that serves a subject reference: the causal chain's
     * {@code trigger.subjectRef} / {@code actions[].targetRef} (M7.5a) and, since v1.1.3
     * (CG-1), {@code nonFiring.triggerRef} and {@code automations[].components[].ref}. Hoisted
     * verbatim from {@code GetRunCausalChainEndpoint} so the three reads cannot drift.
     *
     * @param ref the view, or {@code null}
     * @return the ordered {@code {type, id}} map, or {@code null} when {@code ref} is
     *         {@code null} (JSON null on the wire — the key stays present)
     */
    static Map<String, Object> subjectRefMap(RunExplanation.SubjectRefView ref) {
        if (ref == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>(2);
        map.put("type", ref.type());
        map.put("id", ref.id());
        return map;
    }
}
