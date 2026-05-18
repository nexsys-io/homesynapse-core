/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QueueSaturationHealthCheck} (AMD-43 §3.6.3).
 *
 * <p>Each tick represents one second of real wall-clock time. Tests advance a
 * {@code MutableClock} by exactly 1 second per tick so the re-emit cooldown
 * arithmetic is deterministic.</p>
 *
 * <p>No direct time access (NO_DIRECT_TIME_ACCESS arch rule). All instants
 * derive from the injected clock.</p>
 */
class QueueSaturationHealthCheckTest {

    private static final Instant EPOCH = Instant.parse("2026-05-01T00:00:00Z");
    private static final int WARN_DEPTH = 5000;
    private static final int CRITICAL_DEPTH = 10000;
    private static final int SATURATION_TICKS = 5;

    private MutableClock clock;
    private AtomicInteger depth;
    private List<HealthSignal> signals;
    private QueueSaturationHealthCheck check;

    /** Creates a new test instance. */
    QueueSaturationHealthCheckTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(EPOCH);
        depth = new AtomicInteger(0);
        signals = new ArrayList<>();
        check = new QueueSaturationHealthCheck(
                depth::get, clock,
                WARN_DEPTH, CRITICAL_DEPTH, SATURATION_TICKS,
                signals::add);
    }

    @Test
    void noEmissionBelow5000() {
        depth.set(4000);
        for (int i = 0; i < 10; i++) {
            tickOnce();
        }
        assertThat(signals).isEmpty();
    }

    @Test
    void warnAfterFiveConsecutiveTicksAbove5000() {
        depth.set(6000);
        for (int i = 1; i <= 4; i++) {
            tickOnce();
            assertThat(signals)
                    .as("No signal before saturation reached (tick %d)", i)
                    .isEmpty();
        }
        tickOnce(); // 5th tick — WARN fires
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).level()).isEqualTo(HealthLevel.WARN);
        assertThat(signals.get(0).channel())
                .isEqualTo(QueueSaturationHealthCheck.CHANNEL_SATURATING);
        assertThat(signals.get(0).depth()).isEqualTo(6000);
    }

    @Test
    void criticalAfterFiveConsecutiveTicksAbove10000() {
        depth.set(11000);
        for (int i = 0; i < 5; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).level()).isEqualTo(HealthLevel.CRITICAL);
        assertThat(signals.get(0).channel())
                .isEqualTo(QueueSaturationHealthCheck.CHANNEL_SATURATING);
    }

    @Test
    void criticalSuppressesWarn() {
        depth.set(11000);
        for (int i = 0; i < 5; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).level()).isEqualTo(HealthLevel.CRITICAL);
        assertThat(signals).noneMatch(s -> s.level() == HealthLevel.WARN);
    }

    @Test
    void recoveryAfterFiveTicksBelowThreshold() {
        // Trigger WARN with 5 ticks at 6000.
        depth.set(6000);
        for (int i = 0; i < 5; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(1);

        // Drop below threshold for 5 ticks — recovery INFO fires on the 5th.
        depth.set(3000);
        for (int i = 1; i < 5; i++) {
            tickOnce();
            assertThat(signals)
                    .as("No recovery signal before saturationTicks below threshold (tick %d)", i)
                    .hasSize(1);
        }
        tickOnce(); // 5th below-threshold tick — INFO recovered

        assertThat(signals).hasSize(2);
        assertThat(signals.get(1).level()).isEqualTo(HealthLevel.INFO);
        assertThat(signals.get(1).channel())
                .isEqualTo(QueueSaturationHealthCheck.CHANNEL_RECOVERED);
    }

    @Test
    void warnReEmitAt30SecondIntervals() {
        depth.set(6000);

        // First WARN at tick 5.
        for (int i = 0; i < 5; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(1);

        // Tick through 30 more seconds — at tick 35 the re-emit cooldown elapses
        // and the next WARN fires.
        for (int i = 0; i < 30; i++) {
            tickOnce();
        }
        assertThat(signals)
                .as("Second WARN emitted after 30s cooldown")
                .hasSize(2);
        assertThat(signals.get(1).level()).isEqualTo(HealthLevel.WARN);

        // Another 30 seconds — third WARN.
        for (int i = 0; i < 30; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(3);
        assertThat(signals.get(2).level()).isEqualTo(HealthLevel.WARN);
    }

    @Test
    void criticalReEmitAt10SecondIntervals() {
        depth.set(11000);

        for (int i = 0; i < 5; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(1);

        // 10s cooldown → second CRITICAL at tick 15.
        for (int i = 0; i < 10; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(2);
        assertThat(signals.get(1).level()).isEqualTo(HealthLevel.CRITICAL);

        // Third CRITICAL at tick 25.
        for (int i = 0; i < 10; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(3);
        assertThat(signals.get(2).level()).isEqualTo(HealthLevel.CRITICAL);
    }

    @Test
    void transientSpikeDoesNotTrigger() {
        depth.set(6000);
        for (int i = 0; i < 3; i++) {
            tickOnce();
        }
        depth.set(3000);
        for (int i = 0; i < 2; i++) {
            tickOnce();
        }
        assertThat(signals)
                .as("Counter resets on below-threshold tick — no emission")
                .isEmpty();
    }

    @Test
    void clockInjectionDrivesReEmitCadence() {
        depth.set(6000);

        // 5 ticks reach saturation — first WARN at the 5-second mark.
        for (int i = 0; i < 5; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(1);
        Instant firstWarn = signals.get(0).timestamp();
        // tickOnce reads the clock, runs the algorithm, then advances by 1 s.
        // tick 5 reads the clock after four prior 1-second advances → EPOCH + 4 s.
        assertThat(firstWarn).isEqualTo(EPOCH.plusSeconds(4));

        // Advance an additional 30 seconds with continuing breach.
        for (int i = 0; i < 30; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(2);
        Instant secondWarn = signals.get(1).timestamp();
        assertThat(Duration.between(firstWarn, secondWarn).toSeconds())
                .as("Second WARN at exactly 30s past first")
                .isGreaterThanOrEqualTo(30);
    }

    @Test
    void emitterReceivesNonNullDepthAndTimestamp() {
        depth.set(11000);
        for (int i = 0; i < 5; i++) {
            tickOnce();
        }
        assertThat(signals).hasSize(1);
        HealthSignal s = signals.get(0);
        assertThat(s.depth()).isPositive();
        assertThat(s.timestamp()).isNotNull();
        assertThat(s.level()).isNotNull();
        assertThat(s.channel()).isNotNull();
    }

    /**
     * Ticks the health check once and advances the clock by one second
     * (one tick = one second of wall-clock in the production wiring).
     */
    private void tickOnce() {
        check.tick();
        clock.advance(Duration.ofSeconds(1));
    }

    /**
     * Mutable clock for deterministic tick-based testing. Mirrors the
     * MutableClock pattern used in InProcessEventBusTest.
     */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;
        private final ZoneOffset zone = ZoneOffset.UTC;

        MutableClock(Instant start) {
            this.current = new AtomicReference<>(start);
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zoneId) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        void advance(Duration duration) {
            current.updateAndGet(i -> i.plus(duration));
        }
    }
}
