/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.integration.runtime.IntegrationIds;
import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;
import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * M9.4b §7.3 — the supervisor-restart honesty leg: a {@code restartIntegration}
 * mid-confirmation-window must never double-actuate (the adapter never re-sends
 * — AMD-90-INV-01's no-autonomous-retry extends across restarts), the in-flight
 * window must resolve HONESTLY (timeout, never a phantom re-fire), and the
 * post-restart re-announce → re-link path must still carry the adoption tuning
 * (DP-a pin 2, post-DUR/AMD-99: the tuning PERSISTS in the projection-backed
 * registry — relink no longer re-installs anything; the measured 15 s Hue CT
 * window is visible on the NEXT {@code command_issued.confirmationTimeoutMs}).
 */
@DisplayName("RestartHonestyIT — restartIntegration: no double-actuation, honest window, "
        + "pin-2 re-install (M9.4b §7.3)")
final class RestartHonestyIT {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAC"));

    private TestClock clock;
    private ZigbeeHardwareFreeRig rig;
    private HomeSynapseCore core;
    private EntityId hueEntity;
    /** Captures the adoption slice's relink INFO line (the re-link barrier). */
    private ListAppender<ILoggingEvent> sliceLogCapture;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RestartHonestyIT() {
    }

    @AfterEach
    void tearDown() {
        if (sliceLogCapture != null) {
            sliceLogger().detachAppender(sliceLogCapture);
        }
        if (core != null) {
            core.stop();
        }
    }

    @Test
    @DisplayName("restart mid-window: zero double-actuation, honest timeout, and the "
            + "re-linked device carries the tuned window on the next command")
    void restartMidWindow_honest(@TempDir Path tempDir) throws Exception {
        boot(tempDir);

        // A CONFIRMABLE command opens a window; its frame reaches the NCP once.
        fireManual("hero-turn-on");
        awaitEnvelope(EventTypes.COMMAND_ISSUED,
                event -> commandType(event).equals("turn_on"), "command_issued(turn_on)");
        awaitTrue(() -> countFrames(0x0006, 0x01) == 1, "the single On frame");

        // Restart the integration mid-window (the supervisor's planned restart).
        core.integrationSupervisor()
                .restartIntegration(IntegrationIds.deriveStable(
                        ZigbeeIntegrationFactory.INTEGRATION_TYPE))
                .get(30, TimeUnit.SECONDS);
        awaitTrue(rig::sessionStarted, "the restarted EZSP session");

        // No double-actuation: the restarted adapter never re-fires the command.
        assertThat(countFrames(0x0006, 0x01))
                .as("AMD-90-INV-01 across restarts: exactly one actuation")
                .isEqualTo(1);

        // The in-flight window resolves HONESTLY: no report ever arrives, the
        // clock steps past the 5 s window, the ledger times out — never a
        // phantom re-fire, never a fabricated confirm.
        rig.clock().advance(Duration.ofSeconds(6));
        awaitTrue(() -> countEventsOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT) == 1L,
                "the honest confirmation timeout across the restart");
        assertThat(countEventsOfType(EventTypes.STATE_CONFIRMED)).isZero();

        // Post-restart re-announce → re-link (IEEE match, LINKED — no re-adoption).
        // DP-a pin 2, post-DUR: the tuning persists in the projection-backed
        // registry on the SAME entity — relink rebuilds maps only (AMD-99 DP-4).
        // WU-AVAIL-SEED DP-3 STOP retired the relink availability emission, so
        // the barrier is the preserved relink LOG line: one from the restart's
        // DP-6 rehydration + one from this announce-driven LINKED arm.
        rig.announce(ZigbeeHardwareFreeRig.HUE_IEEE);
        rig.deliverAndCycle();
        awaitTrue(() -> relinkLogLines() >= 2L,
                "the re-link log line (rehydration + re-announce)");
        assertThat(countEventsOfType(EventTypes.DEVICE_ADOPTED))
                .as("re-pairing re-links; it never re-adopts")
                .isEqualTo(1L);

        // The beat-2 binding pin, restart-leg-proven: the NEXT command_issued
        // carries the adoption-installed 15 s window (P17 precedence, zero
        // executor change).
        fireManual("ct-4550");
        EventEnvelope tuned = awaitEnvelope(EventTypes.COMMAND_ISSUED,
                event -> commandType(event).equals("set_color_temperature"),
                "the post-restart CT command");
        assertThat(((CommandIssuedEvent) tuned.payload()).confirmationTimeoutMs())
                .isEqualTo(15000);
    }

    // ── harness (the HeroLoopHardwareFreeIT boot shape, slimmed) ────────────

    private void boot(Path tempDir) throws Exception {
        clock = TestClock.createDefault();
        sliceLogCapture = new ListAppender<>();
        sliceLogCapture.start();
        sliceLogger().addAppender(sliceLogCapture);
        writeConfig(tempDir);
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
        awaitTrue(rig::sessionStarted, "the EZSP session over the scripted NCP");

        rig.announce(ZigbeeHardwareFreeRig.HUE_IEEE);
        rig.deliverAndCycle();
        hueEntity = rig.adopt(ZigbeeHardwareFreeRig.HUE_IEEE)
                .get(ZigbeeHardwareFreeRig.HUE_ENDPOINT);
        awaitRegistryProjectionCaughtUp();
        label(hueEntity, "hero-light");
    }

    private void label(EntityId entityId, String labelValue) {
        var entity = core.entityRegistry().getEntity(entityId);
        core.entityRegistry().updateEntity(new com.homesynapse.device.Entity(
                entity.entityId(), entity.entitySlug(), entity.entityType(),
                entity.displayName(), entity.deviceId(), entity.endpointIndex(),
                entity.areaId(), entity.enabled(), List.of(labelValue),
                entity.capabilities(), entity.entityRole(), entity.createdAt()));
    }

    private void writeConfig(Path tempDir) throws Exception {
        String yaml = """
                automation:
                  automations:
                    - name: "hero turn on"
                      slug: "hero-turn-on"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: turn_on
                    - name: "ct 4550"
                      slug: "ct-4550"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: set_color_temperature
                          parameters:
                            kelvin: 4550
                """;
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    private void fireManual(String slug) throws Exception {
        AutomationId automationId = core.automationRegistry().getBySlug(slug)
                .orElseThrow(() -> new AssertionError("automation '" + slug + "' not loaded"))
                .automationId();
        core.eventPublisher().publishRoot(new EventDraft(
                EventTypes.AUTOMATION_INVOKED, 1, null, SubjectRef.automation(automationId),
                EventPriority.NORMAL, EventOrigin.AUTOMATION,
                new com.homesynapse.event.AutomationInvokedEvent("restart-honesty"),
                null, null));
    }

    // ── awaits + store reads (real-time polls; clock-independent) ───────────

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

    /**
     * M9.5-DURc — the registry-projection checkpoint barrier. {@code adopt()}
     * applies the registration facts synchronously AND publishes them; the
     * {@code registry_projection} subscriber re-applies each self-delivery on
     * its own virtual thread (idempotent only when state is EQUAL — different
     * state ⇒ replace). {@code label()} mutates the registry directly
     * (log-invisible), so a self-delivery landing after it lawfully replaces
     * the labeled entity with its as-adopted state and the label-targeted
     * automation resolves zero entities. Awaiting the subscriber's checkpoint
     * reaching the LAST registration fact closes the window.
     *
     * <p>The await target is the max {@code globalPosition} over the
     * {@code device_registered}/{@code entity_registered} envelopes — NOT
     * {@code EventStore.latestPosition()}: the subscriber is type-filtered
     * ({@link RegistryProjectionSubscriber#subscriptionFilter()}) and the bus
     * advances a subscriber checkpoint only on MATCHING deliveries, while
     * {@code adopt()} publishes the non-matching {@code device_adopted} LAST
     * — a store-head target is unreachable by construction.</p>
     */
    private void awaitRegistryProjectionCaughtUp() {
        long lastRegistrationFact = events().stream()
                .filter(event -> event.eventType().equals(EventTypes.DEVICE_REGISTERED)
                        || event.eventType().equals(EventTypes.ENTITY_REGISTERED))
                .mapToLong(EventEnvelope::globalPosition)
                .max()
                .orElseThrow(() -> new AssertionError(
                        "no registration facts in the log after adopt()"));
        awaitTrue(() -> registryProjectionCheckpoint() >= lastRegistrationFact,
                "the registry projection consuming the adoption events");
    }

    private long registryProjectionCheckpoint() {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> RegistryProjectionSubscriber.SUBSCRIBER_ID
                        .equals(snapshot.subscriberId()))
                .mapToLong(SubscriberSnapshot::checkpoint)
                .findFirst()
                .orElse(0L);
    }

    private List<EventEnvelope> events() {
        return core.eventStore().readFrom(0L, 2000).events();
    }

    private long countEventsOfType(String eventType) {
        return events().stream()
                .filter(envelope -> envelope.eventType().equals(eventType)).count();
    }

    /**
     * The adoption slice is package-private in the zigbee module — the logger
     * is addressed by NAME (logback loggers are name-keyed).
     */
    private static Logger sliceLogger() {
        return (Logger) org.slf4j.LoggerFactory.getLogger(
                "com.homesynapse.integration.zigbee.ZigbeeAdoptionSlice");
    }

    /** The captured {@code zigbee.device_relinked} INFO lines so far. */
    private long relinkLogLines() {
        return sliceLogCapture.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("zigbee.device_relinked"))
                .count();
    }

    private static String commandType(EventEnvelope envelope) {
        return envelope.payload() instanceof CommandIssuedEvent issued
                ? issued.commandType() : "";
    }

    private long countFrames(int clusterId, int commandId) {
        return rig.sentZclFrames().stream()
                .filter(frame -> frame.clusterId() == clusterId
                        && frame.commandId() == commandId)
                .count();
    }

    private EventEnvelope awaitEnvelope(String eventType, Predicate<EventEnvelope> matcher,
            String what) {
        for (int poll = 0; poll < 500; poll++) {
            Optional<EventEnvelope> found = events().stream()
                    .filter(event -> event.eventType().equals(eventType))
                    .filter(matcher)
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleepBriefly();
        }
        throw new AssertionError("timed out awaiting " + what);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
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
            throw new IllegalStateException("interrupted awaiting restart honesty", ex);
        }
    }
}
