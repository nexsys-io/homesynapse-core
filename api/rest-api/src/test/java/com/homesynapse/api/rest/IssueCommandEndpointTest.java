/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.automation.StandardCommandValidator;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationPolicy;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.IdempotencyClass;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IssueCommandEndpoint}.
 *
 * <p>Drives the endpoint's {@code apply(EndpointContext)} directly through a
 * {@link RecordingEndpointContext} stub (the {@link ListEntitiesEndpointTest}
 * pattern). The {@link RecordingEventPublisher} captures the published draft
 * so the frozen wire tokens (DP-2) and the published event shape (DP-3) are
 * pinned byte-for-byte; the dispatch subscriber's downstream behavior is the
 * pipeline's already-proven property and is deliberately NOT asserted here.</p>
 */
@DisplayName("IssueCommandEndpoint")
final class IssueCommandEndpointTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);

    private static final String ENTITY_ULID = "01H8000000000000000000000A";
    private static final long PROJECTION_CURSOR = 777L;
    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final String VALID_BODY =
            "{\"capability\":\"on_off\",\"command\":\"turn_on\",\"parameters\":{}}";

    private RecordingEventPublisher publisher;
    private FakeEntityRegistry registry;
    private IssueCommandEndpoint endpoint;

    IssueCommandEndpointTest() {
    }

    @BeforeEach
    void setUp() {
        publisher = new RecordingEventPublisher(FIXED_CLOCK);
        registry = new FakeEntityRegistry().put(lightEntity(ENTITY_ULID));
        endpoint = endpointOver(publisher, registry, new IdempotencyCache(FIXED_CLOCK),
                FIXED_CLOCK);
    }

    @Test
    @DisplayName("happy path returns 202 with the exact frozen data+meta key sets and values")
    void happyPathPinsWireShape() {
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(202);
        Map<String, Object> body = bodyOf(ctx);
        assertThat(body.keySet()).containsExactly("data", "meta");
        Map<String, Object> data = section(body, "data");
        assertThat(data.keySet()).containsExactly(
                "commandId", "correlationId", "entityId", "status", "acceptedAt", "viewPosition");
        Map<String, Object> meta = section(body, "meta");
        assertThat(meta.keySet()).containsExactly("viewPosition", "timestamp");

        EventEnvelope envelope = publisher.published.get(0);
        assertThat(data.get("commandId")).isEqualTo(envelope.eventId().toString());
        assertThat(data.get("correlationId")).isEqualTo(data.get("commandId"));
        assertThat(data.get("entityId")).isEqualTo(ENTITY_ULID);
        assertThat(data.get("status")).isEqualTo("accepted");
        assertThat(data.get("acceptedAt")).isEqualTo("2026-07-22T12:00:00Z");
        assertThat(data.get("viewPosition")).isEqualTo(envelope.globalPosition());
        assertThat(meta.get("viewPosition")).isEqualTo(PROJECTION_CURSOR);
        assertThat(meta.get("timestamp")).isEqualTo("2026-07-22T12:00:00Z");
    }

    @Test
    @DisplayName("happy path sets Cache-Control: no-store and the view-position header")
    void happyPathSetsHeaders() {
        RecordingEndpointContext ctx = post(VALID_BODY);

        endpoint.apply(ctx);

        assertThat(ctx.headers)
                .containsEntry("Cache-Control", "no-store")
                .containsEntry("X-HomeSynapse-View-Position", Long.toString(PROJECTION_CURSOR));
    }

    @Test
    @DisplayName("publishes the command_issued draft field-for-field (origin USER_COMMAND); "
            + "parameters serialize KEY-SORTED regardless of client key order")
    void publishesDraftFieldForField() {
        // Client sends ramp BEFORE level — the payload must serialize sorted.
        RecordingEndpointContext ctx = post(
                "{\"capability\":\"on_off\",\"command\":\"turn_on\","
                        + "\"parameters\":{\"ramp\":true,\"level\":42}}");

        endpoint.apply(ctx);

        assertThat(publisher.rootDrafts).hasSize(1);
        EventDraft draft = publisher.rootDrafts.get(0);
        assertThat(draft.eventType()).isEqualTo(EventTypes.COMMAND_ISSUED);
        assertThat(draft.schemaVersion()).isEqualTo(1);
        assertThat(draft.eventTime()).isNull();
        assertThat(draft.subjectRef())
                .isEqualTo(SubjectRef.entity(EntityId.of(Ulid.parse(ENTITY_ULID))));
        assertThat(draft.priority()).isEqualTo(EventPriority.NORMAL);
        assertThat(draft.origin()).isEqualTo(EventOrigin.USER_COMMAND);
        assertThat(draft.actorRef()).isNull();
        assertThat(draft.idempotencyKey()).isNull();

        CommandIssuedEvent payload = (CommandIssuedEvent) draft.payload();
        assertThat(payload.targetEntityRef()).isEqualTo(Ulid.parse(ENTITY_ULID));
        assertThat(payload.commandType()).isEqualTo("turn_on");
        assertThat(payload.parameters()).isEqualTo("{\"level\":42,\"ramp\":true}");
        assertThat(payload.confirmationTimeoutMs()).isEqualTo(15_000);
        assertThat(payload.idempotencyClass()).isEqualTo(CommandIdempotency.IDEMPOTENT);
    }

    @Test
    @DisplayName("parameterless command serializes parameters as the {} floor and falls back "
            + "to the config default timeout + NOT_IDEMPOTENT-declared class")
    void resolvesFallbackTimeoutAndDeclaredIdempotency() {
        RecordingEndpointContext ctx = post(
                "{\"capability\":\"on_off\",\"command\":\"toggle\",\"parameters\":{}}");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(202);
        CommandIssuedEvent payload = (CommandIssuedEvent) publisher.rootDrafts.get(0).payload();
        assertThat(payload.parameters()).isEqualTo("{}");
        assertThat(payload.confirmationTimeoutMs()).isEqualTo(DEFAULT_TIMEOUT_MS);
        assertThat(payload.idempotencyClass()).isEqualTo(CommandIdempotency.NOT_IDEMPOTENT);
    }

    @Test
    @DisplayName("absent entity returns 404 and publishes nothing")
    void absentEntityIs404() {
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", "01H8000000000000000000000B")
                .withBody(VALID_BODY);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
        assertThat(bodyOf(ctx).get("title")).isEqualTo("Not Found");
        assertThat(publisher.rootDrafts).isEmpty();
    }

    @Test
    @DisplayName("unparseable entityId returns 404 and publishes nothing")
    void unparseableEntityIdIs404() {
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", "not-a-ulid")
                .withBody(VALID_BODY);

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
        assertThat(publisher.rootDrafts).isEmpty();
    }

    @Test
    @DisplayName("entity resolution (404) precedes command validation (422)")
    void entityResolutionPrecedesValidation() {
        RecordingEndpointContext ctx = new RecordingEndpointContext()
                .withPathParam("entityId", "01H8000000000000000000000B")
                .withBody("{\"capability\":\"on_off\",\"command\":\"lock\",\"parameters\":{}}");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(404);
    }

    @Test
    @DisplayName("undeclared command returns 422 with the validator reason verbatim")
    void undeclaredCommandIs422ReasonVerbatim() {
        RecordingEndpointContext ctx = post(
                "{\"capability\":\"on_off\",\"command\":\"lock\",\"parameters\":{}}");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(422);
        Map<String, Object> body = bodyOf(ctx);
        assertThat(body.get("title")).isEqualTo("Invalid Command");
        assertThat(body.get("detail")).isEqualTo(
                "Entity '" + ENTITY_ULID + "' does not support command 'lock'");
        assertThat(publisher.rootDrafts).isEmpty();
    }

    @Test
    @DisplayName("missing required fields return 400 with one FieldError per field")
    void missingFieldsAre400WithFieldErrors() {
        RecordingEndpointContext ctx = post("{}");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        Map<String, Object> body = bodyOf(ctx);
        assertThat(body.get("title")).isEqualTo("Invalid Parameters");
        assertThat(fieldsOf(body)).containsExactlyInAnyOrder(
                "capability", "command", "parameters");
        assertThat(publisher.rootDrafts).isEmpty();
    }

    @Test
    @DisplayName("blank command returns 400 with a command FieldError")
    void blankCommandIs400() {
        RecordingEndpointContext ctx = post(
                "{\"capability\":\"on_off\",\"command\":\"  \",\"parameters\":{}}");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(fieldsOf(bodyOf(ctx))).containsExactly("command");
    }

    @Test
    @DisplayName("non-object parameters return 400 with a parameters FieldError")
    void nonObjectParametersIs400() {
        RecordingEndpointContext ctx = post(
                "{\"capability\":\"on_off\",\"command\":\"turn_on\",\"parameters\":\"nope\"}");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(fieldsOf(bodyOf(ctx))).containsExactly("parameters");
    }

    @Test
    @DisplayName("malformed JSON body returns 400 and publishes nothing")
    void malformedBodyIs400() {
        RecordingEndpointContext ctx = post("this is not json");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(fieldsOf(bodyOf(ctx))).containsExactly("body");
        assertThat(publisher.rootDrafts).isEmpty();
    }

    @Test
    @DisplayName("unknown body fields are ignored (FAIL_ON_UNKNOWN_PROPERTIES=false posture)")
    void unknownBodyFieldsAreIgnored() {
        RecordingEndpointContext ctx = post(
                "{\"capability\":\"on_off\",\"command\":\"turn_on\",\"parameters\":{},"
                        + "\"futureField\":123}");

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(202);
    }

    @Test
    @DisplayName("oversize Idempotency-Key (129 chars) returns 400 and publishes nothing")
    void oversizeIdempotencyKeyIs400() {
        RecordingEndpointContext ctx = post(VALID_BODY)
                .withRequestHeader("Idempotency-Key", "k".repeat(129));

        endpoint.apply(ctx);

        assertThat(ctx.statusSet).isEqualTo(400);
        assertThat(fieldsOf(bodyOf(ctx))).containsExactly("Idempotency-Key");
        assertThat(publisher.rootDrafts).isEmpty();
    }

    @Test
    @DisplayName("same key + same body replays the cached 202 with ZERO second publish")
    void replaySameKeySameBody() {
        MutableTestClock clock = new MutableTestClock(Instant.parse("2026-07-22T12:00:00Z"));
        RecordingEventPublisher recorder = new RecordingEventPublisher(clock);
        IssueCommandEndpoint replayEndpoint =
                endpointOver(recorder, registry, new IdempotencyCache(clock), clock);

        RecordingEndpointContext first = post(VALID_BODY)
                .withRequestHeader("Idempotency-Key", "retry-1");
        replayEndpoint.apply(first);
        Map<String, Object> firstData = section(bodyOf(first), "data");

        clock.advance(Duration.ofHours(1));
        RecordingEndpointContext second = post(VALID_BODY)
                .withRequestHeader("Idempotency-Key", "retry-1");
        replayEndpoint.apply(second);

        assertThat(second.statusSet).isEqualTo(202);
        assertThat(recorder.rootDrafts).hasSize(1);
        Map<String, Object> secondData = section(bodyOf(second), "data");
        assertThat(secondData.get("commandId")).isEqualTo(firstData.get("commandId"));
        assertThat(secondData.get("correlationId")).isEqualTo(firstData.get("correlationId"));
        assertThat(secondData.get("viewPosition")).isEqualTo(firstData.get("viewPosition"));
        assertThat(secondData.get("acceptedAt")).isEqualTo("2026-07-22T12:00:00Z");
        Map<String, Object> secondMeta = section(bodyOf(second), "meta");
        assertThat(secondMeta.get("timestamp")).isEqualTo("2026-07-22T13:00:00Z");
    }

    @Test
    @DisplayName("same key + different body returns 409 IDEMPOTENCY_KEY_CONFLICT")
    void conflictSameKeyDifferentBody() {
        RecordingEndpointContext first = post(
                "{\"capability\":\"on_off\",\"command\":\"turn_on\","
                        + "\"parameters\":{\"level\":1}}")
                .withRequestHeader("Idempotency-Key", "retry-2");
        endpoint.apply(first);

        RecordingEndpointContext second = post(
                "{\"capability\":\"on_off\",\"command\":\"turn_on\","
                        + "\"parameters\":{\"level\":2}}")
                .withRequestHeader("Idempotency-Key", "retry-2");
        endpoint.apply(second);

        assertThat(second.statusSet).isEqualTo(409);
        assertThat(bodyOf(second).get("title")).isEqualTo("Idempotency Key Conflict");
        assertThat(publisher.rootDrafts).hasSize(1);
    }

    @Test
    @DisplayName("no Idempotency-Key header bypasses the cache: two publishes, two command ids")
    void noHeaderPublishesTwice() {
        RecordingEndpointContext first = post(VALID_BODY);
        endpoint.apply(first);
        RecordingEndpointContext second = post(VALID_BODY);
        endpoint.apply(second);

        assertThat(publisher.rootDrafts).hasSize(2);
        String firstId = (String) section(bodyOf(first), "data").get("commandId");
        String secondId = (String) section(bodyOf(second), "data").get("commandId");
        assertThat(secondId).isNotEqualTo(firstId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private IssueCommandEndpoint endpointOver(RecordingEventPublisher eventPublisher,
                                              FakeEntityRegistry entityRegistry,
                                              IdempotencyCache cache,
                                              Clock clock) {
        return new IssueCommandEndpoint(eventPublisher, entityRegistry,
                new StandardCommandValidator(entityRegistry), cache,
                DEFAULT_TIMEOUT_MS, () -> PROJECTION_CURSOR, clock);
    }

    private static RecordingEndpointContext post(String body) {
        return new RecordingEndpointContext()
                .withPathParam("entityId", ENTITY_ULID)
                .withBody(body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(RecordingEndpointContext ctx) {
        assertThat(ctx.body).as("response body").isNotNull();
        return (Map<String, Object>) ctx.body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> fieldsOf(Map<String, Object> problemBody) {
        List<Map<String, Object>> errors =
                (List<Map<String, Object>>) problemBody.get("errors");
        assertThat(errors).as("errors array").isNotNull();
        return errors.stream().map(e -> (String) e.get("field")).toList();
    }

    /**
     * A LIGHT entity with one {@code on_off} capability declaring
     * {@code turn_on} (15s capability timeout, IDEMPOTENT) and {@code toggle}
     * (no positive capability timeout — exercises the config fallback —
     * NOT_IDEMPOTENT).
     */
    static Entity lightEntity(String ulid) {
        CommandDefinition turnOn = new CommandDefinition("turn_on", List.of(), 0, List.of(),
                Duration.ofSeconds(15), IdempotencyClass.IDEMPOTENT);
        CommandDefinition toggle = new CommandDefinition("toggle", List.of(), 0, List.of(),
                Duration.ZERO, IdempotencyClass.NOT_IDEMPOTENT);
        CapabilityInstance onOff = new CapabilityInstance("on_off", 1, "core", 0,
                Map.of(), Map.of("turn_on", turnOn, "toggle", toggle),
                new ConfirmationPolicy(ConfirmationMode.EXACT_MATCH, List.of("on"), null, 5000L));
        return new Entity(EntityId.of(Ulid.parse(ulid)), "test-light", EntityType.LIGHT,
                "Test Light", null, 1, null, true, List.of(), List.of(onOff),
                EntityRole.PRIMARY, Instant.parse("2026-07-22T00:00:00Z"));
    }
}
