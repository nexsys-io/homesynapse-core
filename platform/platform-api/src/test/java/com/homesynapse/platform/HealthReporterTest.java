/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link HealthReporter} interface.
 *
 * <p>Since {@code HealthReporter} is an interface with no implementations yet,
 * these tests verify the API surface exists and is implementable. Phase 3 will
 * provide {@code SystemdHealthReporter} (Tier 1) and {@code NoOpHealthReporter}
 * (development tier).</p>
 *
 * <h3>Method inventory (4 methods):</h3>
 * <ul>
 *   <li>{@code reportReady()} — signals system is fully started</li>
 *   <li>{@code reportWatchdog()} — periodic heartbeat to supervisor</li>
 *   <li>{@code reportStopping()} — signals shutdown sequence starting</li>
 *   <li>{@code reportStatus(String)} — human-readable status update</li>
 * </ul>
 */
@DisplayName("HealthReporter")
class HealthReporterTest {

    /** No-op test implementation — validates the interface is implementable. */
    private static final HealthReporter NO_OP = new HealthReporter() {
        @Override public void reportReady() { }
        @Override public void reportWatchdog() { }
        @Override public void reportStopping() { }
        @Override public void reportStatus(String message) { }
    };

    @Nested
    @DisplayName("Interface implementability")
    class ImplementabilityTests {

        @Test
        @DisplayName("no-op implementation can be instantiated")
        void noOpInstantiable() {
            assertThat(NO_OP).isNotNull();
        }

        @Test
        @DisplayName("no-op implementation is an instance of HealthReporter")
        void noOpIsCorrectType() {
            assertThat(NO_OP).isInstanceOf(HealthReporter.class);
        }
    }

    @Nested
    @DisplayName("Methods are callable without exception")
    class CallabilityTests {

        @Test
        @DisplayName("reportReady() completes without exception")
        void reportReadyCallable() {
            NO_OP.reportReady();
            // No exception — method is callable
        }

        @Test
        @DisplayName("reportWatchdog() completes without exception")
        void reportWatchdogCallable() {
            NO_OP.reportWatchdog();
        }

        @Test
        @DisplayName("reportStopping() completes without exception")
        void reportStoppingCallable() {
            NO_OP.reportStopping();
        }

        @Test
        @DisplayName("reportStatus(String) completes without exception")
        void reportStatusCallable() {
            NO_OP.reportStatus("Phase 3: starting integration adapters");
        }
    }

    @Nested
    @DisplayName("Tracking implementation")
    class TrackingTests {

        @Test
        @DisplayName("tracking implementation records method calls")
        void trackingRecordsCalls() {
            var tracker = new TrackingHealthReporter();

            tracker.reportReady();
            tracker.reportWatchdog();
            tracker.reportStopping();
            tracker.reportStatus("test status");

            assertThat(tracker.readyCalled).isTrue();
            assertThat(tracker.watchdogCount).isEqualTo(1);
            assertThat(tracker.stoppingCalled).isTrue();
            assertThat(tracker.lastStatus).isEqualTo("test status");
        }

        @Test
        @DisplayName("multiple watchdog calls are counted")
        void multipleWatchdogCalls() {
            var tracker = new TrackingHealthReporter();

            tracker.reportWatchdog();
            tracker.reportWatchdog();
            tracker.reportWatchdog();

            assertThat(tracker.watchdogCount).isEqualTo(3);
        }
    }

    /**
     * Test double that records calls for verification.
     * Useful as a pattern for Phase 3 integration tests.
     */
    private static final class TrackingHealthReporter implements HealthReporter {
        boolean readyCalled;
        int watchdogCount;
        boolean stoppingCalled;
        String lastStatus;

        @Override public void reportReady() { readyCalled = true; }
        @Override public void reportWatchdog() { watchdogCount++; }
        @Override public void reportStopping() { stoppingCalled = true; }
        @Override public void reportStatus(String message) { lastStatus = message; }
    }
}
