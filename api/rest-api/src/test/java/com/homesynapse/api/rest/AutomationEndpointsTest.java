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
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ListAutomationsEndpoint} and {@link GetNonFiringEndpoint} (M7.5b), driven
 * through a recording {@link EndpointContext} stub and a fake {@link ExplanationService}.
 *
 * <p>The headline is the <strong>v1.1 shape test</strong> — the cross-lane pin the Web-UI lane
 * depends on: the serialized {@code /automations} and {@code /automations/{id}/non-firing} bodies
 * must carry exactly the frozen camelCase field names, nesting, verdict vocabulary, nullability,
 * pagination, and meta.</p>
 */
@DisplayName("Automation query endpoints")
final class AutomationEndpointsTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final LongSupplier VIEW_POSITION = () -> 42L;

    private static final String RUN_ULID = "01H8000000000000000000000A";
    private static final String AUTO_ULID = "01H8000000000000000000000B";

    AutomationEndpointsTest() {
    }

    // ---- /automations/{id}/non-firing ---------------------------------------

    @Test
    @DisplayName("GET /automations/{id}/non-firing serializes exactly the frozen v1.1 shape")
    void nonFiring_v11ShapeTest() {
        NonFiringExplanation explanation = new NonFiringExplanation(
                AutomationId.parse(AUTO_ULID), "My Automation", true,
                NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED,
                new RunId(Ulid.parse(RUN_ULID)),
                "Automation 'My Automation' fired, but a device did not confirm the requested change.",
                "Fires on state change",
                new NonFiringExplanation.LastEvaluationView(FIXED_INSTANT, "true"));
        GetNonFiringEndpoint endpoint =
                new GetNonFiringEndpoint(fake().withNonFiring(explanation), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext().withPathParam("id", AUTO_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat(ctx.headers).containsEntry(ListEntitiesEndpoint.VIEW_POSITION_HEADER, "42");
        // A non-firing verdict is mutable → weak ETag keyed on the projection position.
        assertThat(ctx.headers).containsEntry("ETag", "W/\"42\"");

        Map<String, Object> body = asMap(ctx.body);
        assertThat(body).containsOnlyKeys("data", "meta");

        Map<String, Object> data = asMap(body.get("data"));
        assertThat(data).containsOnlyKeys("automationId", "automationName", "enabled", "verdict",
                "lastRelevantRunId", "explanation", "triggerSummary", "lastEvaluation");
        assertThat(data).containsEntry("automationId", AUTO_ULID);
        assertThat(data).containsEntry("automationName", "My Automation");
        assertThat(data).containsEntry("enabled", true);
        assertThat(data).containsEntry("verdict", "ACTED_BUT_UNCONFIRMED");
        assertThat(data).containsEntry("lastRelevantRunId", RUN_ULID);
        assertThat(data).containsEntry("triggerSummary", "Fires on state change");

        Map<String, Object> lastEvaluation = asMap(data.get("lastEvaluation"));
        assertThat(lastEvaluation).containsOnlyKeys("at", "conditionsResult");
        assertThat(lastEvaluation).containsEntry("at", "2026-01-01T00:00:00Z");
        assertThat(lastEvaluation).containsEntry("conditionsResult", "true");

        Map<String, Object> meta = asMap(body.get("meta"));
        assertThat(meta).containsOnlyKeys("viewPosition", "timestamp");
        assertThat(meta).containsEntry("viewPosition", 42L);
        assertThat(meta).containsEntry("timestamp", "2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("GET /automations/{id}/non-firing renders nullable run id + lastEvaluation as null")
    void nonFiring_neverTriggered_nullsRenderedAsNull() {
        NonFiringExplanation explanation = new NonFiringExplanation(
                AutomationId.parse(AUTO_ULID), "My Automation", true,
                NonFiringExplanation.NonFiringVerdict.NEVER_TRIGGERED, null,
                "Automation 'My Automation' has not been triggered; it fires on state change.",
                "Fires on state change", null);
        GetNonFiringEndpoint endpoint =
                new GetNonFiringEndpoint(fake().withNonFiring(explanation), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext().withPathParam("id", AUTO_ULID);

        endpoint.apply(ctx);

        Map<String, Object> data = asMap(asMap(ctx.body).get("data"));
        assertThat(data).containsEntry("verdict", "NEVER_TRIGGERED");
        assertThat(data).containsEntry("lastRelevantRunId", null);
        assertThat(data).containsEntry("lastEvaluation", null);
    }

    @Test
    @DisplayName("GET /automations/{id}/non-firing returns 404 RFC 9457 for an unknown automation")
    void nonFiring_unknownAutomation_404_problemJson() {
        GetNonFiringEndpoint endpoint = new GetNonFiringEndpoint(fake(), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext().withPathParam("id", AUTO_ULID);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
        assertThat(asMap(ctx.body))
                .containsEntry("type", ProblemType.NOT_FOUND.typeUri())
                .containsEntry("status", 404)
                .containsEntry("title", ProblemType.NOT_FOUND.title());
    }

    @Test
    @DisplayName("GET /automations/{id}/non-firing returns 400 for a malformed id")
    void nonFiring_malformedId_400() {
        GetNonFiringEndpoint endpoint = new GetNonFiringEndpoint(fake(), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx =
                new RecordingEndpointContext().withPathParam("id", "not-a-ulid");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(asMap(ctx.body)).containsEntry("type", ProblemType.INVALID_PARAMETERS.typeUri());
    }

    @Test
    @DisplayName("GET /automations/{id}/non-firing returns 400 for a malformed expectedSince cursor")
    void nonFiring_malformedCursor_400() {
        NonFiringExplanation explanation = new NonFiringExplanation(
                AutomationId.parse(AUTO_ULID), "My Automation", true,
                NonFiringExplanation.NonFiringVerdict.NEVER_TRIGGERED, null, "n/a",
                "Fires on state change", null);
        GetNonFiringEndpoint endpoint =
                new GetNonFiringEndpoint(fake().withNonFiring(explanation), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("id", AUTO_ULID)
                .withQueryParam("expectedSince", "@@not-a-cursor@@");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(asMap(ctx.body)).containsEntry("type", ProblemType.INVALID_PARAMETERS.typeUri());
    }

    // ---- /automations -------------------------------------------------------

    @Test
    @DisplayName("GET /automations serializes exactly the frozen v1.1 list shape")
    void automations_v11ShapeTest() {
        AutomationSummary summary = new AutomationSummary(
                AutomationId.parse(AUTO_ULID), "My Automation", true,
                List.of(new AutomationSummary.ComponentView("StateChangeTrigger",
                        "state change trigger")),
                new RunId(Ulid.parse(RUN_ULID)));
        ListAutomationsEndpoint endpoint = new ListAutomationsEndpoint(
                fake().withAutomations(List.of(summary)), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat(ctx.headers).containsEntry(ListEntitiesEndpoint.VIEW_POSITION_HEADER, "42");
        assertThat(ctx.headers).containsEntry("ETag", "W/\"42\"");

        Map<String, Object> body = asMap(ctx.body);
        assertThat(body).containsOnlyKeys("data", "pagination", "meta");

        List<?> data = (List<?>) body.get("data");
        Map<String, Object> automation = asMap(data.get(0));
        assertThat(automation).containsOnlyKeys(
                "automationId", "name", "enabled", "components", "lastRunId");
        assertThat(automation).containsEntry("automationId", AUTO_ULID);
        assertThat(automation).containsEntry("name", "My Automation");
        assertThat(automation).containsEntry("enabled", true);
        assertThat(automation).containsEntry("lastRunId", RUN_ULID);

        Map<String, Object> component = asMap(((List<?>) automation.get("components")).get(0));
        assertThat(component).containsOnlyKeys("type", "summary");
        assertThat(component).containsEntry("type", "StateChangeTrigger");
        assertThat(component).containsEntry("summary", "state change trigger");

        Map<String, Object> pagination = asMap(body.get("pagination"));
        assertThat(pagination).containsOnlyKeys("nextCursor", "hasMore", "limit");
        assertThat(pagination).containsEntry("nextCursor", null);
        assertThat(pagination).containsEntry("hasMore", false);
        assertThat(pagination).containsEntry("limit", 50);

        Map<String, Object> meta = asMap(body.get("meta"));
        assertThat(meta).containsOnlyKeys("viewPosition", "timestamp");
        assertThat(meta).containsEntry("viewPosition", 42L);
    }

    @Test
    @DisplayName("GET /automations renders a null lastRunId as JSON null")
    void automations_nullLastRunId_renderedAsNull() {
        AutomationSummary summary = new AutomationSummary(
                AutomationId.parse(AUTO_ULID), "Never Run", false,
                List.of(new AutomationSummary.ComponentView("StateTrigger", "state trigger")),
                null);
        ListAutomationsEndpoint endpoint = new ListAutomationsEndpoint(
                fake().withAutomations(List.of(summary)), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        Map<String, Object> automation = asMap(((List<?>) asMap(ctx.body).get("data")).get(0));
        assertThat(automation).containsEntry("lastRunId", null);
        assertThat(automation).containsEntry("enabled", false);
    }

    @Test
    @DisplayName("GET /automations returns 200 with empty data (not 404) when none are loaded")
    void automations_emptyData_200_not404() {
        ListAutomationsEndpoint endpoint =
                new ListAutomationsEndpoint(fake(), VIEW_POSITION, FIXED_CLOCK);
        RecordingEndpointContext ctx = new RecordingEndpointContext();

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(200);
        assertThat((List<?>) asMap(ctx.body).get("data")).isEmpty();
        Map<String, Object> pagination = asMap(asMap(ctx.body).get("pagination"));
        assertThat(pagination).containsEntry("hasMore", false);
        assertThat(pagination).containsEntry("nextCursor", null);
    }

    // ---- helpers ------------------------------------------------------------

    private static FakeExplanationService fake() {
        return new FakeExplanationService();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Canned {@link ExplanationService} for the automation endpoint tests. */
    private static final class FakeExplanationService implements ExplanationService {
        private NonFiringExplanation nonFiring;
        private List<AutomationSummary> automations = List.of();

        FakeExplanationService() {
        }

        FakeExplanationService withNonFiring(NonFiringExplanation nonFiring) {
            this.nonFiring = nonFiring;
            return this;
        }

        FakeExplanationService withAutomations(List<AutomationSummary> automations) {
            this.automations = List.copyOf(automations);
            return this;
        }

        @Override
        public RunPage listRuns(Optional<AutomationId> automationId, long beforePosition, int limit) {
            return new RunPage(List.of(), 0, false);
        }

        @Override
        public Optional<RunExplanation> explainRun(RunId runId) {
            return Optional.empty();
        }

        @Override
        public Optional<NonFiringExplanation> explainNonFiring(AutomationId automationId,
                                                               long expectedSincePosition) {
            return Optional.ofNullable(nonFiring);
        }

        @Override
        public List<AutomationSummary> listAutomations() {
            return automations;
        }
    }
}
