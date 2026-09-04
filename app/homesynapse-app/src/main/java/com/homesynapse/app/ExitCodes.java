/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.app;

import com.homesynapse.lifecycle.StartupFailureReport;

import java.util.Objects;
import java.util.Optional;

/**
 * FAILCHAN (EXITCODE (a), R-10 sitting 2026-09-03) — the pure mapping behind the
 * process-exit seam: the lifecycle {@link StartupFailureReport} left by a fatal
 * {@code start()} → the {@link ExitCode} the unit reads. {@code Main} calls it exactly
 * once — after {@code start()} threw and the lifecycle ran its own teardown — and
 * exits with the result. The mapping is unit-tested ({@code ExitCodesTest}); the
 * {@code System.exit} call is the other side of the seam and is NOT (the sitting
 * caveat: the exit lives in {@code main}, never in a shutdown hook, and is reviewed,
 * not tested).
 *
 * <p>The mapping keys on {@link StartupFailureReport#subsystem()} — the
 * {@code recordSubsystem} vocabulary the lifecycle sets as its {@code initializing}
 * marker before each fatal-set init: {@code configuration} → 10 (deterministic —
 * {@code RestartPreventExitStatus=10} keeps the unit from looping on it) ·
 * {@code persistence} → 11 · {@code event-bus} → 12 · every other fatal-set subsystem
 * ({@code device-model}, {@code state-store}, {@code automation}, {@code rest-api},
 * {@code unknown}) and an ABSENT report → 99. {@code SUBSYSTEM_INIT_TIMEOUT} (13) has
 * NO producer at ef02d13 — the Phase-6 bounded integration start is non-fatal by design
 * (INV-RF-01) — so it stays in the enum, reserved, and is never emitted here.</p>
 */
final class ExitCodes {

    private ExitCodes() {
        // Static mapping only — not instantiable
    }

    /**
     * Maps the most recent startup-failure report to the process exit code.
     *
     * @param report {@code SystemLifecycleManager.lastStartupFailure()} — empty when the
     *               failure preceded {@code bootstrap()} (or no report was produced)
     * @return the exit code; never {@code null}, never 0
     * @throws NullPointerException if {@code report} itself is {@code null}
     */
    static ExitCode forStartupFailure(Optional<StartupFailureReport> report) {
        Objects.requireNonNull(report, "report");
        if (report.isEmpty()) {
            return ExitCode.UNEXPECTED_ERROR;
        }
        return switch (report.get().subsystem()) {
            case "configuration" -> ExitCode.CONFIGURATION_FAILURE;
            case "persistence" -> ExitCode.PERSISTENCE_FAILURE;
            case "event-bus" -> ExitCode.EVENT_BUS_FAILURE;
            default -> ExitCode.UNEXPECTED_ERROR;
        };
    }
}
