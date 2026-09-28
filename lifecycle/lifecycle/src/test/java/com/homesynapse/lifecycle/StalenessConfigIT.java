/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static com.homesynapse.lifecycle.BusPositionCensusIT.heroMotionConfigYaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.homesynapse.config.ConfigIssue;
import com.homesynapse.config.ReloadResult;
import com.homesynapse.config.Severity;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * IR-61b (T2, T4) — Doc 03 §9's staleness keys through the REAL core ({@link RealCoreFixture}):
 * an override written in {@code homesynapse.yaml} beats the capability default on the wire
 * (T2), and the {@code state_store} section validates against its own fragment instead of
 * drawing the root's unknown-property WARNING (T4).
 *
 * <p>The plug's entity ULID is minted at adoption, after the first boot, so T2 rewrites
 * {@code homesynapse.yaml} and restarts the core on the same config dir — the restart reads
 * the file again. The static helpers are shared with {@link BootOrderingGateIT}.</p>
 *
 * <p>No wall clock is read: the fixture's shared {@code TestClock} stays where the boot left
 * it (the seed passes {@link Duration#ZERO}) and the stopwatch is a constant.</p>
 */
@DisplayName("StalenessConfigIT — IR-61b T2/T4: the §9 staleness keys read from homesynapse.yaml")
final class StalenessConfigIT {

    /** power_meter's declared interval — the smallest among the plug's capabilities. */
    static final Duration POWER_METER_INTERVAL = Duration.ofSeconds(1200);
    /** The fixture's stopwatch: a constant, since nothing here is timed. */
    static final LongSupplier NO_STOPWATCH = () -> 0L;

    /** The override T2 writes for the plug's entity. */
    private static final Duration OVERRIDE = Duration.ofMinutes(5);
    /** Raw {@code ActivePower}; the rig's divisor 100 renders 80.00 / 80.01 W. */
    private static final int WATTS0 = 8_000;

    private RealCoreFixture fixture;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    StalenessConfigIT() {
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @DisplayName("T2: staleness_overrides {<plug>: PT5M} in homesynapse.yaml → after a restart "
            + "the plug's report reads staleAfter = T + 300 s, not power_meter's T + 1200 s")
    void overrideFromConfig_beatsTheCapabilityDefault(@TempDir Path root) throws Exception {
        String configYaml = RealCoreFixture.withGen4AcceptListed(heroMotionConfigYaml());
        fixture = RealCoreFixture.boot(root, configYaml);
        EntityId plug = fixture.adoptGen4().get(ZigbeeHardwareFreeRig.GEN4_ENDPOINT);

        writeConfig(fixture, configYaml
                + "state_store:\n"
                + "  staleness:\n"
                + "    staleness_overrides:\n"
                + "      \"" + plug + "\": PT5M\n");
        fixture.restart(NO_STOPWATCH);
        awaitSessionStarted(fixture);

        Instant reportedAt = seedOneReport(fixture, plug);
        EntityState reported = awaitReported(fixture, plug, reportedAt);
        System.out.println("staleness-config.t2: plug=" + plug + " reportedAt=" + reportedAt
                + " staleAfter=" + reported.staleAfter());
        assertThat(reported.staleAfter())
                .as("the override read from homesynapse.yaml (PT5M) outranks power_meter's "
                        + "1200 s declared interval")
                .isEqualTo(reportedAt.plus(OVERRIDE));
        assertThat(reported.stale()).isFalse();
    }

    @Test
    @DisplayName("T4: a reload with state_store.staleness.bogus → exactly one WARNING naming "
            + "state_store.staleness.bogus; the same file without bogus → zero issues")
    void stateStoreSection_validatesAgainstItsFragment(@TempDir Path root) throws Exception {
        String configYaml = RealCoreFixture.withGen4AcceptListed(heroMotionConfigYaml());
        fixture = RealCoreFixture.boot(root, configYaml);

        writeConfig(fixture, configYaml
                + "state_store:\n"
                + "  staleness:\n"
                + "    default_staleness_threshold: PT2H\n"
                + "    bogus: 1\n");
        ReloadResult withBogus = fixture.core().configurationService().reload();
        System.out.println("staleness-config.t4: with bogus -> " + withBogus.issues());
        assertThat(withBogus.issues())
                .extracting(ConfigIssue::severity, ConfigIssue::path)
                .as("the fragment's additionalProperties: false inside staleness names the key")
                .containsExactly(tuple(Severity.WARNING, "state_store.staleness.bogus"));

        writeConfig(fixture, configYaml
                + "state_store:\n"
                + "  staleness:\n"
                + "    default_staleness_threshold: PT2H\n");
        ReloadResult clean = fixture.core().configurationService().reload();
        System.out.println("staleness-config.t4: without bogus -> " + clean.issues());
        assertThat(clean.issues())
                .as("a registered state_store section validates with no issue")
                .isEmpty();
    }

    // ── shared with BootOrderingGateIT ──────────────────────────────────────

    /** Replaces the fixture's {@code homesynapse.yaml}; the next restart or reload reads it. */
    static void writeConfig(RealCoreFixture fixture, String configYaml) throws IOException {
        Files.writeString(fixture.tempDir().resolve("config").resolve("homesynapse.yaml"),
                configYaml);
    }

    /** Blocks until the restarted adapter's EZSP session has negotiated over the scripted NCP. */
    static void awaitSessionStarted(RealCoreFixture fixture) {
        for (int poll = 0; poll < 500; poll++) {
            if (fixture.rig().sessionStarted()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("the restarted adapter's EZSP session did not negotiate "
                + "within ~10 s");
    }

    /**
     * One {@code ActivePower} report through the real ingestion path; returns the event-time
     * of the {@code state_reported} it landed as ({@code eventTime ?? ingestTime}, the stamp
     * the projection reads).
     */
    static Instant seedOneReport(RealCoreFixture fixture, EntityId plug) {
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
    static EntityState awaitReported(RealCoreFixture fixture, EntityId plug, Instant at) {
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

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the real core", ex);
        }
    }
}
