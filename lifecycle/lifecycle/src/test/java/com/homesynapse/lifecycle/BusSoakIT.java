/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static com.homesynapse.lifecycle.BusPositionCensusIT.NO_AWAITED_POSITION;
import static com.homesynapse.lifecycle.BusPositionCensusIT.allEvents;
import static com.homesynapse.lifecycle.BusPositionCensusIT.awaitSettledCensus;
import static com.homesynapse.lifecycle.BusPositionCensusIT.confirmedFor;
import static com.homesynapse.lifecycle.BusPositionCensusIT.countCommandIssued;
import static com.homesynapse.lifecycle.BusPositionCensusIT.heroMotionConfigYaml;
import static com.homesynapse.lifecycle.BusPositionCensusIT.newestCommandIssued;
import static com.homesynapse.lifecycle.BusPositionCensusIT.newestReportedPosition;
import static com.homesynapse.lifecycle.BusPositionCensusIT.renderTokens;
import static com.homesynapse.lifecycle.BusPositionCensusIT.reportedCount;
import static com.homesynapse.lifecycle.BusPositionCensusIT.sleepBriefly;
import static com.homesynapse.lifecycle.BusPositionCensusIT.timeoutDiagnostic;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.device.Entity;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;
import com.homesynapse.integration.zigbee.ZigbeeIntegrationFactory;
import com.homesynapse.lifecycle.BusPositionCensusIT.SettledCensus;
import com.homesynapse.lifecycle.BusPositionCensusIT.SubscriberCensus;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * FIX-2a (C) — the desk's reproduction of OR-BUS-SILENT-DROP: K hero loops in
 * ONE booted core. Each loop is the hero test's steps 2–4 — {@code occupied=true}
 * at the scripted NCP → the REAL ingestion → the projection's edge → the REAL
 * engine → dispatch → the REAL adapter → the On frame back at the scripted NCP →
 * the confirm — then {@code occupied} back to {@code false} so the next report
 * is an edge again. Per loop the delivery latency is measured; at the end the
 * distribution, the anomaly count and the position census are printed:
 * <pre>
 * bus.soak: loops=&lt;K&gt; ok=&lt;n&gt; timed_out=&lt;m&gt; p50_ms=&lt;x&gt; p99_ms=&lt;y&gt; max_ms=&lt;z&gt; anomalies=&lt;a&gt;
 * bus.position_census: …            (one per subscriber; BusPositionCensusIT's grammar)
 * bus.position_census_total: runs=&lt;n&gt; subscribers=&lt;K&gt; missed=&lt;n&gt; unscored_missed=&lt;u&gt;
 * </pre>
 * The assertions: {@code timed_out == 0}, {@code anomalies == 0}, {@code missed == 0}
 * for every scored subscriber. A loop that times out FAILS the test with the (A)
 * diagnostic as its message (the summary lines are printed first, then the error
 * is rethrown — never swallowed, never retried): that failure IS the instrument
 * working — the class caught in the act, with every subscriber's checkpoint beside
 * the store head.
 *
 * <p><strong>Wall clock — the one lawful exception, named here.</strong> The core
 * and the rig run on the injected {@link TestClock} (never advanced in this
 * test). {@code System.nanoTime()} appears in this class ONLY to measure the
 * delivery latency — the interval between {@code reportOccupied(true)} +
 * {@code deliverAndCycle()} returning and the first poll that sees the On frame at
 * the scripted NCP. A latency is a wall-clock quantity by definition; the injected
 * clock cannot be its source. Resolution is the 20 ms poll interval. Percentiles
 * are nearest-rank over the completed loops' latencies.</p>
 *
 * <p><strong>K.</strong> {@value #DEFAULT_LOOPS} by default; the system property
 * {@value #LOOPS_PROPERTY} overrides, and the environment variable
 * {@value #LOOPS_ENV} is the fallback for a desk whose test task forwards no
 * {@code -D} to the forked test JVM (this build's test task forwards none).</p>
 *
 * <p><strong>Anomalies.</strong> A logback {@link ListAppender} on the
 * {@code HomeSynapseCore} logger, attached BEFORE boot and detached after stop,
 * counts every message starting with {@code bus.delivery_anomaly} — the FIX-1a
 * detector's one token. The count is read after the census settles, when no
 * subscriber thread is appending.</p>
 *
 * <p>Harness: the {@link HeroLoopHardwareFreeIT} boot shape over the
 * {@link ZigbeeHardwareFreeRig}, copied; the census and the store reads are
 * {@link BusPositionCensusIT}'s package-private statics. The rig's
 * {@code sentZclFrames()} accumulates across loops, so frames are COUNTED, never
 * {@code isPresent()}.</p>
 */
@DisplayName("BusSoakIT — K hero loops in ONE core: delivery latency p50/p99/max, the anomaly count, the position census; a timed-out loop fails with the bus reading (FIX-2a C)")
@Tag("bus-soak")
final class BusSoakIT {

    static final String LOOPS_PROPERTY = "homesynapse.soak.loops";
    static final String LOOPS_ENV = "HOMESYNAPSE_SOAK_LOOPS";
    static final int DEFAULT_LOOPS = 20;

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAB"));

    private TestClock clock;
    private ZigbeeHardwareFreeRig rig;
    private HomeSynapseCore core;
    private EntityId snzbEntity;
    private EntityId hueEntity;
    private ListAppender<ILoggingEvent> anomalyCapture;
    /** FIX-2b-i: the test's temp dir, held so a timeout's thread dump has a home. */
    private Path tempDir;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    BusSoakIT() {
    }

    @AfterEach
    void tearDown() {
        if (core != null) {
            core.stop();
        }
        if (anomalyCapture != null) {
            coreLogger().detachAppender(anomalyCapture);
            anomalyCapture.stop();
        }
    }

    @Test
    @DisplayName("soak: K motion→On-frame→confirm loops in one core; bus.soak + bus.position_census tokens; timed_out=0, anomalies=0, missed=0 for every scored subscriber")
    void soak_kHeroLoops_reportsLatencyAndAnomalies(@TempDir Path tempDir) throws Exception {
        int loops = configuredLoops();
        anomalyCapture = attachAnomalyCapture();   // before boot: a boot-time drop counts too
        bootAndAdopt(tempDir);

        // The baseline: occupied=false, so the first true is a real edge.
        rig.reportOccupied(false);
        rig.deliverAndCycle();
        awaitTrue(() -> reportedCount(events(), "occupied", "false") >= 1,
                "the occupied=false baseline report");

        List<Long> latencyNanos = new ArrayList<>(loops);
        int ok = 0;
        try {
            for (int loop = 0; loop < loops; loop++) {
                final int expected = loop + 1;

                // The motion edge → the On frame: the measured interval.
                rig.reportOccupied(true);
                rig.deliverAndCycle();
                long t0 = System.nanoTime();
                long t1 = awaitFrameCount(0x0006, 0x01, expected,
                        "On frame #" + expected + " reaching the scripted NCP (loop " + loop
                                + " of " + loops + ")",
                        () -> newestReportedPosition(events(), "occupied", "true"));
                latencyNanos.add(t1 - t0);

                // The confirm, the hero test's way: the loop's command_issued(turn_on),
                // the on=true report, the state_confirmed joined to that command.
                awaitTrue(() -> countCommandIssued(events(), "turn_on") >= expected,
                        "command_issued(turn_on) #" + expected);
                EventEnvelope issued = newestCommandIssued(events(), "turn_on");
                rig.reportOnOff(true);
                rig.deliverAndCycle();
                awaitTrue(() -> confirmedFor(events(), issued),
                        "state_confirmed for turn_on #" + expected,
                        () -> newestReportedPosition(events(), "on", "true"));
                ok++;

                // occupied back to false, and its state_reported in the store.
                final int expectedFalse = loop + 2;
                rig.reportOccupied(false);
                rig.deliverAndCycle();
                awaitTrue(() -> reportedCount(events(), "occupied", "false") >= expectedFalse,
                        "the occupied=false report #" + expectedFalse + " (loop " + loop + ")");
            }
        } catch (AssertionError timedOut) {
            // The instrument working: the diagnostic is printed and IS the message.
            // The run's summary and the instantaneous census ride beside it.
            List<SubscriberCensus> census = BusPositionCensusIT.census(events(),
                    core.eventBus().subscribers());
            printSummary(loops, ok, 1, latencyNanos, anomalyCount(), census);
            throw timedOut;
        }

        SettledCensus settled = awaitSettledCensus(core);
        long anomalies = anomalyCount();
        printSummary(loops, ok, 0, latencyNanos, anomalies, settled.census());
        if (!settled.settled()) {
            throw timeoutDiagnostic(core, tempDir,
                    "the position census settling to missed=0 for every scored subscriber",
                    settled.firstScoredMiss());
        }
        assertThat(ok).as("bus.soak: ok").isEqualTo(loops);
        assertThat(anomalies).as("bus.soak: anomalies").isZero();
        for (SubscriberCensus census : settled.census()) {
            if (census.scored()) {
                assertThat(census.missed())
                        .as("bus.position_census: subscriber=%s missed", census.subscriberId())
                        .isZero();
            }
        }
    }

    // ── the soak's own instruments ─────────────────────────────────────────

    /**
     * K: the system property, else the environment variable, else the default.
     *
     * @return the loop count, {@code >= 1}
     */
    static int configuredLoops() {
        String configured = System.getProperty(LOOPS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(LOOPS_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_LOOPS;
        }
        int loops = Integer.parseInt(configured.trim());
        if (loops < 1) {
            throw new IllegalArgumentException(
                    LOOPS_PROPERTY + " must be >= 1, got " + loops);
        }
        return loops;
    }

    private void printSummary(int loops, int ok, int timedOut, List<Long> latencyNanos,
            long anomalies, List<SubscriberCensus> census) {
        List<Long> sorted = new ArrayList<>(latencyNanos);
        Collections.sort(sorted);
        // The host shape the numbers were read on (the desk is not the runner):
        // the carrier count the JVM sees and the VT scheduler's parallelism knob.
        String vtParallelism = System.getProperty("jdk.virtualThreadScheduler.parallelism");
        System.out.println("bus.soak_host: available_processors="
                + Runtime.getRuntime().availableProcessors()
                + " vt_parallelism=" + (vtParallelism == null ? "default" : vtParallelism));
        System.out.println("bus.soak: loops=" + loops + " ok=" + ok + " timed_out=" + timedOut
                + " p50_ms=" + percentileMillis(sorted, 0.50)
                + " p99_ms=" + percentileMillis(sorted, 0.99)
                + " max_ms=" + percentileMillis(sorted, 1.00)
                + " anomalies=" + anomalies);
        System.out.println(renderTokens(census, ok));
    }

    /**
     * Nearest-rank percentile in whole milliseconds; 0 when nothing completed.
     *
     * @param sortedNanos the completed loops' latencies, ascending
     * @param percentile  in {@code (0, 1]}
     * @return the value at rank {@code ceil(p × n)}, rounded to ms
     */
    static long percentileMillis(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(percentile * sortedNanos.size());
        int index = Math.max(0, Math.min(sortedNanos.size() - 1, rank - 1));
        return Math.round(sortedNanos.get(index) / 1_000_000.0);
    }

    /**
     * Polls until the scripted NCP holds {@code expected} frames of the cluster/command.
     *
     * @return {@code System.nanoTime()} at the first poll that saw the count — the
     *         latency measurement's end mark
     */
    private long awaitFrameCount(int clusterId, int commandId, int expected, String what,
            LongSupplier awaitedPosition) {
        for (int poll = 0; poll < 500; poll++) {
            if (frameCount(clusterId, commandId) >= expected) {
                return System.nanoTime();
            }
            sleepBriefly();
        }
        long position = awaitedPosition.getAsLong();
        throw timeoutDiagnostic(core, tempDir, what,
                position < 0 ? OptionalLong.empty() : OptionalLong.of(position));
    }

    private long frameCount(int clusterId, int commandId) {
        return rig.sentZclFrames().stream()
                .filter(frame -> frame.clusterId() == clusterId
                        && frame.commandId() == commandId)
                .count();
    }

    private long anomalyCount() {
        return List.copyOf(anomalyCapture.list).stream()
                .filter(event -> event.getFormattedMessage().startsWith("bus.delivery_anomaly"))
                .count();
    }

    private static ListAppender<ILoggingEvent> attachAnomalyCapture() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        coreLogger().addAppender(appender);
        return appender;
    }

    private static ch.qos.logback.classic.Logger coreLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HomeSynapseCore.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness — the HeroLoopHardwareFreeIT boot shape, copied
    // ════════════════════════════════════════════════════════════════════════

    private void bootAndAdopt(Path tempDir) throws Exception {
        this.tempDir = tempDir;
        clock = TestClock.createDefault();
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("homesynapse.yaml"), heroMotionConfigYaml());
        rig = new ZigbeeHardwareFreeRig(clock, () -> core.deviceRegistry(),
                () -> core.registryProjection(),
                tempDir.resolve("zigbee"));
        core = new HomeSynapseCore(
                tempDir.resolve("homesynapse-events.db"),
                configDir,
                HomeSynapseConfig.testing(),
                clock,
                TEST_HOME_ID,
                null,
                List.of(rig.factory()));
        core.start();
        core.registerIntegrationSchema(ZigbeeIntegrationFactory.INTEGRATION_TYPE,
                ZigbeeIntegrationFactory.configSchemaJson());
        awaitRuntimeSubscribersLive();
        awaitTrue(rig::sessionStarted, "the EZSP session over the scripted NCP");

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

    /** The M9.5-DURc registry-projection checkpoint barrier (see the hero IT's javadoc). */
    private void awaitRegistryProjectionCaughtUp() {
        long lastRegistrationFact = events().stream()
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

    private List<EventEnvelope> events() {
        return allEvents(core.eventStore());
    }

    private void awaitTrue(BooleanSupplier condition, String what) {
        awaitTrue(condition, what, () -> NO_AWAITED_POSITION);
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
        throw timeoutDiagnostic(core, tempDir, what,
                position < 0 ? OptionalLong.empty() : OptionalLong.of(position));
    }
}
