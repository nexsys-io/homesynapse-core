/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Instant;
import java.util.Objects;

import com.homesynapse.state.StateSnapshot;

/**
 * The run-init resolution context a {@link ComputedValue} resolves against: the single
 * trigger-time {@link StateSnapshot} captured once by the {@code RunManager} (AMD-03) and the
 * run-init {@code resolutionTime} taken from the injected {@link java.time.Clock} (§4c —
 * never {@code Instant.now()}).
 *
 * <p>Both inputs are immutable data — there is no resolver, query service, or other I/O
 * collaborator on the context, which is what makes resolution a pure function of
 * {@code (snapshot, resolutionTime)} (INV-TO-02) with no I/O capability (C-SA-2).</p>
 *
 * <p><strong>Deferred input.</strong> Component parameters (the component model) are a future
 * resolution input and are deliberately absent in V1 — no parameter map is carried yet
 * (present-not-built-out). The three V1 permits resolve from the snapshot alone;
 * {@code resolutionTime} is carried for forthcoming time-based computed values.</p>
 *
 * @param snapshot       the captured trigger-time state snapshot (the same one the condition
 *                       gate uses, AMD-03), never {@code null}
 * @param resolutionTime the run-init time from the injected clock, never {@code null}
 * @see ComputedValue
 */
record ComputedValueContext(StateSnapshot snapshot, Instant resolutionTime) {

    /**
     * Validates both inputs are present.
     *
     * @throws NullPointerException if {@code snapshot} or {@code resolutionTime} is {@code null}
     */
    ComputedValueContext {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(resolutionTime, "resolutionTime must not be null");
    }
}
