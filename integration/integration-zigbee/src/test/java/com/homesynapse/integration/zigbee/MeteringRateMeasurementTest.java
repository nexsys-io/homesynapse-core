/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationAdapter;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MEASURE-2 / IR-28 (ENERGY-READ R7, T8) — the sustained-rate instrument on the
 * hardware-free rig. A MEASUREMENT, neither red nor green: it passes when it
 * prints its one {@code MEASURE2} line, and it is skipped unless asked for.
 *
 * <p><strong>What it drives.</strong> The REAL adapter over the scripted NCP
 * ({@link ZigbeeHardwareFreeRig}): the metering-plug fixture announces, is
 * interviewed and — accept-listed — adopted, the adoption drive READS its
 * scripted formatting (ACPower 1/100; summation 1/1,000,000 kWh) and configures
 * the scaled thresholds; then N {@code ActivePower} reports per second for M
 * minutes of the rig's {@link TestClock} (advanced 1/N s per frame — no wall
 * clock drives a frame) enter through the real 0x0045 parse, the real dedup,
 * the real handler table and the real {@code state_reported} publish. Raw
 * values alternate 8,000 / 8,010 (80.0 W / 80.1 W at ÷100) so consecutive
 * payloads differ — the dedup never folds them.
 *
 * <p><strong>What it measures, and the limits of each number.</strong>
 * {@code rate} = {@code state_reported} events per WALL second, frames driven
 * back-to-back — the ingestion path's sustained ceiling on this desk, not a
 * radio's delivery rate. {@code store_*}: the store is the in-memory
 * {@code RecordingEventPublisher} — this module depends on integration-api
 * only (LTD-17), so no SQLite store is reachable from here; rows = envelopes
 * held, bytes = the UTF-8 length of the five {@code StateReportedEvent} slots
 * summed over the {@code state_reported} envelopes — a payload FLOOR, not a
 * row size (no envelope columns, no JSON keys, no index). {@code heap_*}: a
 * {@link Runtime} used-heap reading after a {@code System.gc()} HINT — a
 * proxy. {@code wall_s}: {@code System.nanoTime()}, read ONLY by the two
 * timing lines below, as the instrument.
 *
 * <p><strong>Run it by path, once, on the desk:</strong>
 * {@code ./gradlew :integration:integration-zigbee:test --tests
 * '*MeteringRateMeasurementTest*' -Dhomesynapse.measure2=true --offline -i}.
 * A {@code -D} on the gradlew line sets the property on the GRADLE JVM, never
 * on the forked test worker (build-logic's own FIX-2b-i note; only
 * {@code homesynapse.soak.loops} is forwarded), so the switch also reads the
 * environment — {@code HOMESYNAPSE_MEASURE2=true} — the {@code BusSoakIT}
 * precedent. N and M: {@code homesynapse.measure2.n} /
 * {@code HOMESYNAPSE_MEASURE2_N} (default 20 frames/s) and
 * {@code homesynapse.measure2.minutes} / {@code HOMESYNAPSE_MEASURE2_MINUTES}
 * (default 3).
 */
@DisplayName("MEASURE-2 — the sustained metering ingestion rate on the "
        + "hardware-free rig (ENERGY-READ R7; a measurement, skipped unless asked)")
class MeteringRateMeasurementTest {

    static final String ENABLED_PROPERTY = "homesynapse.measure2";
    static final String ENABLED_ENV = "HOMESYNAPSE_MEASURE2";
    static final String RATE_PROPERTY = "homesynapse.measure2.n";
    static final String RATE_ENV = "HOMESYNAPSE_MEASURE2_N";
    static final String MINUTES_PROPERTY = "homesynapse.measure2.minutes";
    static final String MINUTES_ENV = "HOMESYNAPSE_MEASURE2_MINUTES";
    static final int DEFAULT_FRAMES_PER_SECOND = 20;
    static final int DEFAULT_MINUTES = 3;

    @TempDir
    Path tempDir;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    MeteringRateMeasurementTest() {
    }

    @Test
    @DisplayName("T8: N ActivePower reports/s for M minutes → the one MEASURE2 line")
    void sustainedMeteringIngestionRate() throws Exception {
        assumeTrue("true".equalsIgnoreCase(knob(ENABLED_PROPERTY, ENABLED_ENV)),
                "MEASURE-2 is a desk measurement, not a gate: run it by path with "
                        + "-D" + ENABLED_PROPERTY + "=true (and " + ENABLED_ENV
                        + "=true in the environment — a -D on the gradlew line "
                        + "never reaches the forked test JVM)");
        int framesPerSecond = positive(knob(RATE_PROPERTY, RATE_ENV),
                DEFAULT_FRAMES_PER_SECOND);
        int minutes = positive(knob(MINUTES_PROPERTY, MINUTES_ENV),
                DEFAULT_MINUTES);
        int frames = framesPerSecond * minutes * 60;

        TestClock clock = TestClock.createDefault();
        RecordingEventPublisher publisher = new RecordingEventPublisher(clock);
        InMemoryDeviceRegistry deviceRegistry = new InMemoryDeviceRegistry();
        InMemoryEntityRegistry entityRegistry = new InMemoryEntityRegistry();
        RegistryProjection projection =
                new RegistryProjection(deviceRegistry, entityRegistry);
        ZigbeeHardwareFreeRig rig = new ZigbeeHardwareFreeRig(clock,
                () -> deviceRegistry, () -> projection, tempDir.resolve("zigbee"));
        IntegrationAdapter adapter = rig.factory().create(new IntegrationContext(
                new IntegrationId(UlidFactory.generate(clock)), "zigbee", publisher,
                entityRegistry, unusedQueryService(), unusedHealthReporter(),
                acceptListing(ZigbeeHardwareFreeRig.GEN4_IEEE),
                null, null, null, null, null));
        adapter.initialize();
        // Driven mode: run() opens the scripted transport, negotiates the
        // session and parks; the rig owns the cycle cadence from this thread.
        AtomicReference<Exception> runFailure = new AtomicReference<>();
        Thread runner = Thread.ofPlatform().daemon(true)
                .name("measure2-adapter-run").start(() -> {
                    try {
                        adapter.run();
                    } catch (Exception e) {
                        runFailure.set(e);
                    }
                });
        try {
            for (int i = 0; i < 2_000 && !rig.sessionStarted()
                    && runFailure.get() == null; i++) {
                Thread.sleep(5);
            }
            assertThat(runFailure.get()).as("the adapter's run()").isNull();
            assertThat(rig.sessionStarted())
                    .as("the EZSP session over the scripted NCP").isTrue();

            // Announce → interview → accept-listed adoption → the reporting
            // drive (the two formatting reads + the scaled configures).
            rig.announceGen4();
            rig.deliverAndCycle();
            assertThat(publisher.ofType(EventTypes.DEVICE_ADOPTED).count())
                    .as("the metering fixture adopted through the real path")
                    .isEqualTo(1);

            long eventsBefore = stateReported(publisher).size();
            long rowsBefore = publisher.published().size();
            long bytesBefore = payloadBytes(stateReported(publisher));
            long heapBeforeMb = usedHeapMb();
            Duration step = Duration.ofNanos(1_000_000_000L / framesPerSecond);

            long startNanos = System.nanoTime();   // the instrument: wall time
            for (int i = 0; i < frames; i++) {
                rig.reportAttributes(ZigbeeHardwareFreeRig.GEN4_IEEE,
                        ZigbeeHardwareFreeRig.GEN4_ENDPOINT,
                        ElectricalMeasurementHandler.CLUSTER_ID,
                        Map.of(ElectricalMeasurementHandler.ATTRIBUTE_ACTIVE_POWER,
                                (i & 1) == 0 ? 8_000 : 8_010));
                clock.advance(step);
                rig.deliverAndCycle();
            }
            long wallNanos = System.nanoTime() - startNanos;   // the instrument

            List<EventEnvelope> reported = stateReported(publisher);
            long events = reported.size() - eventsBefore;
            double wallSeconds = wallNanos / 1_000_000_000.0;
            assertThat(events)
                    .as("the fixture's reports reach state_reported — a zero "
                            + "here is a broken fixture, not a measurement")
                    .isPositive();
            StateReportedEvent last =
                    (StateReportedEvent) reported.get(reported.size() - 1).payload();
            assertThat(last.attributeKey()).isEqualTo("power_w");

            System.out.println(String.format(Locale.ROOT,
                    "MEASURE2 rate=%.1f frames=%d events=%d dropped=%d "
                            + "store_rows_before=%d store_rows_after=%d "
                            + "store_bytes_before=%d store_bytes_after=%d "
                            + "heap_before_mb=%d heap_after_mb=%d wall_s=%.3f "
                            + "n_per_s=%d minutes=%d "
                            + "store=in_memory_recording_publisher "
                            + "store_bytes=payload_utf8_floor",
                    events / wallSeconds, frames, events, frames - events,
                    rowsBefore, publisher.published().size(),
                    bytesBefore, payloadBytes(reported),
                    heapBeforeMb, usedHeapMb(), wallSeconds,
                    framesPerSecond, minutes));
        } finally {
            adapter.close();
            runner.join(5_000);
        }
    }

    private static List<EventEnvelope> stateReported(
            RecordingEventPublisher publisher) {
        return publisher.ofType(EventTypes.STATE_REPORTED).toList();
    }

    /** The payload floor: the five StateReportedEvent slots, UTF-8, summed. */
    private static long payloadBytes(List<EventEnvelope> reported) {
        long bytes = 0;
        for (EventEnvelope envelope : reported) {
            StateReportedEvent payload = (StateReportedEvent) envelope.payload();
            bytes += utf8(payload.attributeKey()) + utf8(payload.value())
                    + utf8(payload.unit()) + utf8(payload.rawProtocolValue())
                    + utf8(payload.rawProtocolUnit());
        }
        return bytes;
    }

    private static int utf8(String slot) {
        return slot == null ? 0 : slot.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Used heap in MB after a gc HINT — a proxy, disclosed. */
    private static long usedHeapMb() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
    }

    /** The system property, else the environment variable (the BusSoakIT idiom). */
    private static String knob(String property, String environment) {
        String configured = System.getProperty(property);
        return configured == null || configured.isBlank()
                ? System.getenv(environment) : configured;
    }

    private static int positive(String configured, int fallback) {
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ── inert context stubs (the ZigbeeReportingDriveTest idiom) ────────────

    private static ConfigurationAccess acceptListing(long ieee) {
        List<Object> adoptDevices = List.of(String.format("0x%016x", ieee));
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return Map.of(ZigbeeIntegrationAdapter.ADOPT_DEVICES_KEY,
                        adoptDevices);
            }

            @Override
            public Optional<String> getString(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> getInt(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> getBoolean(String key) {
                return Optional.empty();
            }
        };
    }

    private static StateQueryService unusedQueryService() {
        return new StateQueryService() {
            @Override
            public Optional<EntityState> getState(EntityId entityId) {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public StateSnapshot getSnapshot() {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public long getViewPosition() {
                return 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }
        };
    }

    private static HealthReporter unusedHealthReporter() {
        return new HealthReporter() {
            @Override
            public void reportHeartbeat() {
            }

            @Override
            public void reportKeepalive(Instant lastSuccess) {
            }

            @Override
            public void reportError(Throwable error) {
            }

            @Override
            public void reportHealthTransition(HealthState state, String reason) {
            }
        };
    }
}
