/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import java.util.Objects;

/**
 * The C12-04 diagnostic a fatal startup failure leaves behind (FAILCHAN, 2026-09-04;
 * Doc 12 §5 C12-04): the lifecycle phase the failure occurred in, the fatal-set
 * subsystem whose initialization was being attempted, and the human-readable
 * recommendation — the structured half of "non-zero exit + a structured log entry
 * naming subsystem, phase, exception, recommendation". The exception itself rides
 * the {@code lifecycle.startup_failed} log line (stack trace) and the re-thrown
 * {@code start()} failure; this record is what the composition root reads to choose
 * the process exit code.
 *
 * <p>Produced ONLY by the fatal path of {@code HomeSynapseCore.start()} — built
 * BEFORE the teardown, so {@code phase} is the phase the failure occurred in, not
 * {@code SHUTTING_DOWN}/{@code STOPPED} — and read through
 * {@link SystemLifecycleManager#lastStartupFailure()}. Immutable; safe to publish
 * across threads.</p>
 *
 * @param phase          the {@link LifecyclePhase} the failure occurred in
 * @param subsystem      the subsystem being initialized, in the {@code recordSubsystem}
 *                       vocabulary — {@code configuration} · {@code persistence} ·
 *                       {@code event-bus} · {@code device-model} · {@code state-store} ·
 *                       {@code automation} · {@code rest-api} · {@code observability} ·
 *                       {@code integration} — or {@code "unknown"} before Phase 1
 * @param recommendation the C12-04 operator hint (Register C voice), e.g. "check the
 *                       configuration file …"
 */
public record StartupFailureReport(LifecyclePhase phase, String subsystem,
                                   String recommendation) {

    /** Validates the three components; every field is required (C12-04 names all three). */
    public StartupFailureReport {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(subsystem, "subsystem");
        Objects.requireNonNull(recommendation, "recommendation");
    }
}
