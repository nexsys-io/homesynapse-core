/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PortWatchdog} tests: every unhealth SIGNAL path (disconnect listener,
 * read-error, ASH-liveness), the W5 "isOpen() lies" scenario, and the capped
 * clock-injected backoff schedule.
 */
class PortWatchdogTest {

    private TestClock clock;
    private int attempts;
    private boolean reopenResult;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        attempts = 0;
        reopenResult = false;
    }

    private PortWatchdog watchdog() {
        return new PortWatchdog(clock, () -> {
            attempts++;
            return reopenResult;
        });
    }

    @Test
    @DisplayName("the disconnect-listener signal triggers recovery")
    void disconnectListenerPath() {
        PortWatchdog watchdog = watchdog();

        watchdog.onDisconnectSignal();
        watchdog.tick();

        assertThat(watchdog.isHealthy()).isFalse();
        assertThat(attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("the read-error signal triggers recovery")
    void readErrorPath() {
        PortWatchdog watchdog = watchdog();

        watchdog.onReadError();
        watchdog.tick();

        assertThat(attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("the ASH-liveness signal triggers recovery")
    void ashLivenessPath() {
        PortWatchdog watchdog = watchdog();

        watchdog.onAshLivenessLost();
        watchdog.tick();

        assertThat(attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("W5: isOpen() lies — a channel still reporting open does not "
            + "gate recovery; the liveness signal alone triggers it")
    void isOpenLies_recoveryStillTriggers() {
        // The channel insists it is open (the documented unplug behavior)…
        FakeSerialByteChannel channel = new FakeSerialByteChannel(clock);
        channel.reportOpen(true);
        channel.markDead();
        assertThat(channel.isOpen()).isTrue(); // the lie

        // …and the watchdog, which consumes SIGNALS only (never isOpen()),
        // still recovers on lost liveness.
        PortWatchdog watchdog = watchdog();
        watchdog.onAshLivenessLost();
        watchdog.tick();

        assertThat(attempts).isEqualTo(1);
    }

    @Test
    @DisplayName("capped backoff: 1s, 2s, 4s, 8s, 16s, then 30s cap — under a "
            + "fixed schedule of clock advances")
    void cappedBackoffSchedule() {
        PortWatchdog watchdog = watchdog();
        watchdog.onReadError();

        watchdog.tick(); // immediate first attempt
        assertThat(attempts).isEqualTo(1);

        long[] expectedDelays = {1000, 2000, 4000, 8000, 16000, 30_000, 30_000};
        int expectedAttempts = 1;
        for (long delay : expectedDelays) {
            // One millisecond before the scheduled attempt: nothing happens.
            clock.advance(Duration.ofMillis(delay - 1));
            watchdog.tick();
            assertThat(attempts).isEqualTo(expectedAttempts);
            // At the scheduled instant: exactly one more attempt.
            clock.advance(Duration.ofMillis(1));
            watchdog.tick();
            expectedAttempts++;
            assertThat(attempts).isEqualTo(expectedAttempts);
        }
    }

    @Test
    @DisplayName("repeat failure signals never reset an in-progress backoff")
    void repeatSignals_dontResetSchedule() {
        PortWatchdog watchdog = watchdog();
        watchdog.onReadError();
        watchdog.tick(); // attempt 1 fails → next at +1000

        watchdog.onAshLivenessLost(); // must NOT reschedule to "now"
        watchdog.tick();
        assertThat(attempts).isEqualTo(1);

        clock.advance(Duration.ofMillis(1000));
        watchdog.tick();
        assertThat(attempts).isEqualTo(2);
    }

    @Test
    @DisplayName("a successful reopen restores health and resets the backoff")
    void successResetsBackoff() {
        PortWatchdog watchdog = watchdog();
        watchdog.onReadError();
        watchdog.tick(); // fail
        clock.advance(Duration.ofMillis(1000));
        reopenResult = true;
        watchdog.tick(); // succeed

        assertThat(watchdog.isHealthy()).isTrue();
        assertThat(watchdog.failedAttempts()).isZero();

        // A fresh failure starts the schedule from the initial delay again.
        reopenResult = false;
        watchdog.onDisconnectSignal();
        watchdog.tick(); // immediate attempt fails → next at +1000
        int afterFirst = attempts;
        clock.advance(Duration.ofMillis(999));
        watchdog.tick();
        assertThat(attempts).isEqualTo(afterFirst);
        clock.advance(Duration.ofMillis(1));
        watchdog.tick();
        assertThat(attempts).isEqualTo(afterFirst + 1);
    }

    @Test
    @DisplayName("healthy watchdog ticks are no-ops")
    void healthyTick_noop() {
        PortWatchdog watchdog = watchdog();

        watchdog.tick();

        assertThat(attempts).isZero();
        assertThat(watchdog.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("the field cadence (M9.6-RO): attempts fail while the target is "
            + "absent, then the FIRST attempt after it re-appears recovers")
    void fieldCadence_recoveryAtLaterAttempt_accounting() {
        // The bench timeline in miniature: the stick re-attached mid-backoff; a
        // truthfully-captured identity makes the next attempt succeed.
        PortWatchdog watchdog = watchdog();
        watchdog.onDisconnectSignal();

        watchdog.tick();   // attempt 1 fails (immediate)
        for (long delay : new long[] {1000, 2000, 4000}) {
            clock.advance(Duration.ofMillis(delay));
            watchdog.tick();   // attempts 2-4 fail on the backoff schedule
        }
        assertThat(attempts).isEqualTo(4);
        assertThat(watchdog.isHealthy()).isFalse();

        reopenResult = true;   // the stick is back and the identity matches
        clock.advance(Duration.ofMillis(8000));
        watchdog.tick();       // attempt 5 recovers

        assertThat(attempts).isEqualTo(5);
        assertThat(watchdog.isHealthy()).isTrue();
        assertThat(watchdog.failedAttempts()).isZero();
    }
}
