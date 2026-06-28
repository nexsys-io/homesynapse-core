/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.automation.ExplanationService;
import com.homesynapse.automation.NonFiringExplanation;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@code GET /api/v1/automations/{id}/non-firing} — the co-equal "why did this <em>not</em> fire?"
 * read for one automation, serialized to the frozen v1.1 dashboard contract (§B3).
 *
 * <p>Mirrors {@link GetRunCausalChainEndpoint}: implements {@link Handler}, delegates
 * {@code handle(Context)} to {@link #apply(EndpointContext)} for testability, parses the path
 * {@code id} (malformed → 400) and the optional {@code expectedSince} cursor (malformed → 400),
 * and returns 404 when the automation is unknown to the registry. The response is hand-built as a
 * {@link LinkedHashMap} with explicit <em>camelCase</em> keys so the frozen wire shape is exact and
 * the internal {@link NonFiringExplanation.NonFiringVerdict} maps cleanly via {@code name()} (the
 * enum name is the wire token).</p>
 *
 * <p>Unlike a terminal run's immutable causal chain, a non-firing verdict can change as new events
 * arrive, so this view carries a <strong>weak</strong> ETag keyed on the projection position.</p>
 */
final class GetNonFiringEndpoint implements Handler {

    private final ExplanationService explanationService;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    GetNonFiringEndpoint(ExplanationService explanationService,
                         LongSupplier viewPositionSupplier,
                         Clock clock) {
        this.explanationService = Objects.requireNonNull(explanationService, "explanationService");
        this.viewPositionSupplier =
                Objects.requireNonNull(viewPositionSupplier, "viewPositionSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void handle(Context ctx) {
        apply(new JavalinEndpointContext(ctx));
    }

    void apply(EndpointContext ctx) {
        String raw = ctx.pathParam("id");
        AutomationId automationId;
        try {
            automationId = AutomationId.of(Ulid.parse(raw));
        } catch (IllegalArgumentException ex) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Path parameter 'id' is not a valid ULID: " + raw);
            return;
        }

        long expectedSincePosition;
        try {
            expectedSincePosition = decodeCursor(ctx.queryParam("expectedSince"));
        } catch (IllegalArgumentException ex) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Query parameter 'expectedSince' is not a valid pagination cursor");
            return;
        }

        Optional<NonFiringExplanation> maybe =
                explanationService.explainNonFiring(automationId, expectedSincePosition);
        if (maybe.isEmpty()) {
            EndpointResponses.problem(ctx, ProblemType.NOT_FOUND, "Automation not found: " + raw);
            return;
        }
        NonFiringExplanation explanation = maybe.get();

        long viewPosition = viewPositionSupplier.getAsLong();
        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", viewPosition);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", toWire(explanation));
        body.put("meta", meta);

        ctx.status(200);
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER, Long.toString(viewPosition));
        // A non-firing verdict is a mutable State-Query-plane view (it changes as new events land),
        // so a weak ETag keyed on the projection position is appropriate (no ETagProvider impl
        // exists; format inline, mirroring ListRunsEndpoint).
        ctx.header("ETag", "W/\"" + viewPosition + "\"");
        ctx.json(body);
    }

    private static Map<String, Object> toWire(NonFiringExplanation e) {
        Map<String, Object> data = new LinkedHashMap<>(8);
        data.put("automationId", e.automationId().toString());
        data.put("automationName", e.automationName());
        data.put("enabled", e.enabled());
        data.put("verdict", e.verdict().name());
        data.put("lastRelevantRunId",
                e.lastRelevantRunId() == null ? null : e.lastRelevantRunId().toString());
        data.put("explanation", e.explanation());
        data.put("triggerSummary", e.triggerSummary());
        data.put("lastEvaluation", lastEvaluationMap(e.lastEvaluation()));
        return data;
    }

    private static Map<String, Object> lastEvaluationMap(NonFiringExplanation.LastEvaluationView v) {
        if (v == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>(2);
        map.put("at", v.at() == null ? null : v.at().toString());
        map.put("conditionsResult", v.conditionsResult());
        return map;
    }

    /**
     * Decodes the opaque {@code expectedSince} cursor to its inclusive lower-bound global position
     * (mirrors the {@link ListRunsEndpoint} {@code p:<position>} codec). A blank/absent cursor
     * means the default window (0 — the whole retained log). Any malformed cursor throws
     * {@link IllegalArgumentException} (mapped to 400 by the caller).
     */
    private static long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        byte[] decoded = Base64.getUrlDecoder().decode(cursor.trim());
        String token = new String(decoded, StandardCharsets.UTF_8);
        if (!token.startsWith("p:")) {
            throw new IllegalArgumentException("malformed cursor");
        }
        long position = Long.parseLong(token.substring(2));
        if (position < 0) {
            throw new IllegalArgumentException("negative cursor position");
        }
        return position;
    }
}
