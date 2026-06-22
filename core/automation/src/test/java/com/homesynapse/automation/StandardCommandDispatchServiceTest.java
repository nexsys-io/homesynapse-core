/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StandardCommandDispatchService} — resolve &rarr; validate &rarr; dispatch, producing
 * {@code command_dispatched} on success and {@code command_result} ({@code invalid} /
 * {@code unroutable}) on failure (DP-E).
 */
@DisplayName("StandardCommandDispatchService (M7.2a-2)")
class StandardCommandDispatchServiceTest {

    private final EntityId entityId = AutomationTestSupport.entityId();
    private final DeviceId deviceId = AutomationTestSupport.deviceId();
    private final IntegrationId integrationId = AutomationTestSupport.integrationId();
    private final EventId commandEventId = AutomationTestSupport.eventId();

    private AutomationTestSupport.RecordingEventPublisher publisher;
    private StandardCommandDispatchService dispatch;

    @BeforeEach
    void setUp() {
        Entity entity = AutomationTestSupport.entityWith(entityId, deviceId,
                StandardCapabilities.onOff());
        Device device = AutomationTestSupport.device(deviceId, integrationId);
        publisher = new AutomationTestSupport.RecordingEventPublisher();
        dispatch = new StandardCommandDispatchService(
                new AutomationTestSupport.StubEntityRegistry(List.of(entity)),
                new AutomationTestSupport.MapDeviceRegistry(List.of(device)),
                new StandardCommandValidator(
                        new AutomationTestSupport.StubEntityRegistry(List.of(entity))),
                publisher);
    }

    @Test
    @DisplayName("a routable, valid command emits command_dispatched with the integration id")
    void valid_emitsDispatched() {
        dispatch.dispatch(commandEventId, entityId, "turn_on", Map.of());

        List<EventEnvelope> dispatched = publisher.ofType(EventTypes.COMMAND_DISPATCHED);
        assertThat(dispatched).hasSize(1);
        CommandDispatchedEvent payload = (CommandDispatchedEvent) dispatched.get(0).payload();
        assertThat(payload.targetEntityRef()).isEqualTo(entityId.value());
        assertThat(payload.integrationId()).isEqualTo(integrationId.value());
        assertThat(publisher.ofType(EventTypes.COMMAND_RESULT)).isEmpty();
    }

    @Test
    @DisplayName("an unsupported command emits command_result(invalid)")
    void invalidCommand_emitsResultInvalid() {
        dispatch.dispatch(commandEventId, entityId, "frobnicate", Map.of());

        List<EventEnvelope> results = publisher.ofType(EventTypes.COMMAND_RESULT);
        assertThat(results).hasSize(1);
        CommandResultEvent payload = (CommandResultEvent) results.get(0).payload();
        assertThat(payload.outcome()).isEqualTo("invalid");
        assertThat(payload.commandType()).isEqualTo("frobnicate");
        assertThat(publisher.ofType(EventTypes.COMMAND_DISPATCHED)).isEmpty();
    }

    @Test
    @DisplayName("an entity that routes to no integration emits command_result(unroutable)")
    void unroutableEntity_emitsResultUnroutable() {
        EntityId unknown = AutomationTestSupport.entityId();

        dispatch.dispatch(commandEventId, unknown, "turn_on", Map.of());

        List<EventEnvelope> results = publisher.ofType(EventTypes.COMMAND_RESULT);
        assertThat(results).hasSize(1);
        CommandResultEvent payload = (CommandResultEvent) results.get(0).payload();
        assertThat(payload.outcome()).isEqualTo("unroutable");
        assertThat(publisher.ofType(EventTypes.COMMAND_DISPATCHED)).isEmpty();
    }
}
