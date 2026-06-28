/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.automation.AutomationSummary;
import com.homesynapse.automation.ExplanationService;
import com.homesynapse.automation.NonFiringExplanation;
import com.homesynapse.automation.RunExplanation;
import com.homesynapse.automation.RunId;
import com.homesynapse.automation.RunPage;
import com.homesynapse.automation.RunStatus;
import com.homesynapse.automation.RunSummary;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ListRunsEndpoint} and {@link GetRunCausalChainEndpoint}, driven through
 * a recording {@link EndpointContext} stub and a fake {@link ExplanationService}.
 *
 * <p>The headline is the <strong>v1.1 shape test</strong> — the cross-lane pin the Web-UI lane
 * depends on: the serialized {@code /runs} and {@code /runs/{id}/causal-chain} bodies must carry
 * exactly the frozen camelCase field names, nesting, status vocabulary, outcome enum, pagination,
 * meta, and cascade.</p>
 */
@DisplayName("Run query endpoints")
final class RunEndpointsTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final LongSupplier VIEW_POSITION = () -> 42L;

    private static final String RUN_ULID = "01H8000000000000000000000A";
    private static final String AUTO_ULID = "01H8000000000000000000000B";
    private static final String ENTITY_ULID = "01H8000000000000000000000C";

    RunEndpointsTest() {
    }

    // ---- /runs --------------------------------------------------------------

    @Test
    @DisplayName("GET /runs serializes exactly the frozen v1.1 list shape")
    void runs_v11ShapeTest() {
        RunSummary summary = new RunSummary(runId(), autoId(), "My Automation",
                FIXED_INSTANT, RunStatus.COMPLETED, null);
        ListRunsEndpoint endpoint = new ListRunsEndpoint(
                fake().withPage(new RunPage(List.of(summary), 1234L, true)),
                VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat(ctx.headers).containsEntry(ListEntitiesEndpoint.VIEW_POSITION_HEADER, "42");
        assertThat(ctx.headers).containsEntry("ETag", "W/\"42\"");

        Map<String, Object> body = asMap(ctx.body);
        assertThat(body).containsOnlyKeys("data", "pagination", "meta");

        List<?> data = (List<?>) body.get("data");
        Map<String, Object> run = asMap(data.get(0));
        assertThat(run).containsOnlyKeys(
                "runId", "automationId", "automationName", "triggeredAt", "status", "terminalReason");
        assertThat(run).containsEntry("runId", RUN_ULID);
        assertThat(run).containsEntry("automationId", AUTO_ULID);
        assertThat(run).containsEntry("automationName", "My Automation");
        assertThat(run).containsEntry("triggeredAt", "2026-01-01T00:00:00Z");
        assertThat(run).containsEntry("status", "COMPLETED");
        assertThat(run).containsEntry("terminalReason", null);

        Map<String, Object> pagination = asMap(body.get("pagination"));
        assertThat(pagination).containsOnlyKeys("nextCursor", "hasMore", "limit");
        assertThat(pagination.get("nextCursor")).isInstanceOf(String.class);
        assertThat(pagination).containsEntry("hasMore", true);
        assertThat(pagination).containsEntry("limit", 50);

        Map<String, Object> meta = asMap(body.get("meta"));
        assertThat(meta).containsOnlyKeys("viewPosition", "timestamp");
        assertThat(meta).containsEntry("viewPosition", 42L);
        assertThat(meta).containsEntry("timestamp", "2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("GET /runs maps internal ABORTED to wire CANCELLED, carrying the precise reason")
    void runs_status_mapsAbortedToCancelled() {
        RunSummary summary = new RunSummary(runId(), autoId(), null, FIXED_INSTANT,
                RunStatus.ABORTED, "restart_mode");
        ListRunsEndpoint endpoint = new ListRunsEndpoint(
                fake().withPage(new RunPage(List.of(summary), 0, false)), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        Map<String, Object> run = asMap(((List<?>) asMap(ctx.body).get("data")).get(0));
        assertThat(run).containsEntry("status", "CANCELLED");
        assertThat(run).containsEntry("terminalReason", "restart_mode");
    }

    @Test
    @DisplayName("GET /runs maps internal CONDITION_NOT_MET to wire SKIPPED")
    void runs_status_mapsConditionNotMetToSkipped() {
        RunSummary summary = new RunSummary(runId(), autoId(), null, FIXED_INSTANT,
                RunStatus.CONDITION_NOT_MET, null);
        ListRunsEndpoint endpoint = new ListRunsEndpoint(
                fake().withPage(new RunPage(List.of(summary), 0, false)), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        Map<String, Object> run = asMap(((List<?>) asMap(ctx.body).get("data")).get(0));
        assertThat(run).containsEntry("status", "SKIPPED");
    }

    @Test
    @DisplayName("GET /runs returns 200 with empty data (not 404) when no runs match")
    void runs_emptyData_200_not404() {
        ListRunsEndpoint endpoint = new ListRunsEndpoint(
                fake().withPage(new RunPage(List.of(), 0, false)), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        Map<String, Object> body = asMap(ctx.body);
        assertThat((List<?>) body.get("data")).isEmpty();
        Map<String, Object> pagination = asMap(body.get("pagination"));
        assertThat(pagination).containsEntry("nextCursor", null);
        assertThat(pagination).containsEntry("hasMore", false);
    }

    @Test
    @DisplayName("GET /runs returns 400 for a malformed automationId")
    void runs_malformedAutomationId_400() {
        ListRunsEndpoint endpoint =
                new ListRunsEndpoint(fake(), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withQueryParam("automationId", "not-a-ulid");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(asMap(ctx.body)).containsEntry("type", ProblemType.INVALID_PARAMETERS.typeUri());
    }

    // ---- /runs/{runId}/causal-chain -----------------------------------------

    @Test
    @DisplayName("GET /runs/{id}/causal-chain serializes exactly the frozen v1.1 tree shape")
    void causalChain_v11ShapeTest() {
        GetRunCausalChainEndpoint endpoint = new GetRunCausalChainEndpoint(
                fake().put(sampleExplanation()), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext().withPathParam("runId", RUN_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat(ctx.headers).containsEntry("ETag", "\"" + RUN_ULID + "\"");

        Map<String, Object> body = asMap(ctx.body);
        assertThat(body).containsOnlyKeys("data", "meta");

        Map<String, Object> data = asMap(body.get("data"));
        assertThat(data).containsOnlyKeys(
                "runId", "automationId", "automationName", "trigger", "conditions", "actions",
                "outcome", "cascade");
        assertThat(data).containsEntry("runId", RUN_ULID);
        assertThat(data).containsEntry("automationId", AUTO_ULID);

        Map<String, Object> trigger = asMap(data.get("trigger"));
        assertThat(trigger).containsOnlyKeys("type", "subjectRef", "matchedAt", "firingValue");
        assertThat(trigger).containsEntry("type", "StateTrigger");
        assertThat(trigger).containsEntry("matchedAt", "2026-01-01T00:00:00Z");
        assertThat(trigger).containsEntry("firingValue", null);
        assertThat(asMap(trigger.get("subjectRef"))).containsOnlyKeys("type", "id");

        Map<String, Object> condition = asMap(((List<?>) data.get("conditions")).get(0));
        assertThat(condition).containsOnlyKeys("expression", "evaluated", "result", "observedState");
        assertThat(condition).containsEntry("evaluated", true);
        assertThat(condition).containsEntry("result", true);
        Map<String, Object> observed = asMap(((List<?>) condition.get("observedState")).get(0));
        assertThat(observed).containsOnlyKeys("entityId", "attribute", "value");

        Map<String, Object> action = asMap(((List<?>) data.get("actions")).get(0));
        assertThat(action).containsOnlyKeys(
                "type", "targetRef", "command", "params", "outcome", "reason");
        assertThat(action).containsEntry("command", "turn_on");
        assertThat(action).containsEntry("outcome", "CONFIRMED");
        assertThat(asMap(action.get("params"))).containsEntry("level", 75);
        assertThat(asMap(action.get("targetRef"))).containsOnlyKeys("type", "id");

        Map<String, Object> outcome = asMap(data.get("outcome"));
        assertThat(outcome).containsOnlyKeys(
                "status", "reason", "durationMs", "actionCount", "commandCount");
        assertThat(outcome).containsEntry("status", "COMPLETED");
        assertThat(outcome).containsEntry("durationMs", 1234L);
        assertThat(outcome).containsEntry("actionCount", 1);
        assertThat(outcome).containsEntry("commandCount", 1);

        Map<String, Object> cascade = asMap(data.get("cascade"));
        assertThat(cascade).containsOnlyKeys("parentRunId", "depth");
        assertThat(cascade).containsEntry("parentRunId", null);
        assertThat(cascade).containsEntry("depth", 0);

        Map<String, Object> meta = asMap(body.get("meta"));
        assertThat(meta).containsOnlyKeys("viewPosition", "timestamp");
        assertThat(meta).containsEntry("viewPosition", 42L);
    }

    @Test
    @DisplayName("GET /runs/{id}/causal-chain returns 404 RFC 9457 problem for an unknown run")
    void causalChain_unknownRun_404_problemJson() {
        GetRunCausalChainEndpoint endpoint =
                new GetRunCausalChainEndpoint(fake(), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext().withPathParam("runId", RUN_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
        assertThat(asMap(ctx.body))
                .containsEntry("type", ProblemType.NOT_FOUND.typeUri())
                .containsEntry("status", 404)
                .containsEntry("title", ProblemType.NOT_FOUND.title());
    }

    @Test
    @DisplayName("GET /runs/{id}/causal-chain returns 400 for a malformed runId")
    void causalChain_malformedRunId_400() {
        GetRunCausalChainEndpoint endpoint =
                new GetRunCausalChainEndpoint(fake(), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx =
                new RecordingEndpointContext().withPathParam("runId", "not-a-ulid");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(asMap(ctx.body)).containsEntry("type", ProblemType.INVALID_PARAMETERS.typeUri());
    }

    // ---- helpers ------------------------------------------------------------

    private static RunId runId() {
        return new RunId(Ulid.parse(RUN_ULID));
    }

    private static AutomationId autoId() {
        return AutomationId.parse(AUTO_ULID);
    }

    private static FakeExplanationService fake() {
        return new FakeExplanationService();
    }

    private static RunExplanation sampleExplanation() {
        return new RunExplanation(runId(), autoId(), "My Automation",
                new RunExplanation.TriggerView("StateTrigger",
                        new RunExplanation.SubjectRefView("entity", ENTITY_ULID),
                        FIXED_INSTANT, null),
                List.of(new RunExplanation.ConditionView("StateCondition", true, true,
                        List.of(new RunExplanation.ObservedStateEntry(
                                ENTITY_ULID, "motion", "active")))),
                List.of(new RunExplanation.ActionView("CommandAction",
                        new RunExplanation.SubjectRefView("entity", ENTITY_ULID),
                        "turn_on", "{\"level\":75}",
                        RunExplanation.ActionOutcome.CONFIRMED, null)),
                new RunExplanation.OutcomeView(RunStatus.COMPLETED, null, 1234L, 1, 1),
                new RunExplanation.CascadeView(null, 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Canned {@link ExplanationService} for endpoint tests. */
    private static final class FakeExplanationService implements ExplanationService {
        private RunPage page = new RunPage(List.of(), 0, false);
        private final Map<RunId, RunExplanation> explanations = new HashMap<>();

        FakeExplanationService() {
        }

        FakeExplanationService withPage(RunPage page) {
            this.page = page;
            return this;
        }

        FakeExplanationService put(RunExplanation explanation) {
            explanations.put(explanation.runId(), explanation);
            return this;
        }

        @Override
        public RunPage listRuns(Optional<AutomationId> automationId, long beforePosition, int limit) {
            return page;
        }

        @Override
        public Optional<RunExplanation> explainRun(RunId runId) {
            return Optional.ofNullable(explanations.get(runId));
        }

        // M7.5b methods — unused by the run-endpoint tests; canned empties keep the stub compiling.
        @Override
        public Optional<NonFiringExplanation> explainNonFiring(AutomationId automationId,
                                                               long expectedSincePosition) {
            return Optional.empty();
        }

        @Override
        public List<AutomationSummary> listAutomations() {
            return List.of();
        }
    }
}
