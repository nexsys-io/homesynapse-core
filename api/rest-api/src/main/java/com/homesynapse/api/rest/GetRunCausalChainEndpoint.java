/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.automation.ExplanationService;
import com.homesynapse.automation.RunExplanation;
import com.homesynapse.automation.RunId;
import com.homesynapse.platform.identity.Ulid;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@code GET /api/v1/runs/{runId}/causal-chain} — the hero causal-chain tree for one terminal
 * run, serialized to the frozen v1.1 dashboard contract.
 *
 * <p>Mirrors {@link GetEntityEndpoint}: implements {@link Handler}, delegates
 * {@code handle(Context)} to {@link #apply(EndpointContext)} for testability, parses the path
 * {@code runId} (malformed → 400), and returns 404 when the projection finds no terminal run.
 * The full nested response is hand-built as {@link LinkedHashMap}/{@link List} with explicit
 * <em>camelCase</em> keys so the frozen wire shape is exact and the internal
 * {@code RunStatus}/{@code ActionOutcome} enums map cleanly (DP-A1 status mapping via
 * {@link ListRunsEndpoint#wireStatus}; the {@code ActionOutcome} name is the wire token).</p>
 *
 * <p>The action {@code params} object is parsed from the raw {@code command_issued.parameters}
 * JSON string carried on {@link RunExplanation.ActionView#paramsJson()} — the automation
 * module owns no JSON library, so this rest-api boundary (the JSON layer, LTD-08) does the
 * parse, degrading to an empty object on any malformation.</p>
 *
 * <p>v1.1.2 amendment (additive-only, Nick ruling 1): each action map additionally carries
 * {@code resultOutcome} (the raw {@code command_result.outcome} string, or null) and
 * {@code settled} (the derived Q1b settledness flag).</p>
 *
 * <p>v1.1.4 amendment (additive-only, EXPLAIN-114a): each action map additionally carries
 * {@code settledAt} and {@code confirmedAt} (the classifying / confirming envelope's instant,
 * rendered {@code Instant.toString()} — ISO-8601 UTC, the same rendering as {@code matchedAt}
 * — or JSON null), appended after {@code settled}; the {@code data} object additionally carries
 * {@code definitionKey} (the run's stamped definition hash, or JSON null), appended after
 * {@code cascade}. {@code trigger.firingValue} keeps its key and position; since v1.1.4 the
 * projection populates it when the log carries the value.</p>
 *
 * <p>v1.1.5 amendment (additive-only, EXPLAIN-114c): each condition map additionally carries
 * {@code definition}, appended after {@code observedState} — the condition as structured data
 * from the registry definition the run ran under, a nested object in the order {@code type},
 * {@code selector}, {@code attribute}, {@code value}, {@code above}, {@code below},
 * {@code after}, {@code before}, {@code children} (recursive, {@code []} for a leaf) — or JSON
 * null when the projection has nothing it can vouch for (the definition changed, the automation
 * is gone, or the index is out of range). PRESENT in every v1.1.5 payload.</p>
 */
final class GetRunCausalChainEndpoint implements Handler {

    /** Parses the durable command-parameter JSON strings into wire objects. Thread-safe. */
    private static final ObjectMapper PARAMS_MAPPER = new ObjectMapper();

    private final ExplanationService explanationService;
    private final LongSupplier viewPositionSupplier;
    private final Clock clock;

    GetRunCausalChainEndpoint(ExplanationService explanationService,
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
        String raw = ctx.pathParam("runId");
        RunId runId;
        try {
            runId = new RunId(Ulid.parse(raw));
        } catch (IllegalArgumentException ex) {
            EndpointResponses.problem(ctx, ProblemType.INVALID_PARAMETERS,
                    "Path parameter 'runId' is not a valid ULID: " + raw);
            return;
        }

        Optional<RunExplanation> maybe = explanationService.explainRun(runId);
        if (maybe.isEmpty()) {
            EndpointResponses.problem(ctx, ProblemType.NOT_FOUND, "Run not found: " + raw);
            return;
        }
        RunExplanation explanation = maybe.get();

        long viewPosition = viewPositionSupplier.getAsLong();
        Map<String, Object> meta = new LinkedHashMap<>(2);
        meta.put("viewPosition", viewPosition);
        meta.put("timestamp", clock.instant().toString());

        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("data", toWire(explanation));
        body.put("meta", meta);

        ctx.status(200);
        ctx.header(ListEntitiesEndpoint.VIEW_POSITION_HEADER, Long.toString(viewPosition));
        // The causal chain of a terminal run is immutable (INV-ES-01), so a strong ETag keyed
        // on the immutable runId is appropriate (no ETagProvider impl exists; format inline).
        ctx.header("ETag", "\"" + explanation.runId() + "\"");
        ctx.json(body);
    }

    private static Map<String, Object> toWire(RunExplanation e) {
        Map<String, Object> data = new LinkedHashMap<>(9);
        data.put("runId", e.runId().toString());
        data.put("automationId", e.automationId().toString());
        data.put("automationName", e.automationName());
        data.put("trigger", triggerMap(e.trigger()));
        data.put("conditions", conditionsList(e.conditions()));
        data.put("actions", actionsList(e.actions()));
        data.put("outcome", outcomeMap(e.outcome()));
        data.put("cascade", cascadeMap(e.cascade()));
        // v1.1.4 (EXPLAIN-114a): appended LAST — the LinkedHashMap order is the wire order.
        data.put("definitionKey", e.definitionKey());
        return data;
    }

    private static Map<String, Object> triggerMap(RunExplanation.TriggerView t) {
        Map<String, Object> map = new LinkedHashMap<>(4);
        map.put("type", t.type());
        // The {type, id} rendering is shared with the v1.1.3 non-firing/list reads (CG-1);
        // hoisted to EndpointResponses.subjectRefMap, wire byte-identical.
        map.put("subjectRef", EndpointResponses.subjectRefMap(t.subjectRef()));
        map.put("matchedAt", t.matchedAt() == null ? null : t.matchedAt().toString());
        map.put("firingValue", t.firingValue());
        return map;
    }

    private static List<Map<String, Object>> conditionsList(
            List<RunExplanation.ConditionView> conditions) {
        List<Map<String, Object>> list = new ArrayList<>(conditions.size());
        for (RunExplanation.ConditionView c : conditions) {
            Map<String, Object> map = new LinkedHashMap<>(5);
            map.put("expression", c.expression());
            map.put("evaluated", c.evaluated());
            map.put("result", c.result());
            List<Map<String, Object>> observed = new ArrayList<>(c.observedState().size());
            for (RunExplanation.ObservedStateEntry o : c.observedState()) {
                Map<String, Object> entry = new LinkedHashMap<>(3);
                entry.put("entityId", o.entityId());
                entry.put("attribute", o.attribute());
                entry.put("value", o.value());
                observed.add(entry);
            }
            map.put("observedState", observed);
            // v1.1.5 (EXPLAIN-114c): appended LAST — the LinkedHashMap order is the wire order.
            map.put("definition", definitionMap(c.definition()));
            list.add(map);
        }
        return list;
    }

    /**
     * The v1.1.5 structured condition definition, nested recursively in the frozen key order
     * (SD-2), or JSON null when the projection vouches for none. {@code children} is always a
     * list — {@code []} for a leaf — so a consumer's tri-state read (absent / null / value)
     * sees a value.
     */
    private static Map<String, Object> definitionMap(RunExplanation.ConditionDefinitionView d) {
        if (d == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>(9);
        map.put("type", d.type());
        map.put("selector", d.selector());
        map.put("attribute", d.attribute());
        map.put("value", d.value());
        map.put("above", d.above());
        map.put("below", d.below());
        map.put("after", d.after());
        map.put("before", d.before());
        List<Map<String, Object>> children = new ArrayList<>(d.children().size());
        for (RunExplanation.ConditionDefinitionView child : d.children()) {
            children.add(definitionMap(child));
        }
        map.put("children", children);
        return map;
    }

    private static List<Map<String, Object>> actionsList(List<RunExplanation.ActionView> actions) {
        List<Map<String, Object>> list = new ArrayList<>(actions.size());
        for (RunExplanation.ActionView a : actions) {
            Map<String, Object> map = new LinkedHashMap<>(10);
            map.put("type", a.type());
            map.put("targetRef", EndpointResponses.subjectRefMap(a.targetRef()));
            map.put("command", a.command());
            map.put("params", parseParams(a.paramsJson()));
            map.put("outcome", a.outcome().name());
            map.put("reason", a.reason());
            map.put("resultOutcome", a.resultOutcome());
            map.put("settled", a.settled());
            // v1.1.4 (EXPLAIN-114a): appended LAST, in this order; Instant.toString() or null.
            map.put("settledAt", a.settledAt() == null ? null : a.settledAt().toString());
            map.put("confirmedAt", a.confirmedAt() == null ? null : a.confirmedAt().toString());
            list.add(map);
        }
        return list;
    }

    private static Map<String, Object> outcomeMap(RunExplanation.OutcomeView o) {
        Map<String, Object> map = new LinkedHashMap<>(5);
        map.put("status", ListRunsEndpoint.wireStatus(o.status()));
        map.put("reason", o.reason());
        map.put("durationMs", o.durationMs());
        map.put("actionCount", o.actionCount());
        map.put("commandCount", o.commandCount());
        return map;
    }

    private static Map<String, Object> cascadeMap(RunExplanation.CascadeView c) {
        Map<String, Object> map = new LinkedHashMap<>(2);
        map.put("parentRunId", c.parentRunId() == null ? null : c.parentRunId().toString());
        map.put("depth", c.depth());
        return map;
    }

    /**
     * Parses a raw command-parameter JSON-object string into a wire object. A blank string or
     * any malformation degrades to an empty object (the params are durably on
     * {@code command_issued}; a read must never fail on a single bad parameter blob).
     */
    private static Object parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = PARAMS_MAPPER.readValue(paramsJson, Object.class);
            return parsed instanceof Map ? parsed : new LinkedHashMap<>();
        } catch (IOException ex) {
            return new LinkedHashMap<>();
        }
    }
}
