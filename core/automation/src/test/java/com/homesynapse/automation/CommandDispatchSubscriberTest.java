/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;

import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardCommandDispatchService} as the co-located {@code command_dispatch_service} bus
 * subscriber (M7.4a): {@code onEvent(command_issued)} runs resolve&rarr;validate&rarr;emit in
 * {@code LIVE} only (D2 pure-function-replay), threading the full causal chain (Doc 07 §3.11.2),
 * and the paired {@code close()} teardown (the reverted-M7.3 lesson).
 */
@DisplayName("StandardCommandDispatchService — command_dispatch_service subscriber (M7.4a)")
class CommandDispatchSubscriberTest {

    private final EntityId entityId = AutomationTestSupport.entityId();
    private final DeviceId deviceId = AutomationTestSupport.deviceId();
    private final IntegrationId integrationId = AutomationTestSupport.integrationId();

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private StandardCommandDispatchService subscriber;

    @BeforeEach
    void setUp() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.onOff());
        Device device = AutomationTestSupport.device(deviceId, integrationId);
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        subscriber = new StandardCommandDispatchService(
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)),
                new AutomationTestSupport.MapDeviceRegistry(List.of(device)),
                new StandardCommandValidator(
                        new AutomationTestSupport.StubEntityRegistry(List.of(entity))),
                publisher);
    }

    @Test
    @DisplayName("a LIVE command_issued dispatches and threads the Run's causal chain")
    void onCommandIssued_inLive_dispatches() {
        subscriber.setMode(SubscriberMode.LIVE);
        Ulid correlation = AutomationTestSupport.ulid();
        Ulid triggerEventId = AutomationTestSupport.ulid();
        EventEnvelope issued =
                AutomationTestSupport.commandIssued(entityId, "turn_on", correlation, triggerEventId);

        subscriber.onEvent(issued);

        List<EventEnvelope> dispatched = publisher.ofType(EventTypes.COMMAND_DISPATCHED);
        assertThat(dispatched).hasSize(1);
        CommandDispatchedEvent payload = (CommandDispatchedEvent) dispatched.get(0).payload();
        assertThat(payload.targetEntityRef()).isEqualTo(entityId.value());
        assertThat(payload.integrationId()).isEqualTo(integrationId.value());
        assertThat(publisher.ofType(EventTypes.COMMAND_RESULT)).isEmpty();
        // Doc 07 §3.11.2: correlation = the Run's; causation = the command_issued event.
        assertThat(dispatched.get(0).causalContext().correlationId()).isEqualTo(correlation);
        assertThat(dispatched.get(0).causalContext().causationId())
                .isEqualTo(issued.eventId().value());
    }

    @Test
    @DisplayName("D2: a command_issued delivered in REPLAY produces zero dispatch side-effects")
    void onCommandIssued_inReplay_doesNotDispatch() {
        subscriber.setMode(SubscriberMode.REPLAY);
        EventEnvelope issued = AutomationTestSupport.commandIssued(
                entityId, "turn_on", AutomationTestSupport.ulid(), AutomationTestSupport.ulid());

        subscriber.onEvent(issued);

        assertThat(publisher.published()).isEmpty();    // no command_dispatched, no command_result
    }

    @Test
    @DisplayName("an entity that routes to no integration emits command_result(unroutable)")
    void unroutableEntity_emitsCommandResultUnroutable() {
        subscriber.setMode(SubscriberMode.LIVE);
        EntityId unknown = AutomationTestSupport.entityId();
        EventEnvelope issued = AutomationTestSupport.commandIssued(
                unknown, "turn_on", AutomationTestSupport.ulid(), AutomationTestSupport.ulid());

        subscriber.onEvent(issued);

        List<EventEnvelope> results = publisher.ofType(EventTypes.COMMAND_RESULT);
        assertThat(results).hasSize(1);
        assertThat(((CommandResultEvent) results.get(0).payload()).outcome())
                .isEqualTo("unroutable");
        assertThat(publisher.ofType(EventTypes.COMMAND_DISPATCHED)).isEmpty();
    }

    @Test
    @DisplayName("an unsupported command emits command_result(invalid)")
    void invalidCommand_emitsCommandResultInvalid() {
        subscriber.setMode(SubscriberMode.LIVE);
        EventEnvelope issued = AutomationTestSupport.commandIssued(
                entityId, "frobnicate", AutomationTestSupport.ulid(), AutomationTestSupport.ulid());

        subscriber.onEvent(issued);

        List<EventEnvelope> results = publisher.ofType(EventTypes.COMMAND_RESULT);
        assertThat(results).hasSize(1);
        CommandResultEvent payload = (CommandResultEvent) results.get(0).payload();
        assertThat(payload.outcome()).isEqualTo("invalid");
        assertThat(payload.commandType()).isEqualTo("frobnicate");
        assertThat(publisher.ofType(EventTypes.COMMAND_DISPATCHED)).isEmpty();
    }

    // ── HONESTY-1 ORIGIN-1: command-event provenance is INHERITED from the issued envelope ──

    @Test
    @DisplayName("ORIGIN-1 T-B1: command_dispatched inherits the issued envelope's origin + actorRef (USER_COMMAND, actor X); the causal chain unchanged")
    void onCommandIssued_userCommand_dispatchedInheritsOriginAndActor() {
        subscriber.setMode(SubscriberMode.LIVE);
        Ulid correlation = AutomationTestSupport.ulid();
        Ulid triggerEventId = AutomationTestSupport.ulid();
        Ulid actor = AutomationTestSupport.ulid();
        EventEnvelope issued = AutomationTestSupport.commandIssued(entityId, "turn_on",
                correlation, triggerEventId, EventOrigin.USER_COMMAND, actor);

        subscriber.onEvent(issued);

        List<EventEnvelope> dispatched = publisher.ofType(EventTypes.COMMAND_DISPATCHED);
        assertThat(dispatched).hasSize(1);
        // Doc 01 §3.9: origin is evidence-based — the command_issued envelope IS the evidence.
        // A REST-issued command's dispatch reads USER_COMMAND, not the router's own AUTOMATION.
        assertThat(dispatched.get(0).origin()).isEqualTo(EventOrigin.USER_COMMAND);
        assertThat(dispatched.get(0).actorRef())
                .as("actorRef travels with the origin (INV-MU-01)")
                .isEqualTo(actor);
        // Doc 07 §3.11.2 unchanged beside the new fields: correlation = the Run's; causation =
        // the command_issued event (the same assertions as onCommandIssued_inLive_dispatches).
        assertThat(dispatched.get(0).causalContext().correlationId()).isEqualTo(correlation);
        assertThat(dispatched.get(0).causalContext().causationId())
                .isEqualTo(issued.eventId().value());
        assertThat(publisher.ofType(EventTypes.COMMAND_RESULT)).isEmpty();
    }

    @Test
    @DisplayName("ORIGIN-1 T-B2: the router's own command_result (unroutable) inherits the issued envelope's origin + actorRef")
    void unroutableEntity_userCommand_resultInheritsOriginAndActor() {
        subscriber.setMode(SubscriberMode.LIVE);
        EntityId unknown = AutomationTestSupport.entityId();
        Ulid actor = AutomationTestSupport.ulid();
        EventEnvelope issued = AutomationTestSupport.commandIssued(unknown, "turn_on",
                AutomationTestSupport.ulid(), AutomationTestSupport.ulid(),
                EventOrigin.USER_COMMAND, actor);

        subscriber.onEvent(issued);

        List<EventEnvelope> results = publisher.ofType(EventTypes.COMMAND_RESULT);
        assertThat(results).hasSize(1);
        assertThat(((CommandResultEvent) results.get(0).payload()).outcome())
                .isEqualTo("unroutable");
        assertThat(results.get(0).origin()).isEqualTo(EventOrigin.USER_COMMAND);
        assertThat(results.get(0).actorRef()).isEqualTo(actor);
        assertThat(publisher.ofType(EventTypes.COMMAND_DISPATCHED)).isEmpty();
    }

    @Test
    @DisplayName("ORIGIN-1 T-B4 (regression): an AUTOMATION-issued command still dispatches as AUTOMATION, no actor")
    void onCommandIssued_automation_dispatchedStaysAutomation() {
        subscriber.setMode(SubscriberMode.LIVE);
        // The 4-arg fixture: AUTOMATION, actorRef null — every pre-HONESTY-1 caller's shape.
        EventEnvelope issued = AutomationTestSupport.commandIssued(
                entityId, "turn_on", AutomationTestSupport.ulid(), AutomationTestSupport.ulid());

        subscriber.onEvent(issued);

        List<EventEnvelope> dispatched = publisher.ofType(EventTypes.COMMAND_DISPATCHED);
        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.get(0).origin()).isEqualTo(EventOrigin.AUTOMATION);
        assertThat(dispatched.get(0).actorRef()).isNull();
    }

    @Test
    @DisplayName("close() releases resources and is idempotent (the paired-teardown smoke test)")
    void stop_releasesResources() {
        // Stateless today, so the smoke test is that close() exists, never throws, and is safe to
        // call twice — establishing the teardown the composition root MUST call (M7.3 lesson).
        assertThatCode(() -> {
            subscriber.close();
            subscriber.close();
        }).doesNotThrowAnyException();
    }
}
