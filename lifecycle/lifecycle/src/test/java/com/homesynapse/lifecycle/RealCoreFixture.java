/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.Device;
import com.homesynapse.device.Entity;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.bus.InProcessEventBus;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;
import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * MEASURE-2b — the three ITs' boot shape, once: the REAL composition root on a
 * temp-dir SQLite store, the REAL zigbee adapter over the scripted NCP
 * ({@link ZigbeeHardwareFreeRig}), the SNZB and the Hue adopted and labeled.
 * {@link BusSoakIT}, {@link HeroLoopHardwareFreeIT} and {@link MeasureReadPathIT}
 * call {@link #boot}; every later scale question is a method call on the result.
 *
 * <p><strong>The boot is the extraction, not a redesign.</strong> {@link #boot}
 * performs the calls of {@code HeroLoopHardwareFreeIT.bootAndAdopt} at
 * {@code d1c2cbc} in its order — the form the two copies' tie-breaker named —
 * with that copy's three boot-smoke assertions (the composed schema names
 * {@code zigbee}; the Hue CT capability carries the measured 15 s window on the
 * policy and on the command definition). The configuration YAML is the caller's.
 *
 * <p><strong>The clock.</strong> The core and the rig share one injected
 * {@link TestClock}. Nothing here advances it except
 * {@link #seedPowerReports}, by the step its caller names, once per pumped
 * batch: a log seeded at one frozen instant is not a run's log — every time-window
 * read ({@code StandardExplanationService.locateTriggered}'s hint window) would hold
 * the whole log or none of it. Await loops are real-time polls, clock-independent.
 *
 * <p><strong>No wall clock lives here.</strong> The two timed methods take their
 * stopwatch from the caller ({@link MeasureReadPathIT}'s one
 * {@code stopwatchNanos} helper), so this class reads no time source of its own.
 *
 * <p><strong>The metering plug adopts by configuration, not by
 * {@code rig.adopt}.</strong> {@code ZigbeeIntegrationAdapter.adoptIfAccepted} is
 * the only path that runs the adoption-time reporting drive — the two formatting
 * reads without which the 0x0B04/0x0702 handlers stay silent (ENERGY-READ).
 * {@link #adoptGen4} therefore needs the Gen4 on the accept list:
 * {@link #withGen4AcceptListed} appends that section to a YAML.
 *
 * <p>Test-tree only, package-private: not a {@code testFixtures} source set until a
 * second module needs it. Not thread-safe — one test thread drives it.
 */
final class RealCoreFixture {

    static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAB"));

    /** Reports queued per {@code deliverAndCycle()} pump while seeding. */
    static final int SEED_BATCH = 100;

    /** The boot's readiness bound: 250 × 20 ms, the ITs' own (~5 s). */
    private static final int BOOT_LIVE_POLLS = 250;

    /**
     * The restart's readiness bound: 30,000 × 20 ms (~10 min). A slow restart at
     * run scale is a NUMBER for the caller, never this fixture's timeout;
     * {@code HomeSynapseCore.start()} carries its own ~30 s projection gates.
     */
    private static final int RESTART_LIVE_POLLS = 30_000;

    /** Unchanged store-head polls that end one seeded chunk (× 20 ms). */
    private static final int QUIET_POLLS = 5;

    /** Unchanged store-head polls that read as "the pipeline drained" (× 20 ms). */
    private static final int SETTLED_POLLS = 25;

    private static final int ELECTRICAL_MEASUREMENT_CLUSTER = 0x0B04;
    private static final int ATTRIBUTE_ACTIVE_POWER = 0x050B;

    private final Path tempDir;
    private final Path configDir;
    private TestClock clock;
    private ZigbeeHardwareFreeRig rig;
    private HomeSynapseCore core;
    private EntityId snzbEntity;
    private EntityId hueEntity;
    /** Power reports seeded so far — the alternation continues across calls. */
    private long powerReportsSeeded;

    private RealCoreFixture(Path tempDir) {
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir");
        this.configDir = tempDir.resolve("config");
    }

    /**
     * Boots the real core over the rig and adopts + labels the SNZB
     * ({@code motion}) and the Hue ({@code hero-light}). A failure anywhere stops
     * whatever was started before it propagates — a half-booted core never leaks
     * into the next test.
     *
     * @param tempDir    the test's temp dir: the store, the config dir and the
     *                   zigbee data dir live under it
     * @param configYaml the {@code homesynapse.yaml} text
     * @return the booted fixture
     * @throws Exception if the config cannot be written or the core cannot start
     */
    static RealCoreFixture boot(Path tempDir, String configYaml) throws Exception {
        RealCoreFixture fixture = new RealCoreFixture(tempDir);
        try {
            fixture.bootAndAdopt(Objects.requireNonNull(configYaml, "configYaml"));
            return fixture;
        } catch (Exception | Error failure) {
            fixture.close();
            throw failure;
        }
    }

    /**
     * Appends the zigbee accept list naming the metering-plug fixture to a YAML
     * that has no {@code integrations:} section of its own.
     *
     * @param configYaml a YAML without a top-level {@code integrations:} key
     * @return the YAML with {@code integrations.zigbee.adopt_devices: [GEN4]}
     */
    static String withGen4AcceptListed(String configYaml) {
        return configYaml + "integrations:\n"
                + "  zigbee:\n"
                + "    adopt_devices:\n"
                + "      - \"" + ieeeHex(ZigbeeHardwareFreeRig.GEN4_IEEE) + "\"\n";
    }

    HomeSynapseCore core() {
        return core;
    }

    ZigbeeHardwareFreeRig rig() {
        return rig;
    }

    TestClock clock() {
        return clock;
    }

    EntityId snzbEntity() {
        return snzbEntity;
    }

    EntityId hueEntity() {
        return hueEntity;
    }

    Path tempDir() {
        return tempDir;
    }

    // ════════════════════════════════════════════════════════════════════════
    // The boot shape — HeroLoopHardwareFreeIT.bootAndAdopt at d1c2cbc
    // ════════════════════════════════════════════════════════════════════════

    private void bootAndAdopt(String configYaml) throws Exception {
        clock = TestClock.createDefault();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), configYaml);
        rig = new ZigbeeHardwareFreeRig(clock, () -> core.deviceRegistry(),
                () -> core.registryProjection(),
                tempDir.resolve("zigbee"));
        startCore(BOOT_LIVE_POLLS);
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

    /**
     * The composition root over the SAME store path, config dir, clock and rig —
     * the boot's one construction site and the restart's.
     */
    private void startCore(int livePolls) throws Exception {
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                configDir,
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
        awaitRuntimeSubscribersLive(livePolls);
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

    // ════════════════════════════════════════════════════════════════════════
    // The scale surface
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Announces the metering-plug fixture and pumps one cycle: the REAL interview,
     * the accept-listed adoption and the adoption-time reporting drive (binds, the
     * two formatting reads, the scaled configures) — the ENERGY-READ path.
     *
     * @return endpoint → entity id of the adopted plug
     * @throws AssertionError if the plug did not adopt — the fixture was booted
     *         without {@link #withGen4AcceptListed}
     */
    Map<Integer, EntityId> adoptGen4() {
        rig.announceGen4();
        rig.deliverAndCycle();
        Optional<Device> plug = core.deviceRegistry().findByHardwareIdentifier(
                "zigbee", ieeeHex(ZigbeeHardwareFreeRig.GEN4_IEEE));
        if (plug.isEmpty()) {
            throw new AssertionError("the metering-plug fixture did not adopt: boot "
                    + "with a config from RealCoreFixture.withGen4AcceptListed(...) — "
                    + "only the accept-listed path runs the formatting reads");
        }
        Map<Integer, EntityId> entityIds = new HashMap<>();
        for (Entity entity : core.entityRegistry()
                .listEntitiesByDevice(plug.get().deviceId())) {
            entityIds.put(entity.endpointIndex(), entity.entityId());
        }
        return Map.copyOf(entityIds);
    }

    /**
     * Seeds {@code count} 0x0B04 {@code ActivePower} reports from the metering plug
     * through the REAL ingestion path — the raw value alternating
     * {@code watts0}/{@code watts0 + 1}, so every report is also a state change —
     * pumping every {@value #SEED_BATCH} and advancing the shared clock by
     * {@code clockStepPerBatch} before each pump. Returns when the store holds at
     * least one row per report and its head has stopped moving (the projection's
     * {@code state_changed} rows land on its own thread).
     *
     * <p>The alternation is not a choice: the ingestion dedup drops an unchanged
     * payload on a consecutive TSN inside 10 s. So a report lands as TWO rows, and
     * the second is a DERIVED write paced by production's token bucket (AMD-43
     * §3.6.4: 200/s) — about 400 rows/s sustained, whatever the store can take.
     *
     * @param count             reports to seed, {@code >= 1}
     * @param watts0            the lower raw {@code ActivePower} value
     * @param clockStepPerBatch the domain time one batch spans; {@link Duration#ZERO}
     *                          freezes the clock
     * @param stopwatchNanos    the caller's wall-time source
     * @return the nanos from the first report to the store head's last move
     */
    long seedPowerReports(int count, int watts0, Duration clockStepPerBatch,
            LongSupplier stopwatchNanos) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1, got " + count);
        }
        long target = rows() + count;
        long start = stopwatchNanos.getAsLong();
        for (int i = 1; i <= count; i++) {
            rig.reportAttributes(ZigbeeHardwareFreeRig.GEN4_IEEE,
                    ZigbeeHardwareFreeRig.GEN4_ENDPOINT, ELECTRICAL_MEASUREMENT_CLUSTER,
                    Map.of(ATTRIBUTE_ACTIVE_POWER,
                            watts0 + (int) (powerReportsSeeded++ & 1L)));
            if (i % SEED_BATCH == 0 || i == count) {
                clock.advance(clockStepPerBatch);
                rig.deliverAndCycle();
            }
        }
        return awaitStoreQuiet(target, stopwatchNanos) - start;
    }

    /**
     * One hero loop, confirmed — the run maker: {@code occupied} false then true
     * (an edge whatever the prior state), the engine's {@code turn_on}, the Hue's
     * {@code on=true} report, the {@code state_confirmed}, the run's
     * {@code automation_completed}. Awaits read the type index past the position
     * the pulse started at, so they cost the same at any store size.
     */
    void pulseMotion() {
        long before = rows();
        rig.reportOccupied(false);
        rig.deliverAndCycle();
        rig.reportOccupied(true);
        rig.deliverAndCycle();
        awaitTypeAfter(EventTypes.COMMAND_ISSUED, before, "the pulse's command_issued");
        rig.reportOnOff(true);
        rig.deliverAndCycle();
        awaitTypeAfter(EventTypes.STATE_CONFIRMED, before, "the pulse's state_confirmed");
        awaitTypeAfter(EventTypes.AUTOMATION_COMPLETED, before,
                "the pulse's automation_completed");
    }

    /**
     * Stops the core and boots a new one on the SAME store, config, clock and rig.
     * Labels are registry-direct and log-invisible: they do NOT survive.
     *
     * @param stopwatchNanos the caller's wall-time source
     * @return the nanos from the composition root's constructor to the five
     *         runtime subscribers reading LIVE ({@code stop()} is not in it)
     * @throws Exception if the new core cannot start
     */
    long restart(LongSupplier stopwatchNanos) throws Exception {
        core.stop();
        long start = stopwatchNanos.getAsLong();
        startCore(RESTART_LIVE_POLLS);
        return stopwatchNanos.getAsLong() - start;
    }

    /**
     * Blocks until the store head has not moved for half a second — the pipeline
     * drained, so a timed read that follows contends with no writer.
     */
    void settle() {
        long head = -1L;
        int quiet = 0;
        for (int poll = 0; poll < 30_000; poll++) {
            long now = rows();
            if (now != head) {
                head = now;
                quiet = 0;
            } else if (++quiet >= SETTLED_POLLS) {
                return;
            }
            sleepBriefly();
        }
        throw timeoutDiagnostic("the store head settling (head " + head + ")",
                OptionalLong.of(head));
    }

    /** The store's head: {@code EventStore.latestPosition()}. */
    long rows() {
        return core.eventStore().latestPosition();
    }

    /** The database file alone. */
    long dbBytes() {
        return sizeOf("homesynapse-events.db");
    }

    /** The write-ahead log beside it; 0 when absent. */
    long walBytes() {
        return sizeOf("homesynapse-events.db-wal");
    }

    /** The WAL index beside it; 0 when absent. */
    long shmBytes() {
        return sizeOf("homesynapse-events.db-shm");
    }

    /** The database file, its {@code -wal} and its {@code -shm}, summed. */
    long storeBytes() {
        return dbBytes() + walBytes() + shmBytes();
    }

    /** Stops the core; safe on a half-booted fixture and safe to repeat. */
    void close() {
        if (core != null) {
            core.stop();
        }
    }

    private long sizeOf(String fileName) {
        Path file = tempDir.resolve(fileName);
        try {
            return Files.exists(file) ? Files.size(file) : 0L;
        } catch (IOException e) {
            throw new IllegalStateException("cannot size " + file, e);
        }
    }

    private static String ieeeHex(long ieee) {
        return String.format(Locale.ROOT, "0x%016X", ieee);
    }

    // ── awaits (real-time polls; clock-independent) ─────────────────────────

    private void awaitRuntimeSubscribersLive(int polls) {
        for (int poll = 0; poll < polls; poll++) {
            if (subscriberMode("automation_engine") == SubscriberMode.LIVE
                    && subscriberMode("command_dispatch_service") == SubscriberMode.LIVE
                    && subscriberMode("pending_command_ledger") == SubscriberMode.LIVE
                    && subscriberMode("integration_supervisor") == SubscriberMode.LIVE
                    && subscriberMode("state_projection") == SubscriberMode.LIVE) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("runtime subscribers did not reach LIVE within ~"
                + (polls / 50) + "s");
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
        long lastRegistrationFact = core.eventStore().readFrom(0L, 2000).events().stream()
                .filter(event -> event.eventType().equals(EventTypes.DEVICE_REGISTERED)
                        || event.eventType().equals(EventTypes.ENTITY_REGISTERED))
                .mapToLong(EventEnvelope::globalPosition)
                .max()
                .orElseThrow(() -> new AssertionError(
                        "no registration facts in the log after adopt()"));
        awaitTrue(() -> registryProjectionCheckpoint() >= lastRegistrationFact,
                "the registry projection consuming the adoption events",
                () -> lastRegistrationFact);
    }

    private long registryProjectionCheckpoint() {
        return core.eventBus().subscribers().stream()
                .filter(snapshot -> RegistryProjectionSubscriber.SUBSCRIBER_ID
                        .equals(snapshot.subscriberId()))
                .mapToLong(SubscriberSnapshot::checkpoint)
                .findFirst()
                .orElse(0L);
    }

    private void awaitTypeAfter(String eventType, long afterPosition, String what) {
        awaitTrue(() -> !core.eventStore().readByType(eventType, afterPosition, 1)
                .events().isEmpty(), what, () -> afterPosition);
    }

    /**
     * Polls the store head until it is at or past {@code target} and has not moved
     * for {@value #QUIET_POLLS} polls.
     *
     * @return the stopwatch reading at the head's last observed move
     */
    private long awaitStoreQuiet(long target, LongSupplier stopwatchNanos) {
        long head = -1L;
        long lastMove = stopwatchNanos.getAsLong();
        int quiet = 0;
        for (int poll = 0; poll < 30_000; poll++) {
            long now = rows();
            if (now != head) {
                head = now;
                lastMove = stopwatchNanos.getAsLong();
                quiet = 0;
            } else if (head >= target && ++quiet >= QUIET_POLLS) {
                return lastMove;
            }
            sleepBriefly();
        }
        throw timeoutDiagnostic("the seeded reports reaching the store (head "
                + head + " of " + target + ")", OptionalLong.of(target));
    }

    private void awaitTrue(BooleanSupplier condition, String what) {
        awaitTrue(condition, what, () -> -1L);
    }

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
     * FIX-2a (A) / FIX-2b-i (B), the hero IT's form: the bus reading printed with
     * the thread dump and thrown as the message. The store head is
     * {@code latestPosition()} — the hero IT's 2,000-envelope read is not a head
     * at run scale. An instrument never becomes the failure channel.
     */
    private AssertionError timeoutDiagnostic(String what, OptionalLong awaited) {
        String reading;
        try {
            long storeHead = core.eventStore().latestPosition();
            // BUS-ORDER-1: the cursor= reading needs the concrete bus (lastDelivered
            // is concrete-only, the abandon() precedent).
            InProcessEventBus bus = (InProcessEventBus) core.eventBus();
            reading = BusAwaitDiagnostic.render(what, storeHead, awaited,
                    bus.subscribers(), bus::lastDelivered);
        } catch (RuntimeException gatherFailure) {
            reading = "timed out awaiting " + what
                    + " (bus.await_timeout unavailable: " + gatherFailure + ")";
        }
        System.out.println(reading + "\n" + BusThreadDump.capture(tempDir));
        return new AssertionError(reading);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the real core", ex);
        }
    }
}
