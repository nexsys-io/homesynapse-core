/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static com.homesynapse.lifecycle.BusPositionCensusIT.attachLineCapture;
import static com.homesynapse.lifecycle.BusPositionCensusIT.heroMotionConfigYaml;
import static com.homesynapse.lifecycle.BusPositionCensusIT.homesynapseLogger;
import static com.homesynapse.lifecycle.StalenessConfigIT.NO_STOPWATCH;
import static com.homesynapse.lifecycle.StalenessConfigIT.POWER_METER_INTERVAL;
import static com.homesynapse.lifecycle.StalenessConfigIT.awaitReported;
import static com.homesynapse.lifecycle.StalenessConfigIT.seedOneReport;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.homesynapse.integration.zigbee.ZigbeeHardwareFreeRig;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateProjection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * IR-61b (T3, T3b) — the boot ordering gate (IR-83): the state projection subscribes only
 * after the registry projection is LIVE, so a replayed {@code state_reported} always finds its
 * entity's registration.
 *
 * <p>T3 pins the gate's observable — the ORDER of two lines the boot already writes: the
 * registry's {@code registry.projection_live:} ({@code RegistryProjectionSubscriber}) before the
 * state projection's {@code caught up at position} ({@code StateProjection}). Both are written
 * by {@code onCaughtUp()} on their subscriber's thread after the bus flips the subscriber to
 * LIVE, so the order holds because the state projection's subscribe, replay and catch-up take
 * far longer than the registry thread's few lines between its flip and its log call. T3b reads
 * the plug's state once after a restart (informational: a checkpoint restore can mask the
 * race). No wall clock is read.</p>
 */
@DisplayName("BootOrderingGateIT — IR-61b T3/T3b: the state projection subscribes after the "
        + "registry projection is LIVE (IR-83)")
final class BootOrderingGateIT {

    private static final String REGISTRY_LIVE = "registry.projection_live: ";
    private static final String STATE_CAUGHT_UP = "caught up at position";

    private RealCoreFixture fixture;
    private ListAppender<ILoggingEvent> lines;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    BootOrderingGateIT() {
    }

    @AfterEach
    void tearDown() {
        if (lines != null) {
            homesynapseLogger().detachAppender(lines);
            lines.stop();
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    @DisplayName("T3: the boot writes exactly one registry.projection_live line and one "
            + "StateProjection caught-up line, the registry's first")
    void registryLiveLine_precedesTheStateCaughtUpLine(@TempDir Path root) throws Exception {
        lines = attachLineCapture();
        fixture = RealCoreFixture.boot(root,
                RealCoreFixture.withGen4AcceptListed(heroMotionConfigYaml()));

        List<ILoggingEvent> captured = awaitBothLines();
        List<Integer> registryAt = new ArrayList<>();
        List<Integer> stateAt = new ArrayList<>();
        for (int i = 0; i < captured.size(); i++) {
            if (isRegistryLive(captured.get(i))) {
                registryAt.add(i);
            } else if (isStateCaughtUp(captured.get(i))) {
                stateAt.add(i);
            }
        }
        // The boot's cost, from the stamps logback recorded on the lines (no clock read here).
        long bootStartedAt = captured.get(0).getTimeStamp();
        for (int i : registryAt) {
            System.out.println("boot-order.t3: [" + i + "] +" + (captured.get(i).getTimeStamp()
                    - bootStartedAt) + " ms " + captured.get(i).getFormattedMessage());
        }
        for (int i : stateAt) {
            System.out.println("boot-order.t3: [" + i + "] +" + (captured.get(i).getTimeStamp()
                    - bootStartedAt) + " ms " + captured.get(i).getFormattedMessage());
        }

        assertThat(registryAt).as("registry.projection_live lines in the boot").hasSize(1);
        assertThat(stateAt).as("StateProjection caught-up lines in the boot").hasSize(1);
        assertThat(registryAt.get(0))
                .as("the registry projection is LIVE before the state projection subscribes, "
                        + "so its caught-up line comes first")
                .isLessThan(stateAt.get(0));
    }

    @Test
    @DisplayName("T3b (informational): the first read after a restart, before any live report, "
            + "carries staleAfter = T + 1200 s")
    void firstReadAfterRestart_carriesTheThreshold(@TempDir Path root) throws Exception {
        fixture = RealCoreFixture.boot(root,
                RealCoreFixture.withGen4AcceptListed(heroMotionConfigYaml()));
        EntityId plug = fixture.adoptGen4().get(ZigbeeHardwareFreeRig.GEN4_ENDPOINT);
        Instant reportedAt = seedOneReport(fixture, plug);
        assertThat(awaitReported(fixture, plug, reportedAt).staleAfter())
                .isEqualTo(reportedAt.plus(POWER_METER_INTERVAL));

        fixture.restart(NO_STOPWATCH);
        EntityState first = fixture.core().stateQueryService().getSnapshot().states().get(plug);
        System.out.println("boot-order.t3b: first read after restart: "
                + (first == null ? "absent" : "staleAfter=" + first.staleAfter()
                        + " lastReported=" + first.lastReported()));

        assertThat(first).as("the plug's entity in the first snapshot after the restart")
                .isNotNull();
        assertThat(first.staleAfter())
                .as("the replayed or restored state carries the threshold the chain resolved")
                .isEqualTo(reportedAt.plus(POWER_METER_INTERVAL));
    }

    /** The captured lines once both caught-up lines are present (~10 s bound). */
    private List<ILoggingEvent> awaitBothLines() {
        List<ILoggingEvent> captured = List.of();
        for (int poll = 0; poll < 500; poll++) {
            captured = List.copyOf(lines.list);
            if (captured.stream().anyMatch(BootOrderingGateIT::isRegistryLive)
                    && captured.stream().anyMatch(BootOrderingGateIT::isStateCaughtUp)) {
                return captured;
            }
            sleepBriefly();
        }
        throw new AssertionError("the boot did not write both caught-up lines within ~10 s; "
                + "captured " + captured.size() + " lines");
    }

    private static boolean isRegistryLive(ILoggingEvent event) {
        return event.getFormattedMessage().startsWith(REGISTRY_LIVE);
    }

    private static boolean isStateCaughtUp(ILoggingEvent event) {
        return StateProjection.class.getName().equals(event.getLoggerName())
                && event.getFormattedMessage().contains(STATE_CAUGHT_UP);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting the boot's lines", ex);
        }
    }
}
