/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.Entity;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
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
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * M9.4a §5.1 — THE done-when gate: the first time the thesis runs with the real
 * halves together, hardware-free. SNZB motion fixture frames → REAL ingestion →
 * {@code occupancy.occupied} → the REAL automation engine → turn-on dispatch → the
 * REAL adapter command path over the scripted NCP → the Hue confirmation window →
 * an honest {@code CONFIRMED} with the causal chain intact. Plus the supersession
 * pair (Doc 08 §3.6 caveat 3 at the E2E level), the UNCONFIRMABLE immediate honest
 * verdict (AMD-97-INV-01), and the timeout-honesty leg. A mismatch anywhere is a
 * failing assertion, not a bench mystery.
 *
 * <p>Harness: the {@link RunPipelineReplaySafetyTest} composition-root pattern over
 * the {@link ZigbeeHardwareFreeRig} (zigbee testFixtures — the REAL adapter code
 * over a scripted NCP). Time is an injected {@link TestClock} shared by the core
 * and the rig (§4c); await loops are real-time polls (clock-independent).</p>
 */
@DisplayName("HeroLoopHardwareFreeIT — motion → occupancy.occupied → trigger → dispatch → FakeNcp → honest CONFIRMED (M9.4a §5.1)")
final class HeroLoopHardwareFreeIT {

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAB"));

    private TestClock clock;
    private ZigbeeHardwareFreeRig rig;
    private HomeSynapseCore core;
    private EntityId snzbEntity;
    private EntityId hueEntity;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    HeroLoopHardwareFreeIT() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // The hero loop (steps 1–4)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("the hero loop: a motion edge fires the automation, the REAL adapter dispatches "
            + "the On frame, the confirm report renders an honest state_confirmed with the causal "
            + "chain intact")
    void heroLoop_motionToHonestConfirmed(@TempDir Path tempDir) throws Exception {
        bootAndAdopt(tempDir);

        // Step 2 — the motion edge (false → true so the projection publishes a real
        // state_changed edge): the capability-key binding is occupancy.occupied,
        // NOT motion (asserted explicitly below).
        rig.reportOccupied(false);
        rig.deliverAndCycle();
        awaitTrue(() -> !reportedValues("occupied").isEmpty(),
                "the occupied=false baseline report");
        rig.reportOccupied(true);
        rig.deliverAndCycle();
        awaitTrue(() -> reportedValues("occupied").contains("true"),
                "the occupied=true motion edge");
        assertThat(events().stream()
                .filter(event -> event.eventType().equals(EventTypes.STATE_REPORTED))
                .map(EventEnvelope::payload)
                .map(payload -> ((StateReportedEvent) payload).attributeKey()))
                .as("the capability-key binding: occupancy.occupied, never 'motion'")
                .contains("occupied")
                .doesNotContain("motion");

        // Step 3 — the engine fires: real trigger → run → command_issued(turn_on) →
        // dispatch → router → the REAL zigbee handler → the scripted NCP receives
        // the byte-asserted OnOff frame.
        awaitTrue(() -> sentFrame(0x0006, 0x01).isPresent(),
                "the On frame reaching the scripted NCP",
                () -> newestReportedPosition("occupied", "true"));
        ZigbeeHardwareFreeRig.SentZcl onFrame = sentFrame(0x0006, 0x01).orElseThrow();
        assertThat(onFrame.networkAddress()).isEqualTo(ZigbeeHardwareFreeRig.HUE_NWK);
        assertThat(onFrame.destinationEndpoint())
                .isEqualTo(ZigbeeHardwareFreeRig.HUE_ENDPOINT);
        assertThat(onFrame.payload()).isEmpty();       // ZCL8 §3.8.2.3: On has no payload

        EventEnvelope issued = awaitEnvelope(EventTypes.COMMAND_ISSUED,
                event -> commandType(event).equals("turn_on"), "command_issued(turn_on)");

        // Step 4 — the confirmation: the scripted NCP reports on=true through the
        // REAL ingestion; the ledger renders the honest CONFIRMED.
        rig.reportOnOff(true);
        rig.deliverAndCycle();
        EventEnvelope confirmed = awaitEnvelope(EventTypes.STATE_CONFIRMED,
                event -> ((StateConfirmedEvent) event.payload()).commandEventId()
                        .equals(issued.eventId()),
                "state_confirmed for the turn_on command");

        // The causal chain: state_confirmed joins BOTH the command and the report;
        // the command's run correlation reaches the confirmation; the trigger's
        // cause is the occupied state_changed edge.
        StateConfirmedEvent confirmation = (StateConfirmedEvent) confirmed.payload();
        assertThat(confirmation.attributeKey()).isEqualTo("on");
        assertThat(confirmation.matchType()).isEqualTo("exact");
        EventEnvelope report = findByEventId(confirmation.reportEventId()).orElseThrow();
        assertThat(report.eventType()).isEqualTo(EventTypes.STATE_REPORTED);
        assertThat(((StateReportedEvent) report.payload()).attributeKey()).isEqualTo("on");
        assertThat(confirmed.causalContext().correlationId())
                .isEqualTo(issued.causalContext().correlationId());
        EventEnvelope triggered = awaitEnvelope(EventTypes.AUTOMATION_TRIGGERED,
                event -> true, "automation_triggered");
        assertThat(issued.causalContext().correlationId())
                .isEqualTo(triggered.causalContext().correlationId());
        EventEnvelope triggering =
                findByEventId(triggered.causalContext().causationId()).orElseThrow();
        assertThat(triggering.eventType()).isEqualTo(EventTypes.STATE_CHANGED);
        assertThat(((StateChangedEvent) triggering.payload()).attributeKey())
                .isEqualTo("occupied");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 5 — the CT derivation + the F-2 supersession pair at the E2E level
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("set_color_temperature rides the F-3 derivation with the DP-a tuned window "
            + "(command_issued carries 15000 ms), and the superseded 4550 K command renders "
            + "superseded while 4525 K confirms from ONE 221-mired report")
    void colorTemperature_derivationTunedWindow_andSupersessionPair(@TempDir Path tempDir)
            throws Exception {
        bootAndAdopt(tempDir);

        fireManual("ct-4550");
        EventEnvelope first = awaitEnvelope(EventTypes.COMMAND_ISSUED,
                event -> commandType(event).equals("set_color_temperature"),
                "command_issued(set_color_temperature 4550)");
        // DP-a at the E2E level: the executor read the ADOPTION-TUNED CommandDefinition
        // (the measured 15 s Hue CT window), not the 5 s standard default — zero
        // executor change (the P17 precedence).
        assertThat(((CommandIssuedEvent) first.payload()).confirmationTimeoutMs())
                .isEqualTo(15000);
        awaitTrue(() -> sentFrame(0x0300, 0x0A).isPresent(),
                "the first Move to Color Temperature frame");
        // round(1e6 / 4550) = 220 = 0xDC LE — the wire-boundary Kelvin→mireds inverse.
        assertThat(sentFrame(0x0300, 0x0A).orElseThrow().payload()[0])
                .isEqualTo((byte) 0xDC);

        fireManual("ct-4525");
        awaitTrue(() -> countCommandIssued("set_color_temperature") == 2,
                "the second CT command_issued");
        EventEnvelope second = events().stream()
                .filter(event -> event.eventType().equals(EventTypes.COMMAND_ISSUED))
                .filter(event -> commandType(event).equals("set_color_temperature"))
                .filter(event -> !event.eventId().equals(first.eventId()))
                .findFirst().orElseThrow();

        // F-2 at the E2E level: issuing 4525 expires the in-flight 4550 with the
        // recorded superseded disposition (Doc 08 §3.6 caveat 3, Direction 2 — with
        // ±50 K, one 4525 report WOULD have confirmed 4550 too).
        EventEnvelope superseded = awaitEnvelope(EventTypes.COMMAND_RESULT,
                event -> ((CommandResultEvent) event.payload()).outcome()
                        .equals("superseded"),
                "command_result(superseded) for the 4550 command");
        assertThat(((CommandResultEvent) superseded.payload()).failureReason())
                .contains("superseded by a newer command on the same attribute");

        rig.reportColorTemperatureMireds(221);          // ingests as exactly 4525 K
        rig.deliverAndCycle();
        EventEnvelope confirmed = awaitEnvelope(EventTypes.STATE_CONFIRMED,
                event -> true, "state_confirmed for the 4525 command");
        assertThat(((StateConfirmedEvent) confirmed.payload()).commandEventId())
                .as("only the LATEST command confirms — the superseded one is gone")
                .isEqualTo(second.eventId());
        assertThat(countEventsOfType(EventTypes.STATE_CONFIRMED)).isEqualTo(1L);
        assertThat(countEventsOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT))
                .as("the superseded command never false-fails")
                .isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 6 — the UNCONFIRMABLE immediate honest verdict (AMD-97-INV-01)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("identify rides the REAL dispatch (SD-3): issued → dispatched → router → "
            + "handler → byte-asserted Identify frame → immediate honest unconfirmed — "
            + "and NEVER a state_confirmed (AMD-97-INV-01)")
    void unconfirmableIdentify_immediateHonestVerdict(@TempDir Path tempDir) throws Exception {
        bootAndAdopt(tempDir);

        // M9.4b §3 (the M9.4a interim retired): the §3.1 Identify capability makes
        // identify issuable through the REAL Tier-1 validator — the engine's own
        // manual automation drives the FULL path, no synthesized command pair.
        fireManual("hero-identify");
        EventEnvelope issued = awaitEnvelope(EventTypes.COMMAND_ISSUED,
                event -> commandType(event).equals("identify"), "command_issued(identify)");
        awaitEnvelope(EventTypes.COMMAND_DISPATCHED,
                event -> event.causalContext().causationId()
                        .equals(issued.eventId().value()),
                "the REAL dispatch service routing identify");

        // Dispatched AND honestly unconfirmed — actuation is not gated (INV-SA-03).
        awaitTrue(() -> sentFrame(0x0003, 0x00).isPresent(),
                "the Identify frame reaching the scripted NCP");
        assertThat(sentFrame(0x0003, 0x00).orElseThrow().payload())
                .as("identifyTime u16 LE — the 3 s adapter default")
                .containsExactly(0x03, 0x00);
        EventEnvelope verdict = awaitEnvelope(EventTypes.COMMAND_RESULT,
                event -> ((CommandResultEvent) event.payload()).outcome()
                        .equals("unconfirmed"),
                "the immediate honest unconfirmed verdict");
        assertThat(((CommandResultEvent) verdict.payload()).failureReason())
                .as("the reason is the bundled profile's measured note")
                .contains("no report");
        assertThat(verdict.causalContext().causationId())
                .as("N-6: the verdict chains from the command, never a root publish")
                .isEqualTo(issued.eventId().value());
        assertThat(verdict.causalContext().correlationId())
                .as("the run's correlation reaches the verdict")
                .isEqualTo(issued.causalContext().correlationId());
        assertThat(countEventsOfType(EventTypes.STATE_CONFIRMED))
                .as("AMD-97-INV-01: an UNCONFIRMABLE command NEVER renders CONFIRMED")
                .isZero();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 8 — the SD-2 brightness honest-confirm leg (M9.4b §7.1)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("set_brightness(50) → level-127 wire frame → level-127 report → honest "
            + "state_confirmed → the query shows brightness=127 AND brightness_percent=50")
    void brightness_percentCommandLevelDomain_honestConfirm(@TempDir Path tempDir)
            throws Exception {
        bootAndAdopt(tempDir);

        fireManual("hero-brightness");
        EventEnvelope issued = awaitEnvelope(EventTypes.COMMAND_ISSUED,
                event -> commandType(event).equals("set_brightness"),
                "command_issued(set_brightness 50)");
        awaitTrue(() -> sentFrame(0x0008, 0x04).isPresent(),
                "the Move to Level frame reaching the scripted NCP");
        // round(50 × 254 / 100) = 127 — percent never rides the wire (SD-2).
        assertThat(sentFrame(0x0008, 0x04).orElseThrow().payload()[0])
                .isEqualTo((byte) 127);

        // The device reports the LEVEL domain; the SD-2 rescaled expectation
        // (WithinTolerance(127, 2)) confirms it — the F-3 false-fail class dead
        // end-to-end.
        rig.reportBrightnessLevel(127);
        rig.deliverAndCycle();
        EventEnvelope confirmed = awaitEnvelope(EventTypes.STATE_CONFIRMED,
                event -> ((StateConfirmedEvent) event.payload()).commandEventId()
                        .equals(issued.eventId()),
                "state_confirmed for set_brightness");
        StateConfirmedEvent confirmation = (StateConfirmedEvent) confirmed.payload();
        assertThat(confirmation.attributeKey()).isEqualTo("brightness");
        EventEnvelope report = findByEventId(confirmation.reportEventId()).orElseThrow();
        assertThat(report.eventType()).isEqualTo(EventTypes.STATE_REPORTED);

        // Doc 08 §3.5: the canonical 0-254 level materializes; the percent derives
        // at QUERY time (M9.4b §2.3) — never stored, never an event.
        awaitTrue(() -> core.stateQueryService().getState(hueEntity)
                        .map(state -> state.attributes().get("brightness"))
                        .isPresent(),
                "the materialized brightness level");
        var attributes = core.stateQueryService().getState(hueEntity)
                .orElseThrow().attributes();
        assertThat(attributes.get("brightness"))
                .isEqualTo(new com.homesynapse.value.IntValue(127));
        assertThat(attributes.get("brightness_percent"))
                .isEqualTo(new com.homesynapse.value.IntValue(50));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 7 — timeout honesty
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a turn_on whose report never arrives times out honestly — "
            + "command_confirmation_timed_out, never state_confirmed")
    void timeoutHonesty_noReportNeverConfirms(@TempDir Path tempDir) throws Exception {
        bootAndAdopt(tempDir);

        fireManual("hero-turn-on");
        EventEnvelope issued = awaitEnvelope(EventTypes.COMMAND_ISSUED,
                event -> commandType(event).equals("turn_on"), "command_issued(turn_on)");
        awaitTrue(() -> sentFrame(0x0006, 0x01).isPresent(), "the dispatched On frame");

        // No report ever arrives. Step the injected clock past the tuned 5 s window;
        // the scheduler's real-time expiry tick evaluates deadlines against it.
        rig.clock().advance(Duration.ofSeconds(6));
        awaitTrue(() -> countEventsOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT) == 1L,
                "the honest confirmation timeout");
        assertThat(countEventsOfType(EventTypes.STATE_CONFIRMED)).isZero();
        assertThat(issued.eventId()).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness — boot the REAL root over the rig, adopt both devices (step 1)
    // ════════════════════════════════════════════════════════════════════════

    private void bootAndAdopt(Path tempDir) throws Exception {
        clock = TestClock.createDefault();
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

        // §4.2 boot smoke: the supervisor created + started the adapter green over
        // the scripted NCP, and the W10 schema registration is visible.
        core.registerIntegrationSchema(ZigbeeIntegrationFactory.INTEGRATION_TYPE,
                ZigbeeIntegrationFactory.configSchemaJson());
        assertThat(core.schemaRegistry().getComposedSchema()).contains("zigbee");
        awaitRuntimeSubscribersLive();
        awaitTrue(rig::sessionStarted, "the EZSP session over the scripted NCP");

        // Step 1 — REAL interview/adoption over scripted ZDO/ZCL exchanges.
        rig.announce(ZigbeeHardwareFreeRig.SNZB_IEEE);
        rig.announce(ZigbeeHardwareFreeRig.HUE_IEEE);
        rig.deliverAndCycle();
        snzbEntity = rig.adopt(ZigbeeHardwareFreeRig.SNZB_IEEE)
                .get(ZigbeeHardwareFreeRig.SNZB_ENDPOINT);
        hueEntity = rig.adopt(ZigbeeHardwareFreeRig.HUE_IEEE)
                .get(ZigbeeHardwareFreeRig.HUE_ENDPOINT);
        awaitRegistryProjectionCaughtUp();
        label(snzbEntity, "motion");
        label(hueEntity, "hero-light");

        // The §2 overrides are INSTALLED: the Hue CT capability carries the measured
        // 15 s window on BOTH the policy and the CommandDefinition (P17 precedence).
        Entity hue = core.entityRegistry().getEntity(hueEntity);
        var colorTemperature = hue.capabilities().stream()
                .filter(capability -> capability.capabilityId().equals("color_temperature"))
                .findFirst().orElseThrow();
        assertThat(colorTemperature.confirmation().defaultTimeoutMs()).isEqualTo(15000L);
        assertThat(colorTemperature.commands().get("set_color_temperature")
                .defaultTimeout().toMillis()).isEqualTo(15000L);
    }

    /** Re-registers an adopted entity with a selector label (the trigger/action join). */
    private void label(EntityId entityId, String labelValue) {
        Entity entity = core.entityRegistry().getEntity(entityId);
        core.entityRegistry().updateEntity(new Entity(entity.entityId(),
                entity.entitySlug(), entity.entityType(), entity.displayName(),
                entity.deviceId(), entity.endpointIndex(), entity.areaId(),
                entity.enabled(), List.of(labelValue), entity.capabilities(),
                entity.entityRole(), entity.createdAt()));
    }

    private void writeConfig(Path tempDir) throws Exception {
        String yaml = """
                automation:
                  automations:
                    - name: "hero motion lights"
                      slug: "hero-motion"
                      triggers:
                        - type: state_change
                          label: "motion"
                          attribute: "occupied"
                          to: "true"
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
                    - name: "ct 4525"
                      slug: "ct-4525"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: set_color_temperature
                          parameters:
                            kelvin: 4525
                    - name: "hero turn on"
                      slug: "hero-turn-on"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: turn_on
                    - name: "hero identify"
                      slug: "hero-identify"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: identify
                    - name: "hero brightness"
                      slug: "hero-brightness"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            label: "hero-light"
                          command: set_brightness
                          parameters:
                            level: 50
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
                new com.homesynapse.event.AutomationInvokedEvent("hero-loop"), null, null));
    }

    // ── awaits + store reads (real-time polls; clock-independent) ───────────

    private void awaitRuntimeSubscribersLive() {
        for (int poll = 0; poll < 250; poll++) {
            if (subscriberMode("automation_engine") == SubscriberMode.LIVE
                    && subscriberMode("command_dispatch_service") == SubscriberMode.LIVE
                    && subscriberMode("pending_command_ledger") == SubscriberMode.LIVE
                    && subscriberMode("integration_supervisor") == SubscriberMode.LIVE
                    && subscriberMode("state_projection") == SubscriberMode.LIVE) {
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

    private long countCommandIssued(String type) {
        return events().stream()
                .filter(event -> event.eventType().equals(EventTypes.COMMAND_ISSUED))
                .filter(event -> commandType(event).equals(type)).count();
    }

    private static String commandType(EventEnvelope envelope) {
        return envelope.payload() instanceof CommandIssuedEvent issued
                ? issued.commandType() : "";
    }

    private List<String> reportedValues(String attributeKey) {
        return events().stream()
                .filter(event -> event.eventType().equals(EventTypes.STATE_REPORTED))
                .map(EventEnvelope::payload)
                .map(payload -> (StateReportedEvent) payload)
                .filter(reported -> reported.attributeKey().equals(attributeKey))
                .map(StateReportedEvent::value)
                .toList();
    }

    private Optional<EventEnvelope> findByEventId(com.homesynapse.event.EventId eventId) {
        return events().stream()
                .filter(event -> event.eventId().equals(eventId))
                .findFirst();
    }

    private Optional<EventEnvelope> findByEventId(Ulid eventId) {
        return events().stream()
                .filter(event -> event.eventId().value().equals(eventId))
                .findFirst();
    }

    private Optional<ZigbeeHardwareFreeRig.SentZcl> sentFrame(int clusterId, int commandId) {
        return rig.sentZclFrames().stream()
                .filter(frame -> frame.clusterId() == clusterId
                        && frame.commandId() == commandId)
                .findFirst();
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

    /** What a {@code LongSupplier} returns when the await cannot name its position. */
    private static final long NO_AWAITED_POSITION = -1L;

    /**
     * FIX-2a (A): the polling shape is unchanged (500 × 20 ms); on timeout the
     * failure carries the bus reading — {@link BusAwaitDiagnostic} — printed to
     * stdout (the XML {@code <system-out>}) and thrown as the message (the XML
     * {@code <failure message>}). This arm names no awaited position.
     */
    private void awaitTrue(BooleanSupplier condition, String what) {
        awaitTrue(condition, what, () -> NO_AWAITED_POSITION);
    }

    /**
     * The sibling for an await that can name the store position it is gated on.
     * The supplier is read only on timeout (the newest such position at that
     * moment); a negative value reads as {@code none}.
     */
    private void awaitTrue(BooleanSupplier condition, String what,
            LongSupplier awaitedPosition) {
        for (int poll = 0; poll < 500; poll++) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        long position = awaitedPosition.getAsLong();
        throw timeoutDiagnostic(what,
                position < 0 ? OptionalLong.empty() : OptionalLong.of(position));
    }

    /**
     * Gathers the reading — the store head from {@link #events()}, every
     * subscriber from {@code subscribers()} — prints it and returns the error to
     * throw. An instrument never becomes the failure channel: when the gathering
     * itself throws, the bare timeout message carries that cause instead.
     */
    private AssertionError timeoutDiagnostic(String what, OptionalLong awaited) {
        String text;
        try {
            long storeHead = events().stream()
                    .mapToLong(EventEnvelope::globalPosition).max().orElse(0L);
            text = BusAwaitDiagnostic.render(what, storeHead, awaited,
                    core.eventBus().subscribers());
        } catch (RuntimeException gatherFailure) {
            text = "timed out awaiting " + what
                    + " (bus.await_timeout unavailable: " + gatherFailure + ")";
        }
        System.out.println(text);
        return new AssertionError(text);
    }

    /**
     * The newest {@code state_reported} position carrying {@code attributeKey=value}
     * (the position an On-frame await is gated on), or the sentinel when none.
     */
    private long newestReportedPosition(String attributeKey, String value) {
        return events().stream()
                .filter(event -> event.eventType().equals(EventTypes.STATE_REPORTED))
                .filter(event -> {
                    StateReportedEvent reported = (StateReportedEvent) event.payload();
                    return attributeKey.equals(reported.attributeKey())
                            && value.equals(reported.value());
                })
                .mapToLong(EventEnvelope::globalPosition)
                .max()
                .orElse(NO_AWAITED_POSITION);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the hero loop", ex);
        }
    }
}
