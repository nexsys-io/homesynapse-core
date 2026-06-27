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
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportedEvent;
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
 * The M7.4c composition-root <em>confirmation</em>-gate test — the second half of OR-M7-WIRING,
 * proving the "did it actually confirm?" hero renders on a live pipeline. It boots the <em>real</em>
 * {@link HomeSynapseCore} to LIVE with a seeded command automation + an integration-routed
 * device/entity carrying the {@code on_off} capability, fires the trigger, awaits the M7.4b producer
 * chain ({@code command_issued} → {@code command_dispatched}), then injects a <strong>synthetic
 * {@code state_reported}</strong> (there is no real device/adapter until M9) whose value matches the
 * {@code turn_on} {@link com.homesynapse.device.ExpectedOutcome} and asserts the
 * {@code pending_command_ledger} publishes {@code state_confirmed} with a queryable causal chain and
 * drains.
 *
 * <p><strong>This proves a real CONFIRM, not an OPTIMISTIC bypass.</strong>
 * {@code StandardCapabilities.onOff()} declares {@code turn_on} with
 * {@code ExpectedOutcome("on", ExactMatch(true), 5000)} and {@code ConfirmationMode.EXACT_MATCH}
 * (NOT {@code DISABLED}), so the ledger tracks the command and confirms it against the matching
 * report — a bypass would never emit {@code state_confirmed} at all, so the event's presence (with
 * {@code matchType == "exact"}) is the proof.</p>
 *
 * <p>Time is injected via {@code Clock.fixed} (§4c — lifecycle test code self-enforces Clock
 * injection). The fixed clock never advances past the command's deadline ({@code FIXED_INSTANT +
 * 5000 ms}), so the {@code pollExpirations()} scheduler tick never times the command out — the
 * confirmation wins deterministically. The async run/dispatch/confirm hops are awaited by polling
 * the durable event store and the ledger's own query surface (real-time sleeps, clock-independent).</p>
 */
@DisplayName("RunPipelineConfirmWiringTest -- composition-root confirmation pipeline (M7.4c)")
final class RunPipelineConfirmWiringTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    private static final String AUTOMATION_SLUG = "confirm-pipeline";
    private static final String COMMAND = "turn_on";
    private static final String CONFIRM_ATTRIBUTE = "on";       // on_off authoritative attribute
    private static final String CONFIRM_VALUE = "true";         // matches ExactMatch(BooleanValue(true))
    private static final String ENTITY_ULID = "01J" + "B".repeat(23);
    private static final String DEVICE_ULID = "01J" + "C".repeat(23);
    private static final String INTEGRATION_ULID = "01J" + "D".repeat(23);

    private final EntityId targetEntityId = EntityId.parse(ENTITY_ULID);
    private final DeviceId deviceId = DeviceId.parse(DEVICE_ULID);
    private final IntegrationId integrationId = IntegrationId.parse(INTEGRATION_ULID);

    private HomeSynapseCore core;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RunPipelineConfirmWiringTest() {
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
                    - name: "confirm pipeline automation"
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
     * Seeds the target entity (with the {@code on_off} capability — EXACT_MATCH confirmation on
     * {@code on}, so a {@code turn_on} command CONFIRMS rather than optimistic-bypasses) and its
     * integration-routed device.
     */
    private void seedDeviceAndEntity() {
        Capability onOff = StandardCapabilities.onOff();
        CapabilityInstance instance = new CapabilityInstance(
                onOff.capabilityId(), onOff.version(), onOff.namespace(), 0,
                onOff.attributeSchemas(), onOff.commandDefinitions(), onOff.confirmationPolicy());
        Entity entity = new Entity(targetEntityId, "confirm-light", EntityType.LIGHT,
                "Confirm Light", deviceId, 0, null, true, List.of(), List.of(instance),
                EntityRole.PRIMARY, FIXED_INSTANT);
        Device device = new Device(deviceId, "confirm-device", "Confirm Device", "Acme", "Model",
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

    /**
     * Injects the synthetic confirming {@code state_reported} (no real device until M9) and returns
     * its persisted envelope. The {@code on=true} report matches the {@code on_off} {@code turn_on}
     * {@code ExpectedOutcome} ({@code ExactMatch(true)}), so the ledger confirms it.
     */
    private EventEnvelope injectConfirmingStateReported() throws Exception {
        return core.eventPublisher().publishRoot(new EventDraft(
                EventTypes.STATE_REPORTED, 1, null, SubjectRef.entity(targetEntityId),
                EventPriority.DIAGNOSTIC, EventOrigin.DEVICE_AUTONOMOUS,
                new StateReportedEvent(CONFIRM_ATTRIBUTE, CONFIRM_VALUE, null, null, null),
                null, null));
    }

    /** Polls the durable event store until {@code eventType} appears (async run/dispatch/confirm). */
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

    /**
     * Polls the ledger's query surface until it has LIVE-tracked the dispatched command. This both
     * removes the boot-time REPLAY→LIVE race (a {@code command_issued} delivered while the ledger is
     * still catching up would be accumulated, not tracked) and proves the command entered the real
     * confirm path (an OPTIMISTIC bypass would never raise the count off zero).
     */
    private void awaitLedgerTracked() {
        for (int poll = 0; poll < 250; poll++) {       // ~5s at 20ms
            if (core.pendingCommandLedger().pendingCount() >= 1) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("pending_command_ledger did not track the command within ~5s "
                + "(it must be LIVE-tracking before the confirming state_reported is injected — a "
                + "count stuck at 0 means an OPTIMISTIC bypass or a missed REPLAY→LIVE transition)");
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
    @DisplayName("a seeded automation firing -> command_issued + command_dispatched -> a matching "
            + "synthetic state_reported confirms (state_confirmed) with a queryable causal chain; "
            + "the ledger drains")
    void seededAutomation_firesTrigger_confirmsViaSyntheticStateReported(@TempDir Path tempDir)
            throws Exception {
        writeConfig(tempDir);
        core = newCore(tempDir);
        core.start();
        seedDeviceAndEntity();

        fireManualTrigger();

        // The M7.4b producer chain: await command_dispatched (the last producer hop); command_issued
        // is then durable too.
        List<EventEnvelope> dispatched = awaitEventsOfType(EventTypes.COMMAND_DISPATCHED);
        assertThat(dispatched).hasSize(1);
        List<EventEnvelope> issuedEvents = awaitEventsOfType(EventTypes.COMMAND_ISSUED);
        assertThat(issuedEvents).hasSize(1);
        EventEnvelope issued = issuedEvents.get(0);

        // The ledger must have LIVE-tracked the command before the confirming report is injected
        // (FIFO already orders command_issued before the report within the ledger's queue; this also
        // proves a real CONFIRM path, not an OPTIMISTIC bypass).
        awaitLedgerTracked();

        // Inject the synthetic confirming state_reported (no real device until M9).
        EventEnvelope reported = injectConfirmingStateReported();

        // The ledger correlates the report to the in-flight command and publishes state_confirmed.
        List<EventEnvelope> confirmedEvents = awaitEventsOfType(EventTypes.STATE_CONFIRMED);
        assertThat(confirmedEvents).hasSize(1);
        EventEnvelope confirmedEnvelope = confirmedEvents.get(0);
        StateConfirmedEvent confirmed = (StateConfirmedEvent) confirmedEnvelope.payload();

        // The causal chain threads command_issued -> state_confirmed: the confirmation references
        // the command_issued event id and the confirming report, and carries an exact match.
        assertThat(confirmed.commandEventId()).isEqualTo(issued.eventId());
        assertThat(confirmed.reportEventId()).isEqualTo(reported.eventId());
        assertThat(confirmed.attributeKey()).isEqualTo(CONFIRM_ATTRIBUTE);
        assertThat(confirmed.actualValue()).isEqualTo(CONFIRM_VALUE);
        assertThat(confirmed.matchType()).isEqualTo("exact");

        // The queryable causal chain command_issued -> command_dispatched -> state_confirmed shares
        // the Run's correlation id (the chain the Web-UI hero read-slice will thread).
        Ulid runCorrelation = issued.causalContext().correlationId();
        assertThat(confirmedEnvelope.causalContext().correlationId()).isEqualTo(runCorrelation);
        assertThat(dispatched.get(0).causalContext().correlationId()).isEqualTo(runCorrelation);

        // The ledger drained: the confirmed command is no longer in flight.
        assertThat(core.pendingCommandLedger().pendingCount()).isZero();
        assertThat(core.pendingCommandLedger().getCommand(issued.eventId())).isEmpty();
    }

    @Test
    @DisplayName("the pending_command_ledger subscriber tears down cleanly on shutdown (its read "
            + "connection released; no leaked @TempDir handle)")
    void ledgerSubscriber_tornDownCleanly_onShutdown(@TempDir Path tempDir) throws Exception {
        writeConfig(tempDir);
        core = newCore(tempDir);
        core.start();
        seedDeviceAndEntity();
        fireManualTrigger();
        assertThat(awaitEventsOfType(EventTypes.COMMAND_DISPATCHED)).hasSize(1);
        awaitLedgerTracked();
        injectConfirmingStateReported();
        assertThat(awaitEventsOfType(EventTypes.STATE_CONFIRMED)).hasSize(1);

        core.shutdown("test");

        // The reverted-M7.3 fingerprint: a ledger subscriber registered with no paired unsubscribe
        // holds its SQLite read connection open, so @TempDir cleanup fails with an IOException after
        // this method. A clean STOPPED + no post-method @TempDir failure is the gate (the paired
        // eventBus.unsubscribe(pending_command_ledger) in doTeardown releases the connection).
        assertThat(core.currentPhase()).isEqualTo(LifecyclePhase.STOPPED);
    }
}
