/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.systemd;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.platform.HealthReporter;
import com.homesynapse.test.NoRealIoExtension;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link NoOpHealthReporter}. {@link NoRealIoExtension} guards against
 * accidental network I/O, confirming the no-op reporter contacts no supervisor.
 */
@DisplayName("NoOpHealthReporter")
@ExtendWith(NoRealIoExtension.class)
class NoOpHealthReporterTest {

    @Test
    @DisplayName("all reporting methods are no-ops and complete without exception")
    void allMethodsAreNoOps() {
        var reporter = new NoOpHealthReporter();

        reporter.reportReady();
        reporter.reportWatchdog();
        reporter.reportStopping();
        reporter.reportStatus("ignored status");

        assertThat(reporter).isInstanceOf(HealthReporter.class);
    }

    @Test
    @DisplayName("tolerates repeated calls without state")
    void toleratesRepeatedCalls() {
        var reporter = new NoOpHealthReporter();

        reporter.reportReady();
        reporter.reportReady();
        reporter.reportStatus("one");
        reporter.reportStatus("two");
        reporter.reportWatchdog();

        assertThat(reporter).isNotNull();
    }
}
