/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.Entity;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.integration.IntegrationEvents;
import com.homesynapse.integration.runtime.IntegrationIds;
import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;
import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;
import com.homesynapse.persistence.PersistenceFactory;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * M9.4a §5.2 — replay purity with the REAL zigbee {@code CommandHandler} (prove-list
 * seam 4, INV-ES-09): a seeded, joinable, ROUTABLE zigbee command lifecycle replayed
 * on boot must produce ZERO frames at the scripted NCP — ghost commands to real
 * devices are the catastrophic class. The router's replay guard is the enforcing
 * seam; this gate ASSERTS it holds through the new real handler. The LIVE positive
 * control (real adoption → manual trigger → one real On frame) proves
 * non-vacuousness at the frame level. Follows {@link RunPipelineReplaySafetyTest}'s
 * pattern exactly (standalone-seeded log → checkpoint-0 boot → await LIVE);
 * {@code RunPipelineReplaySafetyTest} itself stays untouched-green.
 */
@DisplayName("ZigbeeReplaySafetyIT — zero FakeNcp frames during replay; the LIVE control fires (INV-ES-09, M9.4a §5.2)")
final class ZigbeeReplaySafetyIT {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAC"));
    private static final String SEED_ENTITY_ULID = "01J" + "H".repeat(23);

    private final EntityId seedEntityId = EntityId.parse(SEED_ENTITY_ULID);
    private final IntegrationId zigbeeId =
            IntegrationIds.deriveStable(ZigbeeIntegrationFactory.INTEGRATION_TYPE);

    private TestClock clock;
    private ZigbeeHardwareFreeRig rig;
    private HomeSynapseCore core;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ZigbeeReplaySafetyIT() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    @Test
    @DisplayName("a seeded zigbee command lifecycle replays with ZERO frames at the NCP and zero "
            + "new results; the LIVE control then dispatches ONE real frame through the same handler")
    void seededZigbeeLifecycle_replaysFrameless_liveControlFires(@TempDir Path tempDir)
            throws Exception {
        clock = TestClock.createDefault();
        writeConfig(tempDir);
        seedPriorZigbeeRun(tempDir.resolve("homesynapse-events.db"));
        rig = new ZigbeeHardwareFreeRig(clock, () -> core.deviceRegistry(),
                () -> core.registryProjection(),
                tempDir.resolve("zigbee"));
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                HomeSynapseConfig.testing(),
                clock,
                TEST_HOME_ID,
                null,
                List.of(rig.factory()));
        core.start();
        awaitRuntimeSubscribersLive();

        // INV-ES-09: the seeded command_dispatched is joinable (causation-chained)
        // AND routable (the zigbee integration's derived id) — a broken
        // acts-in-REPLAY router would invoke the REAL ZigbeeCommandHandler, which
        // would either reach the NCP or publish an unroutable result. BOTH seams
        // must stay silent across the replay window.
        assertThat(rig.sentZclFrames())
                .as("ghost commands to real devices — the catastrophic class")
                .isEmpty();
        assertThat(countEventsOfType(EventTypes.COMMAND_RESULT)).isZero();
        assertThat(countEventsOfType(EventTypes.COMMAND_DISPATCHED)).isEqualTo(1L);
        assertThat(core.pendingCommandLedger().pendingCount()).isZero();

        // The LIVE positive control: REAL adoption over the scripted NCP, then one
        // manual trigger — the SAME handler dispatches ONE real frame.
        awaitTrue(rig::sessionStarted, "the EZSP session over the scripted NCP");
        rig.announce(ZigbeeHardwareFreeRig.HUE_IEEE);
        rig.deliverAndCycle();
        EntityId hueEntity = rig.adopt(ZigbeeHardwareFreeRig.HUE_IEEE)
                .get(ZigbeeHardwareFreeRig.HUE_ENDPOINT);
        label(hueEntity, "hero-light");
        fireManual("live-zigbee-control");

        awaitTrue(() -> rig.sentZclFrames().stream()
                        .anyMatch(frame -> frame.clusterId() == 0x0006
                                && frame.commandId() == 0x01),
                "the LIVE control On frame (non-vacuousness)");
        assertThat(rig.sentZclFrames()).hasSize(1);
    }

    // ── harness ─────────────────────────────────────────────────────────────

    private void writeConfig(Path tempDir) throws Exception {
        String yaml = """
                automation:
                  automations:
                    - name: "live zigbee control"
                      slug: "live-zigbee-control"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: turn_on
                """;
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    /**
     * Seeds a COMPLETE prior zigbee command run through a standalone
     * {@link PersistenceFactory} (no subscribers — checkpoint 0 on boot): issued →
     * dispatched (chained + routable to the zigbee id) → reported → confirmed.
     */
    private void seedPriorZigbeeRun(Path dbPath) throws Exception {
        List<Class<? extends DomainEvent>> eventClasses = Stream.of(
                        EventTypes.CORE_PRODUCTION_EVENT_CLASSES,
                        IntegrationEvents.LIFECYCLE_EVENT_CLASSES,
                        IntegrationEvents.CAPABILITY_EVENT_CLASSES)
                .flatMap(List::stream)
                .toList();
        try (PersistenceFactory seed = PersistenceFactory.start(
                dbPath, HomeSynapseConfig.testing().persistence(), clock, TEST_HOME_ID,
                eventClasses, null)) {
            EventPublisher publisher = seed.eventPublisher();
            EventEnvelope issued = publisher.publishRoot(new EventDraft(
                    EventTypes.COMMAND_ISSUED, 1, clock.instant(),
                    SubjectRef.entity(seedEntityId), EventPriority.NORMAL,
                    EventOrigin.AUTOMATION,
                    new CommandIssuedEvent(seedEntityId.value(), "turn_on", "{}", 30_000,
                            CommandIdempotency.NOT_IDEMPOTENT),
                    null, null));
            publisher.publish(new EventDraft(
                    EventTypes.COMMAND_DISPATCHED, 1, clock.instant(),
                    SubjectRef.entity(seedEntityId), EventPriority.DIAGNOSTIC,
                    EventOrigin.AUTOMATION,
                    new CommandDispatchedEvent(seedEntityId.value(), zigbeeId.value(), "{}"),
                    null, null),
                    CausalContext.chain(issued.causalContext().correlationId(),
                            issued.eventId().value()));
            EventEnvelope reported = publisher.publishRoot(new EventDraft(
                    EventTypes.STATE_REPORTED, 1, clock.instant(),
                    SubjectRef.entity(seedEntityId), EventPriority.DIAGNOSTIC,
                    EventOrigin.DEVICE_AUTONOMOUS,
                    new StateReportedEvent("on", "true", null, null, null),
                    null, null));
            publisher.publishRoot(new EventDraft(
                    EventTypes.STATE_CONFIRMED, 1, clock.instant(),
                    SubjectRef.entity(seedEntityId), EventPriority.NORMAL,
                    EventOrigin.SYSTEM,
                    new StateConfirmedEvent(issued.eventId(), reported.eventId(),
                            "on", "true", "true", "exact"),
                    null, null));
        }
    }

    private void label(EntityId entityId, String labelValue) {
        Entity entity = core.entityRegistry().getEntity(entityId);
        core.entityRegistry().updateEntity(new Entity(entity.entityId(),
                entity.entitySlug(), entity.entityType(), entity.displayName(),
                entity.deviceId(), entity.endpointIndex(), entity.areaId(),
                entity.enabled(), List.of(labelValue), entity.capabilities(),
                entity.entityRole(), entity.createdAt()));
    }

    private void fireManual(String slug) throws Exception {
        AutomationId automationId = core.automationRegistry().getBySlug(slug)
                .orElseThrow(() -> new AssertionError("automation '" + slug + "' not loaded"))
                .automationId();
        core.eventPublisher().publishRoot(new EventDraft(
                EventTypes.AUTOMATION_INVOKED, 1, null, SubjectRef.automation(automationId),
                EventPriority.NORMAL, EventOrigin.AUTOMATION,
                new com.homesynapse.event.AutomationInvokedEvent("zigbee-replay-control"),
                null, null));
    }

    private void awaitRuntimeSubscribersLive() {
        for (int poll = 0; poll < 250; poll++) {
            if (subscriberMode("automation_engine") == SubscriberMode.LIVE
                    && subscriberMode("command_dispatch_service") == SubscriberMode.LIVE
                    && subscriberMode("pending_command_ledger") == SubscriberMode.LIVE
                    && subscriberMode("integration_supervisor") == SubscriberMode.LIVE) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("runtime subscribers did not reach LIVE within ~5s");
    }

    private SubscriberMode subscriberMode(String subscriberId) {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> subscriberId.equals(snapshot.subscriberId()))
                .map(SubscriberSnapshot::mode)
                .findFirst()
                .orElse(SubscriberMode.COLD);
    }

    private long countEventsOfType(String eventType) {
        return core.eventStore().readFrom(0L, 2000).events().stream()
                .filter(envelope -> envelope.eventType().equals(eventType)).count();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String what) {
        for (int poll = 0; poll < 500; poll++) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("timed out awaiting " + what);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the replay boundary", ex);
        }
    }
}
