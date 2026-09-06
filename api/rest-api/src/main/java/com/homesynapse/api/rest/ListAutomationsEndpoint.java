/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.automation.AutomationSummary;
import com.homesynapse.automation.ExplanationService;

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
import java.util.function.LongSupplier;

/**
 * {@code GET /api/v1/automations} — the automation list (component-based summaries), serialized to
 * the frozen v1.1 dashboard contract (§B3).
 *
 * <p>Mirrors {@link ListRunsEndpoint}: implements {@link Handler}, delegates {@code handle(Context)}
 * to {@link #apply(EndpointContext)} for testability, and hand-builds a {@link LinkedHashMap} body
 * with explicit <em>camelCase</em> keys. The projection reads the in-memory
 * {@link com.homesynapse.automation.AutomationRegistry} (DP-B3); since that registry is not
 * log-positioned, this endpoint paginates by an in-memory <em>offset</em> over the registry order
 * (the {@code o:<offset>} cursor here, distinct from the run endpoints' {@code p:<position>}
 * cursor). The dataset is small (a home has tens of automations). Empty {@code data} (not 404) when
 * none are loaded.</p>
 *
 * <p>v1.1.3 amendment (additive-only, docket Row 14 / CG-1): each {@code components[]} map
 * additionally carries {@code ref} — the component's single-entity reference as the same
 * {@code {type:"entity", id}} map the causal chain serves ({@link EndpointResponses#subjectRefMap}),
 * or JSON null when the component names no single entity; appended after {@code summary},
 * always present.</p>
 */
final class ListAutomationsEndpoint implements Handler {

    static final int DEFAULT_LIMIT = 50;

    static final int MAX_LIMIT = 100;

    private final ExplanationService explanationService;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    ListAutomationsEndpoint(ExplanationService explanationService,
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
        int offset;
        try {
            offset = decodeCursor(ctx.queryParam("cursor"));
        } catch (IllegalArgumentException ex) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Query parameter 'cursor' is not a valid pagination cursor");
            return;
        }
        int limit = parseLimit(ctx.queryParam("limit"));

        List<AutomationSummary> all = explanationService.listAutomations();
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        boolean hasMore = to < all.size();

        List<Map<String, Object>> data = new ArrayList<>(to - from);
        for (AutomationSummary summary : all.subList(from, to)) {
            data.add(toWire(summary));
        }

        Map<String, Object> pagination = new LinkedHashMap<>(3);
        pagination.put("nextCursor", hasMore ? encodeCursor(to) : null);
        pagination.put("hasMore", hasMore);
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
        // The automation list changes as definitions load/reload and runs land; a weak ETag keyed
        // on the projection position is appropriate (no ETagProvider impl exists; format inline).
        ctx.header("ETag", "W/\"" + viewPosition + "\"");
        ctx.json(body);
    }

    private static Map<String, Object> toWire(AutomationSummary summary) {
        Map<String, Object> map = new LinkedHashMap<>(5);
        map.put("automationId", summary.automationId().toString());
        map.put("name", summary.name());
        map.put("enabled", summary.enabled());
        List<Map<String, Object>> components = new ArrayList<>(summary.components().size());
        for (AutomationSummary.ComponentView component : summary.components()) {
            Map<String, Object> comp = new LinkedHashMap<>(3);
            comp.put("type", component.type());
            comp.put("summary", component.summary());
            // v1.1.3 (CG-1): appended LAST — the LinkedHashMap order is the wire order.
            comp.put("ref", EndpointResponses.subjectRefMap(component.ref()));
            components.add(comp);
        }
        map.put("components", components);
        map.put("lastRunId", summary.lastRunId() == null ? null : summary.lastRunId().toString());
        return map;
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

    /** Encodes an in-memory list offset as an opaque URL-safe Base64 cursor ({@code o:<offset>}). */
    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes an opaque cursor to its in-memory list offset. A blank/absent cursor means the first
     * page (0). Any malformed cursor throws {@link IllegalArgumentException} (mapped to 400).
     */
    private static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        byte[] decoded = Base64.getUrlDecoder().decode(cursor.trim());
        String token = new String(decoded, StandardCharsets.UTF_8);
        if (!token.startsWith("o:")) {
            throw new IllegalArgumentException("malformed cursor");
        }
        int offset = Integer.parseInt(token.substring(2));
        if (offset < 0) {
            throw new IllegalArgumentException("negative cursor offset");
        }
        return offset;
    }
}
