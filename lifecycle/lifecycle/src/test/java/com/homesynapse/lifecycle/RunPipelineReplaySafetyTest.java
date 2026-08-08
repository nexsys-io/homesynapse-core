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
import com.homesynapse.persistence.PersistenceFactory;
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
import java.util.stream.Stream;

/**
 * The M7.4d composition-root <em>replay-safety</em> gate — the CI-pinned integration proof of the
 * <strong>D2 pure-function-replay invariant</strong> (the canonical {@code INV-} id is pending
 * registration; §1 deeper-M7 automation-architecture decision record, 2026-06-25). D2 requires that
 * device dispatch and <em>all external side-effects</em> (adapter calls, network I/O, notifications —
 * anything observable outside the log) <strong>never run on log replay</strong>; they run only on
 * new-command handling in {@code LIVE} mode. This test boots the <em>real</em> {@link HomeSynapseCore}
 * over a seeded command-lifecycle log and asserts the whole assembled pipeline
 * (trigger&rarr;run&rarr;dispatch&rarr;ledger) is replay-pure end-to-end.
 *
 * <p>D1's event-driven shape already makes the property <em>structural</em>: every runtime subscriber
 * acts only in {@code LIVE} (the bus's {@code REPLAY -> TRANSITION -> LIVE} FSM stops it publishing
 * during {@code REPLAY}), and there is a unit-level proof
 * ({@code CommandDispatchSubscriberTest.onCommandIssued_inReplay_doesNotDispatch}). This WU does NOT
 * add the property — it <strong>names, guarantees, and CI-pins</strong> it at the integration level.
 * Because {@code ci.yml} runs {@code ./gradlew check}, this JUnit test is automatically a CI gate of
 * record. It is the production analog of the throwaway V2-A spike
 * ({@code spike/dispatch-latency-and-log-growth/}, which proved 1e6 replayed {@code command_issued}
 * &rarr; zero dispatch).
 *
 * <h2>How the seed is replayed (the load-bearing mechanism)</h2>
 *
 * <p>A graceful or crash boot that produced the prior run <em>in {@code LIVE}</em> would also advance
 * each runtime subscriber's per-event checkpoint <em>past</em> the seeded {@code command_issued}, so a
 * second boot would resume <em>after</em> it and never replay it — a vacuous test. Instead the seed is
 * written through a <strong>standalone {@link PersistenceFactory}</strong> (no event bus, no
 * subscribers), so {@code subscriber_checkpoints} stays empty. When {@link HomeSynapseCore} then boots
 * over the same db, every fresh subscriber reads checkpoint {@code 0} and replays the entire seeded log
 * in {@code REPLAY} — exactly the {@code busAheadOfViewWindowClosedByReplayFromZero} crash-recovery
 * scenario, and the strongest case for D2.
 *
 * <h2>Non-vacuousness (a broken — acts-in-{@code REPLAY} — implementation must fail this)</h2>
 *
 * <ul>
 *   <li><b>Dispatch:</b> the seeded {@code command_issued} targets an entity that is NOT in the
 *       registry during replay, so a broken {@code command_dispatch_service} would publish
 *       {@code command_result("unroutable")} (or {@code command_dispatched} for a routable target).
 *       Asserting <em>both</em> stay at the seeded count closes the gap regardless of routability.</li>
 *   <li><b>Run initiation:</b> the seeded {@code state_reported} matches an {@code EventTrigger}
 *       (type-only match — registry- and {@code AutomationId}-independent; a {@code DirectRefSelector}
 *       state-trigger would need the entity registered, and a {@code manual}/{@code automation_invoked}
 *       trigger would need the boot-fresh {@code AutomationId}). A broken {@code automation_engine}
 *       would {@code initiateRuns} from it, emitting {@code automation_triggered} + a new
 *       {@code command_issued}.</li>
 *   <li><b>Confirmation:</b> the seed is a COMPLETE prior run, so the ledger's replay fold removes the
 *       command (the seeded {@code state_confirmed} folds out what {@code command_issued} folded in) —
 *       leaving nothing in-flight at {@code onCaughtUp} that {@code classifyRestart} could legitimately
 *       re-publish. A broken ledger would publish a second {@code state_confirmed}.</li>
 *   <li><b>Positive control</b> ({@link #afterLive_newTrigger_doesDispatch}): the SAME seeded-and-
 *       replayed core, once {@code LIVE}, dispatches a real {@code command_dispatched} when one new
 *       trigger fires — so the "zero on replay" assertions are meaningful (the harness sees non-zero
 *       in {@code LIVE}).</li>
 * </ul>
 *
 * <h2>The deterministic replay-completion boundary</h2>
 *
 * <p>{@link HomeSynapseCore#start()} gates on the projection reaching {@code LIVE} but registers
 * {@code automation_engine} / {@code command_dispatch_service} / {@code pending_command_ledger}
 * <em>after</em> that, so on return they may still be replaying on their own virtual threads. The test
 * therefore polls {@code core.eventBus().subscribers()} until all three report {@code LIVE} before
 * snapshotting the store. Because the publisher persists-before-notify and each subscriber delivers
 * sequentially on a single VT, any replay-phase publish is durably in the store before that subscriber
 * announces {@code LIVE}, so "all {@code LIVE}" is a race-free upper bound for "all replay side-effects
 * are visible." A subscriber that trips to {@code SUSPENDED} (a replayed {@code onEvent} threw) is
 * itself a replay-safety regression and fails fast rather than spinning to timeout.
 *
 * <h2>Time (§4c) and the M9 adapter seam (M9.1)</h2>
 *
 * <p>Time is injected via {@code Clock.fixed} (lifecycle test code self-enforces Clock injection;
 * the await loops use real-time {@code Thread.sleep}, which is clock-independent and terminates under
 * a fixed clock). <strong>Since M9.1 the outbound dispatch side-effect is REAL:</strong> the core
 * boots with the {@link RecordingIntegrationFactory} (a registered, ROUTABLE fake — the seeded
 * {@code command_dispatched} carries the fake's derived id and chains causation from the seeded
 * {@code command_issued}, so a broken acts-in-REPLAY router would join and invoke
 * {@code CommandHandler.handle(...)}). The gate therefore asserts the adapter recorder is
 * <strong>zero across the replay window</strong> (INV-ES-09 — pure-function replay), exactly as this
 * javadoc's original M9 forward note demanded, and the LIVE positive control asserts the recorder
 * FIRES for one new trigger (non-vacuousness: the same harness sees a real adapter call in LIVE).</p>
 */
@DisplayName("RunPipelineReplaySafetyTest -- composition-root D2 pure-function-replay gate (M7.4d)")
final class RunPipelineReplaySafetyTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    private static final String COMMAND = "turn_on";
    private static final String CONFIRM_ATTRIBUTE = "on";       // on_off authoritative attribute
    private static final String CONFIRM_VALUE = "true";         // matches the turn_on ExpectedOutcome

    private static final String AUTOMATION_REPLAY_SLUG = "replay-safety-trigger";
    private static final String AUTOMATION_LIVE_SLUG = "live-control";

    // The seed targets (NOT registered during replay) and the LIVE positive-control routing chain.
    // Since M9.1 BOTH route to the recording fake's derived integration id: the seeded
    // command_dispatched must be ROUTABLE (INV-ES-09 — a broken acts-in-REPLAY router would
    // reach the registered fake), and the LIVE control proves the recorder fires.
    private static final String SEED_ENTITY_ULID = "01J" + "E".repeat(23);
    private static final String LIVE_ENTITY_ULID = "01J" + "F".repeat(23);
    private static final String LIVE_DEVICE_ULID = "01J" + "G".repeat(23);

    // The seeded prior run, written before boot and replayed from checkpoint 0. A replay-pure pipeline
    // adds NONE of these; each constant is therefore the exact post-replay count for a correct system.
    private static final long SEEDED_COMMAND_ISSUED = 1L;
    private static final long SEEDED_COMMAND_DISPATCHED = 1L;
    private static final long SEEDED_STATE_CONFIRMED = 1L;
    private static final int LOADED_AUTOMATION_COUNT = 2;

    private final EntityId seedEntityId = EntityId.parse(SEED_ENTITY_ULID);
    private final EntityId liveEntityId = EntityId.parse(LIVE_ENTITY_ULID);
    private final DeviceId liveDeviceId = DeviceId.parse(LIVE_DEVICE_ULID);
    private final IntegrationId fakeIntegrationId =
            IntegrationIds.deriveStable(RecordingIntegrationFactory.INTEGRATION_TYPE);

    private HomeSynapseCore core;
    private RecordingIntegrationFactory recordingFake;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    RunPipelineReplaySafetyTest() {
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
    @DisplayName("a seeded command-lifecycle log replayed on boot produces ZERO dispatch side-effects: "
            + "no new command_dispatched and no new command_result (the dispatch seam stays silent in "
            + "REPLAY)")
    void seededCommandLog_onReplay_producesZeroDispatchSideEffects(@TempDir Path tempDir)
            throws Exception {
        seedBootAndAwaitReplayComplete(tempDir);

        // The dispatch seam (today: the command_dispatched publish) must not fire on replay. The seeded
        // log holds exactly ONE command_dispatched (the prior run); replaying its command_issued must
        // add none. A broken (acts-in-REPLAY) dispatch subscriber would publish command_dispatched for
        // a routable target, or command_result("unroutable") for the seed entity (which is not in the
        // registry during replay) — asserting both stay at the seeded count makes the gate non-vacuous.
        assertThat(countEventsOfType(EventTypes.COMMAND_DISPATCHED)).isEqualTo(SEEDED_COMMAND_DISPATCHED);
        assertThat(countEventsOfType(EventTypes.COMMAND_RESULT)).isZero();

        // M9.1 (INV-ES-09): the REAL adapter seam stayed silent too. The seeded dispatched is
        // joinable AND routable to the registered fake — a broken acts-in-REPLAY router would
        // have invoked handle() on it.
        assertThat(recordingFake.recordedCommands()).isEmpty();
    }

    @Test
    @DisplayName("a seeded trigger-matching event replayed on boot initiates NO Run: no "
            + "automation_triggered, no automation_completed, no new command_issued (the engine "
            + "initiates only in LIVE)")
    void seededTrigger_onReplay_initiatesNoRun(@TempDir Path tempDir) throws Exception {
        seedBootAndAwaitReplayComplete(tempDir);

        // The seeded state_reported matches the event-trigger automation's EventTrigger (a type-only
        // match — registry- and AutomationId-independent). A broken automation_engine would initiate a
        // Run from it on replay, emitting automation_triggered + automation_completed + a NEW
        // command_issued. The seeded log holds exactly ONE command_issued (the prior run) and ZERO
        // run-lifecycle events; a replay-pure engine adds none.
        assertThat(countEventsOfType(EventTypes.AUTOMATION_TRIGGERED)).isZero();
        assertThat(countEventsOfType(EventTypes.AUTOMATION_COMPLETED)).isZero();
        assertThat(countEventsOfType(EventTypes.COMMAND_ISSUED)).isEqualTo(SEEDED_COMMAND_ISSUED);
        assertThat(recordingFake.recordedCommands()).isEmpty();    // M9.1 INV-ES-09
    }

    @Test
    @DisplayName("a seeded command with an open expectation, replayed on boot, confirms NOTHING: the "
            + "ledger rebuilds its in-flight set as a pure fold (pendingCount 0) and publishes no "
            + "state_confirmed / command_confirmation_timed_out")
    void seededConfirmableCommand_onReplay_confirmsNothing(@TempDir Path tempDir) throws Exception {
        seedBootAndAwaitReplayComplete(tempDir);

        // The seed is a COMPLETE prior run (command_issued -> state_reported -> state_confirmed). On
        // replay the ledger folds command_issued into its replay accumulator and the seeded
        // state_confirmed folds it back out — a pure projection rebuild, no publish. A broken ledger
        // acting on the replayed command/report would publish a SECOND state_confirmed (or, with the
        // command left in-flight, a command_confirmation_timed_out). The seeded log holds exactly ONE
        // state_confirmed; a replay-pure ledger adds none and rebuilds an empty in-flight set.
        assertThat(countEventsOfType(EventTypes.STATE_CONFIRMED)).isEqualTo(SEEDED_STATE_CONFIRMED);
        assertThat(countEventsOfType(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT)).isZero();
        assertThat(core.pendingCommandLedger().pendingCount()).isZero();
        assertThat(recordingFake.recordedCommands()).isEmpty();    // M9.1 INV-ES-09
    }

    @Test
    @DisplayName("positive control: after the SAME seeded-and-replayed core reaches LIVE, one new "
            + "trigger DOES dispatch (a new command_dispatched targeting the live entity) AND the "
            + "adapter recorder fires — proving the gate would catch a replay-acts regression")
    void afterLive_newTrigger_doesDispatch(@TempDir Path tempDir) throws Exception {
        seedBootAndAwaitReplayComplete(tempDir);

        // Replay produced zero dispatch (the three assertions above); the store holds only the seeded
        // command_dispatched, and the fake's recorder is untouched (M9.1 INV-ES-09).
        long dispatchedAfterReplay = countEventsOfType(EventTypes.COMMAND_DISPATCHED);
        assertThat(dispatchedAfterReplay).isEqualTo(SEEDED_COMMAND_DISPATCHED);
        assertThat(recordingFake.recordedCommands()).isEmpty();

        // Now drive ONE new trigger in LIVE on the positive-control automation (a routable, seeded
        // entity, wired to the recording fake's derived integration id). This is what makes the
        // "zero on replay" assertions meaningful — the same harness must see non-zero in LIVE, at
        // BOTH seams: the durable command_dispatched AND the real adapter recorder.
        seedLiveControlDeviceAndEntity();
        fireManualTrigger(AUTOMATION_LIVE_SLUG);

        assertThat(awaitCommandDispatchedFor(liveEntityId)).isTrue();
        assertThat(countEventsOfType(EventTypes.COMMAND_DISPATCHED)).isGreaterThan(dispatchedAfterReplay);
        assertThat(awaitRecorderNonEmpty()).isTrue();
        assertThat(recordingFake.recordedCommands().get(0).entityRef()).isEqualTo(liveEntityId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness: seed (standalone persistence) -> boot real core -> await replay complete
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Writes the two-automation config, seeds the prior command-lifecycle run into the raw store (no
     * subscribers, so checkpoint 0 on boot), boots the real composition root, and blocks until every
     * runtime subscriber has finished its boot REPLAY pass.
     */
    private void seedBootAndAwaitReplayComplete(Path tempDir) throws Exception {
        HomeSynapseConfig config = HomeSynapseConfig.testing();
        writeConfig(tempDir);
        seedPriorRun(tempDir.resolve("homesynapse-events.db"), config);
        recordingFake = RecordingIntegrationFactory.recording();
        core = newCore(tempDir, config);
        core.start();
        // Both automations must have loaded — a config-load regression (e.g. the EventTrigger shape
        // failing schema/loader validation) would silently make the run-initiation axis vacuous, so
        // fail fast here instead of passing on an unloaded ruleset.
        assertThat(core.automationRegistry().getAll()).hasSize(LOADED_AUTOMATION_COUNT);
        awaitRuntimeSubscribersLive();
    }

    private HomeSynapseCore newCore(Path tempDir, HomeSynapseConfig config) {
        // M9.1: boot with the recording fake registered — the gate's replay window now
        // covers the REAL adapter seam (INV-ES-09), not just the durable publishes.
        return new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                tempDir.resolve("config"),
                config,
                FIXED_CLOCK,
                TEST_HOME_ID,
                null,
                List.of(recordingFake));
    }

    /**
     * Writes a two-automation config: an {@code event}-trigger automation (matched by the seeded
     * {@code state_reported} on replay — the run-initiation non-vacuousness lever) and a {@code manual}
     * automation targeting the routable live entity (the LIVE positive control). Neither
     * {@code entity_ref} is existence-validated at load, so both load before any entity is seeded.
     */
    private void writeConfig(Path tempDir) throws Exception {
        String yaml = ("""
                automation:
                  automations:
                    - name: "replay-safety event-trigger automation"
                      slug: "%s"
                      triggers:
                        - type: event
                          event_type: "state_reported"
                      actions:
                        - type: command
                          target:
                            entity_ref: "%s"
                          command: %s
                    - name: "live positive-control automation"
                      slug: "%s"
                      triggers:
                        - type: manual
                      actions:
                        - type: command
                          target:
                            entity_ref: "%s"
                          command: %s
                """).formatted(AUTOMATION_REPLAY_SLUG, SEED_ENTITY_ULID, COMMAND,
                        AUTOMATION_LIVE_SLUG, LIVE_ENTITY_ULID, COMMAND);
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), yaml);
    }

    /**
     * Seeds a COMPLETE prior command-lifecycle run into the raw event store via a standalone
     * {@link PersistenceFactory} (no bus, no subscribers — so no checkpoint advances and a later boot
     * replays from 0). The event-class manifest mirrors {@link HomeSynapseCore} exactly so the seeded
     * payloads round-trip on read. {@code close()} flushes the WAL and releases all connections so the
     * boot can open the same db (and {@code @TempDir} cleanup succeeds on Windows).
     */
    private void seedPriorRun(Path dbPath, HomeSynapseConfig config) throws Exception {
        List<Class<? extends DomainEvent>> eventClasses = Stream.of(
                        EventTypes.CORE_PRODUCTION_EVENT_CLASSES,
                        IntegrationEvents.LIFECYCLE_EVENT_CLASSES,
                        IntegrationEvents.CAPABILITY_EVENT_CLASSES)
                .flatMap(List::stream)
                .toList();
        try (PersistenceFactory seed = PersistenceFactory.start(
                dbPath, config.persistence(), FIXED_CLOCK, TEST_HOME_ID, eventClasses, null)) {
            EventPublisher publisher = seed.eventPublisher();

            // 1. command_issued — the open command (its replay would re-dispatch under a broken
            //    subscriber; NOT_IDEMPOTENT so a still-in-flight command at onCaughtUp WOULD publish a
            //    recovery command_result — which is why the seed must also confirm it below).
            EventEnvelope issued = publisher.publishRoot(new EventDraft(
                    EventTypes.COMMAND_ISSUED, 1, FIXED_INSTANT, SubjectRef.entity(seedEntityId),
                    EventPriority.NORMAL, EventOrigin.AUTOMATION,
                    new CommandIssuedEvent(seedEntityId.value(), COMMAND, "{}", 30_000,
                            CommandIdempotency.NOT_IDEMPOTENT),
                    null, null));

            // 2. command_dispatched — the prior dispatch. Since M9.1 this row is consumed by the
            //    CommandRoutingSubscriber, so the seed makes it maximally dangerous: causation
            //    CHAINED from the seeded command_issued (joinable in the router's cache) and
            //    integrationId = the REGISTERED recording fake's derived id (routable). A broken
            //    acts-in-REPLAY router would join, resolve the fake, and invoke handle() — the
            //    recorder-zero assertion catches exactly that (INV-ES-09).
            publisher.publish(new EventDraft(
                    EventTypes.COMMAND_DISPATCHED, 1, FIXED_INSTANT, SubjectRef.entity(seedEntityId),
                    EventPriority.DIAGNOSTIC, EventOrigin.AUTOMATION,
                    new CommandDispatchedEvent(seedEntityId.value(), fakeIntegrationId.value(), "{}"),
                    null, null),
                    CausalContext.chain(issued.causalContext().correlationId(),
                            issued.eventId().value()));

            // 3. state_reported — the confirming report AND the EventTrigger-matching event for replay.
            EventEnvelope reported = publisher.publishRoot(new EventDraft(
                    EventTypes.STATE_REPORTED, 1, FIXED_INSTANT, SubjectRef.entity(seedEntityId),
                    EventPriority.DIAGNOSTIC, EventOrigin.DEVICE_AUTONOMOUS,
                    new StateReportedEvent(CONFIRM_ATTRIBUTE, CONFIRM_VALUE, null, null, null),
                    null, null));

            // 4. state_confirmed — the prior confirmation; on replay it folds the command back out of
            //    the ledger's accumulator so nothing is in-flight at onCaughtUp (pendingCount -> 0).
            publisher.publishRoot(new EventDraft(
                    EventTypes.STATE_CONFIRMED, 1, FIXED_INSTANT, SubjectRef.entity(seedEntityId),
                    EventPriority.NORMAL, EventOrigin.SYSTEM,
                    new StateConfirmedEvent(issued.eventId(), reported.eventId(),
                            CONFIRM_ATTRIBUTE, CONFIRM_VALUE, CONFIRM_VALUE, "exact"),
                    null, null));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // The deterministic replay-completion boundary
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Blocks until {@code automation_engine}, {@code command_dispatch_service}, and
     * {@code pending_command_ledger} have all reached {@code LIVE} — i.e. each has finished paging
     * through and delivering the entire seeded log and drained its catch-up window. The projection is
     * already {@code LIVE} (gated inside {@code start()}). A subscriber that trips to {@code SUSPENDED}
     * during the REPLAY pass (a replayed {@code onEvent} threw) is itself a replay-safety regression and
     * fails fast. Real-time polling (clock-independent, so it terminates under {@code Clock.fixed}).
     */
    private void awaitRuntimeSubscribersLive() {
        for (int poll = 0; poll < 250; poll++) {       // ~5s at 20ms
            SubscriberMode automation = subscriberMode("automation_engine");
            SubscriberMode dispatch = subscriberMode("command_dispatch_service");
            SubscriberMode ledger = subscriberMode("pending_command_ledger");
            SubscriberMode router = subscriberMode("integration_supervisor");
            SubscriberMode projection = subscriberMode("state_projection");
            failOnSuspended("automation_engine", automation);
            failOnSuspended("command_dispatch_service", dispatch);
            failOnSuspended("pending_command_ledger", ledger);
            failOnSuspended("integration_supervisor", router);
            failOnSuspended("state_projection", projection);
            if (automation == SubscriberMode.LIVE
                    && dispatch == SubscriberMode.LIVE
                    && ledger == SubscriberMode.LIVE
                    && router == SubscriberMode.LIVE
                    && projection == SubscriberMode.LIVE) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("the runtime subscribers (automation_engine, command_dispatch_service, "
                + "pending_command_ledger, integration_supervisor) did not all reach LIVE within ~5s "
                + "after boot — the seeded log's REPLAY -> TRANSITION -> LIVE catch-up did not "
                + "complete, so the replay-window side-effect snapshot cannot be taken "
                + "deterministically");
    }

    private static void failOnSuspended(String subscriberId, SubscriberMode mode) {
        if (mode == SubscriberMode.SUSPENDED) {
            throw new AssertionError("subscriber '" + subscriberId + "' tripped to SUSPENDED during the "
                    + "boot REPLAY pass — a replayed onEvent threw (a circuit-breaker trip), which is "
                    + "itself a pure-function-replay (D2) regression");
        }
    }

    private SubscriberMode subscriberMode(String subscriberId) {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> subscriberId.equals(snapshot.subscriberId()))
                .map(SubscriberSnapshot::mode)
                .findFirst()
                .orElse(SubscriberMode.COLD);
    }

    // ════════════════════════════════════════════════════════════════════════
    // The dispatch seam, observed via the durable event store (no production seam exists or is needed)
    // ════════════════════════════════════════════════════════════════════════

    /** Counts persisted events of {@code eventType} across the whole log (the seed is tiny). */
    private long countEventsOfType(String eventType) {
        return core.eventStore().readFrom(0L, 1000).events().stream()
                .filter(envelope -> envelope.eventType().equals(eventType))
                .count();
    }

    /** Polls the fake's recorder until it holds at least one envelope (the LIVE adapter seam). */
    private boolean awaitRecorderNonEmpty() {
        for (int poll = 0; poll < 250; poll++) {       // ~5s at 20ms
            if (!recordingFake.recordedCommands().isEmpty()) {
                return true;
            }
            sleepBriefly();
        }
        return false;
    }

    /**
     * Polls the durable store until a {@code command_dispatched} targeting {@code entityId} appears
     * (the LIVE positive control; since M9.1 the adapter recorder is asserted alongside this
     * durable seam). Real-time polling, clock-independent.
     */
    private boolean awaitCommandDispatchedFor(EntityId entityId) {
        Ulid target = entityId.value();
        for (int poll = 0; poll < 250; poll++) {       // ~5s at 20ms
            boolean found = core.eventStore().readFrom(0L, 1000).events().stream()
                    .filter(envelope -> envelope.eventType().equals(EventTypes.COMMAND_DISPATCHED))
                    .map(EventEnvelope::payload)
                    .anyMatch(payload -> payload instanceof CommandDispatchedEvent dispatched
                            && dispatched.targetEntityRef().equals(target));
            if (found) {
                return true;
            }
            sleepBriefly();
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // LIVE positive-control fixtures
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Seeds the live positive-control entity (with the {@code on_off} capability) and its
     * integration-routed device, so a LIVE {@code command_issued} for it dispatches
     * ({@code command_dispatched}). Mirrors {@code RunPipelineConfirmWiringTest.seedDeviceAndEntity}.
     */
    private void seedLiveControlDeviceAndEntity() {
        Capability onOff = StandardCapabilities.onOff();
        CapabilityInstance instance = new CapabilityInstance(
                onOff.capabilityId(), onOff.version(), onOff.namespace(), 0,
                onOff.attributeSchemas(), onOff.commandDefinitions(), onOff.confirmationPolicy());
        Entity entity = new Entity(liveEntityId, "live-control-light", EntityType.LIGHT,
                "Live Control Light", liveDeviceId, 0, null, true, List.of(), List.of(instance),
                EntityRole.PRIMARY, FIXED_INSTANT);
        // M9.1: the device row routes to the recording fake's derived id, so the LIVE
        // positive control exercises the REAL adapter seam (router join -> handle()).
        Device device = new Device(liveDeviceId, "live-control-device", "Live Control Device", "Acme",
                "Model", null, null, null, fakeIntegrationId, null, null, List.of(), Set.of(),
                FIXED_INSTANT);
        core.deviceRegistry().createDevice(device);
        core.entityRegistry().createEntity(entity);
    }

    /** Fires the named automation by publishing an {@code automation_invoked} on its subject. */
    private void fireManualTrigger(String slug) throws Exception {
        AutomationId automationId = core.automationRegistry().getBySlug(slug)
                .orElseThrow(() -> new AssertionError("positive-control automation '" + slug
                        + "' not loaded"))
                .automationId();
        core.eventPublisher().publishRoot(new EventDraft(
                EventTypes.AUTOMATION_INVOKED, 1, null, SubjectRef.automation(automationId),
                EventPriority.NORMAL, EventOrigin.AUTOMATION,
                new AutomationInvokedEvent("replay-safety-positive-control"), null, null));
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting the replay-safety boundary", ex);
        }
    }
}
