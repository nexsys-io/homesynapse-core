/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRole;
import com.homesynapse.device.EntityType;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.event.AutomationInvokedEvent;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * The M7.4b composition-root producer-pipeline test — boots the <em>real</em>
 * {@link HomeSynapseCore} to LIVE with a seeded command automation, seeds a device/entity routed
 * to an integration, fires the trigger, and asserts the run pipeline is live end-to-end up to
 * dispatch: a matched trigger drives a Run whose action emits exactly one {@code command_issued},
 * and the co-located {@code command_dispatch_service} (M7.4a) routes it to exactly one
 * {@code command_dispatched}.
 *
 * <p>Time is injected via {@code Clock.fixed} (§4c — for determinism; lifecycle test code is
 * whitelisted for direct time access). The async run + dispatch hops are awaited by polling the
 * durable event store (real-time sleeps, clock-independent).</p>
 */
@DisplayName("RunPipelineWiringTest -- composition-root producer pipeline (M7.4b)")
final class RunPipelineWiringTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private static final String AUTOMATION_SLUG = "producer-pipeline";
    private static final String COMMAND = "turn_on";
    private static final String ENTITY_ULID = "01J" + "B".repeat(23);
    private static final String DEVICE_ULID = "01J" + "C".repeat(23);
    private static final String INTEGRATION_ULID = "01J" + "D".repeat(23);

    private final EntityId targetEntityId = EntityId.parse(ENTITY_ULID);
    private final DeviceId deviceId = DeviceId.parse(DEVICE_ULID);
    private final IntegrationId integrationId = IntegrationId.parse(INTEGRATION_ULID);

    private HomeSynapseCore core;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RunPipelineWiringTest() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    private HomeSynapseCore newCore(Path tempDir) {
        return new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                FIXED_CLOCK,
                TEST_HOME_ID);
    }

    private void writeConfig(Path tempDir) throws Exception {
        // A command automation: a manual trigger and one command action targeting the seeded
        // entity by entity_ref (a DirectRefSelector — entity_ref is NOT existence-validated at
        // load, so the automation loads before the entity is seeded post-start).
        String yaml = ("""
                automation:
                  automations:
                    - name: "producer pipeline automation"
                      slug: "%s"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            entity_ref: "%s"
                          command: %s
                """).formatted(AUTOMATION_SLUG, ENTITY_ULID, COMMAND);
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    /** Seeds the target entity (with the on_off capability) and its integration-routed device. */
    private void seedDeviceAndEntity() {
        Capability onOff = StandardCapabilities.onOff();
        CapabilityInstance instance = new CapabilityInstance(
                onOff.capabilityId(), onOff.version(), onOff.namespace(), 0,
                onOff.attributeSchemas(), onOff.commandDefinitions(), onOff.confirmationPolicy());
        Entity entity = new Entity(targetEntityId, "producer-light", EntityType.LIGHT,
                "Producer Light", deviceId, 0, null, true, List.of(), List.of(instance),
                EntityRole.PRIMARY, FIXED_INSTANT);
        Device device = new Device(deviceId, "producer-device", "Producer Device", "Acme", "Model",
                null, null, null, integrationId, null, null, List.of(), Set.of(), FIXED_INSTANT);
        core.deviceRegistry().createDevice(device);
        core.entityRegistry().createEntity(entity);
    }

    /** Fires the seeded automation by publishing an {@code automation_invoked} on its subject. */
    private void fireManualTrigger() throws Exception {
        AutomationId automationId = core.automationRegistry().getBySlug(AUTOMATION_SLUG)
                .orElseThrow(() -> new AssertionError("seeded automation not loaded"))
                .automationId();
        core.eventPublisher().publishRoot(new EventDraft(
                EventTypes.AUTOMATION_INVOKED, 1, null, SubjectRef.automation(automationId),
                EventPriority.NORMAL, EventOrigin.AUTOMATION, new AutomationInvokedEvent("test"),
                null, null));
    }

    /** Polls the durable event store until {@code eventType} appears (async run + dispatch hops). */
    private List<EventEnvelope> awaitEventsOfType(String eventType) {
        for (int poll = 0; poll < 250; poll++) {       // ~5s at 20ms
            EventPage page = core.eventStore().readFrom(0L, 1000);
            List<EventEnvelope> matches = page.events().stream()
                    .filter(envelope -> envelope.eventType().equals(eventType))
                    .toList();
            if (!matches.isEmpty()) {
                return matches;
            }
            sleepBriefly();
        }
        return List.of();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting pipeline events", ex);
        }
    }

    @Test
    @DisplayName("a seeded automation firing its trigger emits exactly one command_issued and one "
            + "command_dispatched (the producer pipeline is live up to dispatch)")
    void seededAutomation_firesTrigger_emitsCommandIssuedAndDispatched(@TempDir Path tempDir)
            throws Exception {
        writeConfig(tempDir);
        core = newCore(tempDir);
        core.start();
        seedDeviceAndEntity();

        fireManualTrigger();

        // The dispatch (command_dispatched) is the last hop — await it, then assert both events.
        List<EventEnvelope> dispatched = awaitEventsOfType(EventTypes.COMMAND_DISPATCHED);
        assertThat(dispatched).hasSize(1);
        List<EventEnvelope> issued = core.eventStore().readFrom(0L, 1000).events().stream()
                .filter(envelope -> envelope.eventType().equals(EventTypes.COMMAND_ISSUED))
                .toList();
        assertThat(issued).hasSize(1);

        CommandIssuedEvent issuedPayload = (CommandIssuedEvent) issued.get(0).payload();
        assertThat(issuedPayload.targetEntityRef()).isEqualTo(targetEntityId.value());
        assertThat(issuedPayload.commandType()).isEqualTo(COMMAND);
        assertThat(issuedPayload.confirmationTimeoutMs()).isEqualTo(5000);   // on_off turn_on default
        assertThat(issuedPayload.idempotencyClass()).isEqualTo(CommandIdempotency.IDEMPOTENT);

        CommandDispatchedEvent dispatchedPayload = (CommandDispatchedEvent) dispatched.get(0).payload();
        assertThat(dispatchedPayload.targetEntityRef()).isEqualTo(targetEntityId.value());
        assertThat(dispatchedPayload.integrationId()).isEqualTo(integrationId.value());
    }

    @Test
    @DisplayName("the run pipeline tears down cleanly on shutdown (no leaked Run VT / @TempDir handle)")
    void runVTs_tornDownCleanly_onShutdown(@TempDir Path tempDir) throws Exception {
        writeConfig(tempDir);
        core = newCore(tempDir);
        core.start();
        seedDeviceAndEntity();
        fireManualTrigger();
        // Let the Run complete (command dispatched) so there is no in-flight VT at shutdown.
        assertThat(awaitEventsOfType(EventTypes.COMMAND_DISPATCHED)).hasSize(1);

        core.shutdown("test");

        // STOPPED + a clean @TempDir teardown (the M7.3 Windows-leak fingerprint would surface as
        // an IOException deleting @TempDir after this method if a Run VT / handle leaked).
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
    }
}
