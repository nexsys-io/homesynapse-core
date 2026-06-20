/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.platform.HealthReporter;
import com.homesynapse.platform.systemd.NoOpHealthReporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the runtime health loop (Doc 12 §3.10) and the platform
 * {@link HealthReporter} selection.
 *
 * <p>{@link HealthLoop#tick()} is driven directly under an injected
 * {@code Clock.fixed} and a recording reporter — no real sleeping and no
 * scheduler (§4c). Reporter selection is verified for the {@code $NOTIFY_SOCKET}
 * unset/blank cases, which deterministically yield {@link NoOpHealthReporter}
 * (the set case depends on the JVM's AF_UNIX support and is intentionally not
 * asserted here).</p>
 */
@DisplayName("Health loop and reporter selection (Doc 12 §3.10)")
final class HealthLoopTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    HealthLoopTest() {
    }

    /** Recording {@link HealthReporter} — counts each channel; no real sd_notify. */
    private static final class RecordingReporter implements HealthReporter {
        private final AtomicInteger ready = new AtomicInteger();
        private final AtomicInteger watchdog = new AtomicInteger();
        private final AtomicInteger stopping = new AtomicInteger();
        private final AtomicInteger status = new AtomicInteger();
        private volatile String lastStatus;

        RecordingReporter() {
        }

        @Override
        public void reportReady() {
            ready.incrementAndGet();
        }

        @Override
        public void reportWatchdog() {
            watchdog.incrementAndGet();
        }

        @Override
        public void reportStopping() {
            stopping.incrementAndGet();
        }

        @Override
        public void reportStatus(String message) {
            status.incrementAndGet();
            lastStatus = message;
        }
    }

    @Test
    @DisplayName("tick pets the watchdog and reports status each iteration (no real sleep)")
    void tickPetsWatchdogEachIteration() {
        RecordingReporter reporter = new RecordingReporter();
        HealthLoop loop = new HealthLoop(
                reporter, FIXED_CLOCK, Duration.ofSeconds(30), () -> "RUNNING: ok");

        loop.tick();
        loop.tick();
        loop.tick();

        assertThat(reporter.watchdog.get()).isEqualTo(3);
        assertThat(reporter.status.get()).isEqualTo(3);
        assertThat(reporter.lastStatus).isEqualTo("RUNNING: ok");
        // The watchdog loop never pets READY (that fires once at end of Phase 5).
        assertThat(reporter.ready.get()).isZero();
    }

    @Test
    @DisplayName("selectHealthReporter returns NoOpHealthReporter when $NOTIFY_SOCKET is unset")
    void selectNoOpWhenNotifySocketUnset() {
        HealthReporter reporter = HomeSynapseCore.selectHealthReporter(key -> null);
        assertThat(reporter).isInstanceOf(NoOpHealthReporter.class);
    }

    @Test
    @DisplayName("selectHealthReporter returns NoOpHealthReporter when $NOTIFY_SOCKET is blank")
    void selectNoOpWhenNotifySocketBlank() {
        HealthReporter reporter = HomeSynapseCore.selectHealthReporter(
                key -> "NOTIFY_SOCKET".equals(key) ? "   " : null);
        assertThat(reporter).isInstanceOf(NoOpHealthReporter.class);
    }

    @Test
    @DisplayName("watchdogPeriod defaults to 30s when $WATCHDOG_USEC is unset")
    void watchdogPeriodDefault() {
        assertThat(HomeSynapseCore.watchdogPeriod(key -> null))
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("watchdogPeriod is WatchdogSec/2 derived from $WATCHDOG_USEC")
    void watchdogPeriodFromEnv() {
        // 10_000_000 usec = 10s watchdog interval → 5s notify period.
        assertThat(HomeSynapseCore.watchdogPeriod(
                key -> "WATCHDOG_USEC".equals(key) ? "10000000" : null))
                .isEqualTo(Duration.ofSeconds(5));
    }
}
