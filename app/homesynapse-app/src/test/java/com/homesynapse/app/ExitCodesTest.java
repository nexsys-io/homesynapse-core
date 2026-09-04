/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.lifecycle.LifecyclePhase;
import com.homesynapse.lifecycle.StartupFailureReport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FAILCHAN Part B (EXITCODE (a)) — the pure mapping behind the exit seam:
 * {@code SystemLifecycleManager.lastStartupFailure()} → {@link ExitCode}. This is
 * the half of C12-04 a unit test can hold; the {@code System.exit} call in
 * {@code Main} is the seam's other side and is deliberately NOT under test (the
 * mapping is unit-tested, the process exit is not).
 *
 * <p>No clock: the mapping reads a report and returns an enum constant.</p>
 */
@DisplayName("ExitCodes -- the startup-failure report → ExitCode mapping (FAILCHAN)")
final class ExitCodesTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ExitCodesTest() {
    }

    private static Optional<StartupFailureReport> report(LifecyclePhase phase,
            String subsystem) {
        return Optional.of(new StartupFailureReport(phase, subsystem, "inspect the log"));
    }

    @Test
    @DisplayName("T1: a configuration failure maps to CONFIGURATION_FAILURE (10) — the "
            + "deterministic class the unit refuses to restart (RestartPreventExitStatus=10)")
    void configurationMapsToTen() {
        ExitCode code = ExitCodes.forStartupFailure(
                report(LifecyclePhase.FOUNDATION, "configuration"));

        assertThat(code).isEqualTo(ExitCode.CONFIGURATION_FAILURE);
        assertThat(code.code()).isEqualTo(10);
    }

    @Test
    @DisplayName("T2: persistence → PERSISTENCE_FAILURE (11) and event-bus → EVENT_BUS_FAILURE "
            + "(12) — the Phase-2 pair only the initializing marker tells apart")
    void persistenceAndBusMapToElevenTwelve() {
        ExitCode persistence = ExitCodes.forStartupFailure(
                report(LifecyclePhase.DATA_INFRASTRUCTURE, "persistence"));
        ExitCode bus = ExitCodes.forStartupFailure(
                report(LifecyclePhase.DATA_INFRASTRUCTURE, "event-bus"));

        assertThat(persistence).isEqualTo(ExitCode.PERSISTENCE_FAILURE);
        assertThat(persistence.code()).isEqualTo(11);
        assertThat(bus).isEqualTo(ExitCode.EVENT_BUS_FAILURE);
        assertThat(bus.code()).isEqualTo(12);
    }

    @Test
    @DisplayName("T3: every other fatal-set subsystem, the pre-Phase-1 'unknown' marker, and "
            + "an ABSENT report map to UNEXPECTED_ERROR (99) — never a code that lies about "
            + "the class")
    void unknownAndEmptyMapToNinetyNine() {
        assertThat(ExitCodes.forStartupFailure(report(LifecyclePhase.CORE_DOMAIN, "automation")))
                .isEqualTo(ExitCode.UNEXPECTED_ERROR);
        assertThat(ExitCodes.forStartupFailure(
                report(LifecyclePhase.EXTERNAL_INTERFACES, "rest-api")))
                .isEqualTo(ExitCode.UNEXPECTED_ERROR);
        assertThat(ExitCodes.forStartupFailure(report(LifecyclePhase.CORE_DOMAIN, "device-model")))
                .isEqualTo(ExitCode.UNEXPECTED_ERROR);
        assertThat(ExitCodes.forStartupFailure(report(LifecyclePhase.CORE_DOMAIN, "state-store")))
                .isEqualTo(ExitCode.UNEXPECTED_ERROR);
        assertThat(ExitCodes.forStartupFailure(report(LifecyclePhase.BOOTSTRAP, "unknown")))
                .isEqualTo(ExitCode.UNEXPECTED_ERROR);
        assertThat(ExitCodes.forStartupFailure(Optional.empty()))
                .isEqualTo(ExitCode.UNEXPECTED_ERROR);
        assertThat(ExitCode.UNEXPECTED_ERROR.code()).isEqualTo(99);
    }
}
