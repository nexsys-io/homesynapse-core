/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.automation.ExplanationService;
import com.homesynapse.automation.RunPage;
import com.homesynapse.automation.RunStatus;
import com.homesynapse.automation.RunSummary;
import com.homesynapse.platform.identity.AutomationId;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@code GET /api/v1/runs} — the "why did this fire?" terminal-run entry list, serialized to
 * the frozen v1.1 dashboard contract.
 *
 * <p>Mirrors {@link ListEntitiesEndpoint}: implements {@link Handler}, delegates
 * {@code handle(Context)} to {@link #apply(EndpointContext)} for testability, and hand-builds
 * a {@link LinkedHashMap} body so the wire keys are exactly the frozen <em>camelCase</em>
 * names (a {@code Map} bypasses any Jackson property-naming strategy) and the internal
 * {@link RunStatus} never leaks — it is mapped to the public status vocabulary here (DP-A1).</p>
 *
 * <p>Query params: {@code automationId} (optional ULID; malformed → 400), {@code limit}
 * (default 50, clamped to {@code [1,100]}, malformed → default), {@code cursor}/{@code since}
 * (opaque, Base64; malformed → 400). The projection returns runs newest-first; the
 * {@code sort} param is accepted but not yet honored (newest-first only — see the run-query
 * design note). Empty {@code data} (not 404) when no runs match.</p>
 */
final class ListRunsEndpoint implements Handler {

    static final int DEFAULT_LIMIT = 50;

    static final int MAX_LIMIT = 100;

    private final ExplanationService explanationService;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    ListRunsEndpoint(ExplanationService explanationService,
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
        Optional<AutomationId> automationId;
        try {
            automationId = parseAutomationId(ctx.queryParam("automationId"));
        } catch (IllegalArgumentException ex) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Query parameter 'automationId' is not a valid ULID");
            return;
        }

        long beforePosition;
        try {
            String cursor = ctx.queryParam("cursor");
            if (cursor == null || cursor.isBlank()) {
                cursor = ctx.queryParam("since");
            }
            beforePosition = decodeCursor(cursor);
        } catch (IllegalArgumentException ex) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Query parameter 'cursor' is not a valid pagination cursor");
            return;
        }

        int limit = parseLimit(ctx.queryParam("limit"));

        RunPage page = explanationService.listRuns(automationId, beforePosition, limit);

        List<Map<String, Object>> data = new ArrayList<>(page.runs().size());
        for (RunSummary run : page.runs()) {
            data.add(toWire(run));
        }

        Map<String, Object> pagination = new LinkedHashMap<>(3);
        pagination.put("nextCursor", page.hasMore() ? encodeCursor(page.nextCursorPosition()) : null);
        pagination.put("hasMore", page.hasMore());
        pagination.put("limit", limit);

        long viewPosition = viewPositionSupplier.getAsLong();
        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", viewPosition);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(3);
        body.put("data", data);
        body.put("pagination", pagination);
        body.put("meta", meta);

        ctx.status(200);
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER, Long.toString(viewPosition));
        // The run list is a State-Query-plane view; a weak ETag keyed on the projection
        // position lets it change as new runs land (no ETagProvider impl exists; format inline).
        ctx.header("ETag", "W/\"" + viewPosition + "\"");
        ctx.json(body);
    }

    private static Map<String, Object> toWire(RunSummary run) {
        Map<String, Object> map = new LinkedHashMap<>(6);
        map.put("runId", run.runId().toString());
        map.put("automationId", run.automationId().toString());
        map.put("automationName", run.automationName());
        map.put("triggeredAt", run.triggeredAt().toString());
        map.put("status", wireStatus(run.status()));
        map.put("terminalReason", run.terminalReason());
        return map;
    }

    /**
     * Maps the internal {@link RunStatus} to the frozen public wire vocabulary (DP-A1). The
     * switch is exhaustive with no {@code default}, so a future {@code RunStatus} value is a
     * compile error here rather than a silent miswire. {@code EVALUATING}/{@code RUNNING} are
     * non-terminal and never reach this boundary (the projection lists/explains terminal runs
     * only); they throw defensively.
     *
     * @param status the internal terminal status; never {@code null}
     * @return the public wire status token
     */
    static String wireStatus(RunStatus status) {
        return switch (status) {
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case INTERRUPTED -> "INTERRUPTED";
            case CONDITION_NOT_MET -> "SKIPPED";
            case ABORTED -> "CANCELLED";
            case EVALUATING, RUNNING -> throw new IllegalStateException(
                    "non-terminal RunStatus at the wire boundary: " + status);
        };
    }

    private static Optional<AutomationId> parseAutomationId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(AutomationId.parse(raw.trim()));
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            return DEFAULT_LIMIT;
        }
        if (parsed < 1) {
            return 1;
        }
        return Math.min(parsed, MAX_LIMIT);
    }

    /** Encodes a global-log position as an opaque URL-safe Base64 cursor. */
    private static String encodeCursor(long position) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("p:" + position).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes an opaque cursor to its global-log position (the exclusive upper bound for the
     * next page). A blank/absent cursor means "newest page" (0). Any malformed cursor throws
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
