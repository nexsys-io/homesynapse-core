/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static com.homesynapse.lifecycle.BusPositionCensusIT.heroMotionConfigYaml;
import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.EntityState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * IR-61 (T6) — staleness end to end through the REAL core: the real zigbee adapter over the
 * scripted NCP ({@link RealCoreFixture}), the metering plug adopted by configuration, its
 * {@code ActivePower} report landing as a {@code state_reported} carrying {@code power_w},
 * and the state read the way the dashboard's entities list reads it —
 * {@code core.stateQueryService().getSnapshot()} — on the shared {@code TestClock}.
 *
 * <p>The plug's entity carries {@code power_meter} (1200 s) and {@code energy_meter}
 * (7200 s); the smallest declared interval governs, so {@code staleAfter} is the report's
 * event-time + 1200 s. Before IR-61 every metered entity read {@code staleAfter: null},
 * {@code stale: false} forever — G4-2's 12.5-minute silence of 2026-09-26 read fresh
 * throughout.
 *
 * <p>No wall clock is read: the seed freezes the shared clock ({@link Duration#ZERO} per
 * batch) and this test alone advances it. The fixture's stopwatch is a constant — nothing
 * here is timed.
 */
@DisplayName("StalenessIT — IR-61 T6: a metered entity that stops reporting reads "
        + "stale: true past its threshold through the real read path")
final class StalenessIT {

    /** Raw {@code ActivePower}; the rig's divisor 100 renders 80.00 / 80.01 W. */
    private static final int WATTS0 = 8_000;
    /** power_meter's declared interval — the smallest among the plug's capabilities. */
    private static final Duration THRESHOLD = Duration.ofSeconds(1200);
    /** The fixture's stopwatch: a constant, since nothing here is timed. */
    private static final LongSupplier NO_STOPWATCH = () -> 0L;

    private RealCoreFixture fixture;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StalenessIT() {
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @DisplayName("T6: the plug's power_w report → staleAfter = T + 1200 s, stale false; the "
            + "clock +1201 s → stale true, staleAfter unchanged; a second report → stale "
            + "false, staleAfter = T2 + 1200 s")
    void aMeteredEntityThatStopsReportingReadsStale(@TempDir Path root) throws Exception {
        fixture = RealCoreFixture.boot(root,
                RealCoreFixture.withGen4AcceptListed(heroMotionConfigYaml()));
        EntityId plug = fixture.adoptGen4().get(ZigbeeHardwareFreeRig.GEN4_ENDPOINT);
        assertThat(fixture.core().entityRegistry().getEntity(plug).capabilities())
                .extracting(CapabilityInstance::capabilityId)
                .as("the cluster-first meters (0x0B04 ⇒ power_meter, 0x0702 ⇒ energy_meter): "
                        + "the smaller declared interval, 1200 s, governs")
                .contains("power_meter", "energy_meter");

        // ── the report ────────────────────────────────────────────────────────
        Instant t1 = seedOneReport(plug);
        EntityState reported = awaitReported(plug, t1);
        assertThat(reported.staleAfter()).isEqualTo(t1.plus(THRESHOLD));
        assertThat(reported.stale()).isFalse();

        // ── the silence past the threshold ───────────────────────────────────
        fixture.clock().advance(THRESHOLD.plusSeconds(1));
        EntityState silent = snapshotOf(plug);
        assertThat(silent.stale())
                .as("past staleAfter with no report: the wedge surfaces on the wire")
                .isTrue();
        assertThat(silent.staleAfter()).isEqualTo(reported.staleAfter());

        // ── the next report clears it ────────────────────────────────────────
        Instant t2 = seedOneReport(plug);
        EntityState fresh = awaitReported(plug, t2);
        assertThat(fresh.stale()).isFalse();
        assertThat(fresh.staleAfter())
                .isEqualTo(t2.plus(THRESHOLD))
                .isAfter(reported.staleAfter());
    }

    /**
     * One {@code ActivePower} report through the real ingestion path; returns the event-time
     * of the {@code state_reported} it landed as ({@code eventTime ?? ingestTime}, the stamp
     * the projection reads).
     */
    private Instant seedOneReport(EntityId plug) {
        long before = fixture.rows();
        fixture.seedPowerReports(1, WATTS0, Duration.ZERO, NO_STOPWATCH);
        List<EventEnvelope> reports = fixture.core().eventStore()
                .readByType(EventTypes.STATE_REPORTED, before, 10).events().stream()
                .filter(event -> event.subjectRef().id().equals(plug.value()))
                .filter(event -> ((StateReportedEvent) event.payload()).attributeKey()
                        .equals("power_w"))
                .toList();
        assertThat(reports)
                .as("the seeded ActivePower report lands as a state_reported carrying power_w "
                        + "on the plug's entity")
                .isNotEmpty();
        EventEnvelope report = reports.get(0);
        return report.eventTime() != null ? report.eventTime() : report.ingestTime();
    }

    /** The plug's snapshot state once the projection has applied the report stamped {@code at}. */
    private EntityState awaitReported(EntityId plug, Instant at) {
        EntityState state = null;
        for (int poll = 0; poll < 500; poll++) {
            state = fixture.core().stateQueryService().getSnapshot().states().get(plug);
            if (state != null && at.equals(state.lastReported())) {
                return state;
            }
            sleepBriefly();
        }
        throw new AssertionError("the projection did not apply the report stamped " + at
                + " within ~10 s; the last read: " + state);
    }

    private EntityState snapshotOf(EntityId plug) {
        EntityState state = fixture.core().stateQueryService().getSnapshot().states().get(plug);
        assertThat(state).as("the plug's entity in the snapshot").isNotNull();
        return state;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the projection", ex);
        }
    }
}
