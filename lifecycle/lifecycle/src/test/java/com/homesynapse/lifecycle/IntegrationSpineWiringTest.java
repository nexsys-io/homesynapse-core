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
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.integration.CommandEnvelope;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationHealthChanged;
import com.homesynapse.integration.runtime.IntegrationIds;
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
import java.util.function.BooleanSupplier;

/**
 * The M9.1 composition-root E2E gate (T18–T20): the integration spine proven
 * on the <em>real</em> {@link HomeSynapseCore}. Boots the 7-arg constructor
 * with the {@link RecordingIntegrationFactory}, seeds the command automation
 * plus a device routed to the fake's {@link IntegrationIds#deriveStable
 * derived} id, fires the trigger, and asserts the full spine — trigger &rarr;
 * run &rarr; {@code command_issued} &rarr; {@code command_dispatched} &rarr;
 * router join &rarr; {@code CommandHandler.handle(CommandEnvelope)} — lands
 * exactly one envelope with the DP-2 field contract on the fake's recorder.
 *
 * <p>Follows the {@code RunPipelineConfirmWiringTest} harness pattern
 * (registry seeding, automation seeding, store-polling awaits). Time is
 * injected via {@code Clock.fixed} (§4c); the awaits are real-time polls,
 * clock-independent.</p>
 */
@DisplayName("IntegrationSpineWiringTest -- composition-root integration spine (M9.1)")
final class IntegrationSpineWiringTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private static final String AUTOMATION_SLUG = "spine-pipeline";
    private static final String COMMAND = "turn_on";
    private static final String ENTITY_ULID = "01J" + "B".repeat(23);
    private static final String DEVICE_ULID = "01J" + "C".repeat(23);

    private final EntityId targetEntityId = EntityId.parse(ENTITY_ULID);
    private final DeviceId deviceId = DeviceId.parse(DEVICE_ULID);
    private final IntegrationId fakeIntegrationId =
            IntegrationIds.deriveStable(RecordingIntegrationFactory.INTEGRATION_TYPE);

    private HomeSynapseCore core;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    IntegrationSpineWiringTest() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T18: a seeded automation firing routes ONE CommandEnvelope to the recording "
            + "fake with the exact DP-2 contract (entityRef/commandName/commandEventId/"
            + "correlationId/integrationId)")
    void firedAutomation_routesExactlyOneEnvelopeToTheFake(@TempDir Path tempDir)
            throws Exception {
        RecordingIntegrationFactory fake = RecordingIntegrationFactory.recording();
        writeConfig(tempDir);
        core = newCore(tempDir, List.of(fake));
        core.start();
        assertThat(core.integrationSupervisor()).isNotNull();
        awaitRouterLive();
        seedDeviceAndEntity();

        fireManualTrigger();

        List<EventEnvelope> issuedEvents = awaitEventsOfType(EventTypes.COMMAND_ISSUED);
        assertThat(issuedEvents).hasSize(1);
        EventEnvelope issued = issuedEvents.get(0);
        awaitCondition(() -> fake.recordedCommands().size() == 1,
                "the fake's recorder never received the routed CommandEnvelope");

        CommandEnvelope command = fake.recordedCommands().get(0);
        assertThat(command.entityRef()).isEqualTo(targetEntityId);
        assertThat(command.commandName()).isEqualTo(COMMAND);
        assertThat(command.parameters()).isEmpty();     // the automation carries no parameters
        assertThat(command.commandEventId()).isEqualTo(issued.eventId().value());
        assertThat(command.correlationId()).isEqualTo(issued.causalContext().correlationId());
        assertThat(command.integrationId()).isEqualTo(fakeIntegrationId);

        // Exactly one — the router joins each dispatched exactly once (AMD-90-INV-01
        // composes: no engine or router retry).
        sleepBriefly();
        assertThat(fake.recordedCommands()).hasSize(1);
    }

    @Test
    @DisplayName("T19: the 5-arg constructor (empty factories) skips Phase 6 entirely — no "
            + "supervisor, no integration_supervisor subscriber, pre-M9.1 behavior intact")
    void emptyFactories_skipPhaseSix(@TempDir Path tempDir) throws Exception {
        writeConfig(tempDir);
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                FIXED_CLOCK,
                TEST_HOME_ID);
        core.start();

        assertThat(core.integrationSupervisor()).isNull();
        assertThat(core.eventBus().subscribers())
                .extracting("subscriberId")
                .doesNotContain("integration_supervisor");
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.RUNNING);

        core.stop();
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
    }

    @Test
    @DisplayName("T20: a fake failing at initialize alongside the recording fake — the healthy "
            + "one still routes; the failing one is FAILED with a CRITICAL health event "
            + "(INV-RF-01 at the composition root)")
    void failingSibling_doesNotAffectTheHealthyFake(@TempDir Path tempDir) throws Exception {
        RecordingIntegrationFactory failing =
                RecordingIntegrationFactory.failingAtInitialize("failing-fake");
        RecordingIntegrationFactory fake = RecordingIntegrationFactory.recording();
        writeConfig(tempDir);
        core = newCore(tempDir, List.of(failing, fake));
        core.start();      // boot continues despite the failing integration (INV-RF-01)
        awaitRouterLive();
        seedDeviceAndEntity();

        IntegrationId failingId = IntegrationIds.deriveStable("failing-fake");
        assertThat(core.integrationSupervisor().health(failingId).orElseThrow().state())
                .isEqualTo(HealthState.FAILED);
        assertThat(core.integrationSupervisor().isRunning(failingId)).isFalse();
        assertThat(core.integrationSupervisor().isRunning(fakeIntegrationId)).isTrue();

        List<EventEnvelope> healthChanged =
                awaitEventsOfType(EventTypes.INTEGRATION_HEALTH_CHANGED);
        assertThat(healthChanged).hasSize(1);
        assertThat(healthChanged.get(0).priority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(((IntegrationHealthChanged) healthChanged.get(0).payload()).newState())
                .isEqualTo(HealthState.FAILED);

        fireManualTrigger();
        awaitCondition(() -> fake.recordedCommands().size() == 1,
                "the healthy fake stopped routing because a sibling failed (INV-RF-01 broken)");
        assertThat(failing.recordedCommands()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness (the RunPipelineConfirmWiringTest pattern)
    // ════════════════════════════════════════════════════════════════════════

    private HomeSynapseCore newCore(Path tempDir,
                                    List<com.homesynapse.integration.IntegrationFactory> factories) {
        return new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                FIXED_CLOCK,
                TEST_HOME_ID,
                null,
                factories);
    }

    private void writeConfig(Path tempDir) throws Exception {
        String yaml = ("""
                automation:
                  automations:
                    - name: "integration spine automation"
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

    /**
     * Seeds the target entity (on_off capability) and its device routed to the FAKE's
     * derived integration id — the row that makes {@code command_dispatched} carry the
     * fake's id so the router resolves the recording adapter.
     */
    private void seedDeviceAndEntity() {
        Capability onOff = StandardCapabilities.onOff();
        CapabilityInstance instance = new CapabilityInstance(
                onOff.capabilityId(), onOff.version(), onOff.namespace(), 0,
                onOff.attributeSchemas(), onOff.commandDefinitions(), onOff.confirmationPolicy());
        Entity entity = new Entity(targetEntityId, "spine-light", EntityType.LIGHT,
                "Spine Light", deviceId, 0, null, true, List.of(), List.of(instance),
                EntityRole.PRIMARY, FIXED_INSTANT);
        Device device = new Device(deviceId, "spine-device", "Spine Device", "Acme", "Model",
                null, null, null, fakeIntegrationId, null, null, List.of(), Set.of(),
                FIXED_INSTANT);
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

    /**
     * Blocks until the {@code integration_supervisor} routing subscriber reports
     * {@code LIVE} — the router dispatches only LIVE {@code command_dispatched}
     * events (INV-ES-09), so firing the trigger before the boot catch-up flip
     * completes would legitimately (and per contract) not route. The same
     * boundary discipline as {@code RunPipelineReplaySafetyTest}.
     */
    private void awaitRouterLive() {
        awaitCondition(() -> core.eventBus().subscribers().stream()
                        .anyMatch(snapshot -> "integration_supervisor".equals(snapshot.subscriberId())
                                && snapshot.mode() == com.homesynapse.event.bus.SubscriberMode.LIVE),
                "the integration_supervisor subscriber did not reach LIVE within ~5s");
    }

    /** Polls the durable event store until {@code eventType} appears (async pipeline hops). */
    private List<EventEnvelope> awaitEventsOfType(String eventType) {
        for (int poll = 0; poll < 250; poll++) {       // ~5s at 20ms
            List<EventEnvelope> matches = core.eventStore().readFrom(0L, 1000).events().stream()
                    .filter(envelope -> envelope.eventType().equals(eventType))
                    .toList();
            if (!matches.isEmpty()) {
                return matches;
            }
            sleepBriefly();
        }
        return List.of();
    }

    private static void awaitCondition(BooleanSupplier condition, String failure) {
        for (int poll = 0; poll < 250; poll++) {       // ~5s at 20ms
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError(failure);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting the spine", ex);
        }
    }
}
