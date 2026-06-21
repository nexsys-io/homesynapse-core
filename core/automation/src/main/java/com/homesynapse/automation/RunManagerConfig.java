/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Duration;
import java.util.Objects;

/**
 * Plain, automation-resident configuration for the {@link RunManager} FSM — cascade
 * governance and auto-disable parameters (Doc 07 §9).
 *
 * <p>This is a value record, <em>not</em> a {@code ConfigurationService}: {@code core ->
 * config} is banned at every scope ({@code assertAllowedModuleDependencies}). The
 * composition root (lifecycle/app — which may depend on both core and config) reads
 * {@code homesynapse.yaml} and constructs this record; the FSM receives plain values.</p>
 *
 * @param maxCascadeDepth     the cascade depth ceiling
 *                            ({@code automation.max_cascade_depth}); range 1&ndash;32
 * @param autoDisableThreshold the failure count within the window that auto-disables an
 *                            automation ({@code automation.auto_disable_failure_count});
 *                            {@code >= 1}
 * @param autoDisableWindow   the failure-counting window
 *                            ({@code automation.auto_disable_window_minutes}); non-null,
 *                            strictly positive
 * @see RunManager
 * @see RunManagerAssembly
 */
public record RunManagerConfig(
        int maxCascadeDepth,
        int autoDisableThreshold,
        Duration autoDisableWindow
) {

    /** Default cascade depth ceiling (Doc 07 §9: {@code automation.max_cascade_depth}). */
    public static final int DEFAULT_MAX_CASCADE_DEPTH = 8;

    /** Inclusive lower bound of the cascade depth ceiling range. */
    public static final int MIN_CASCADE_DEPTH = 1;

    /** Inclusive upper bound of the cascade depth ceiling range. */
    public static final int MAX_CASCADE_DEPTH = 32;

    /** Default auto-disable threshold (Doc 07 §9: {@code automation.auto_disable_failure_count}). */
    public static final int DEFAULT_AUTO_DISABLE_THRESHOLD = 5;

    /** Default auto-disable window (Doc 07 §9: {@code automation.auto_disable_window_minutes} = 10). */
    public static final Duration DEFAULT_AUTO_DISABLE_WINDOW = Duration.ofMinutes(10);

    /**
     * Validates the cascade depth range, the threshold lower bound, and the window.
     *
     * @throws NullPointerException     if {@code autoDisableWindow} is {@code null}
     * @throws IllegalArgumentException if {@code maxCascadeDepth} is outside
     *                                  {@value #MIN_CASCADE_DEPTH}&ndash;{@value #MAX_CASCADE_DEPTH},
     *                                  {@code autoDisableThreshold} is less than 1, or
     *                                  {@code autoDisableWindow} is zero or negative
     */
    public RunManagerConfig {
        Objects.requireNonNull(autoDisableWindow, "autoDisableWindow must not be null");
        if (maxCascadeDepth < MIN_CASCADE_DEPTH || maxCascadeDepth > MAX_CASCADE_DEPTH) {
            throw new IllegalArgumentException(
                    "maxCascadeDepth must be in [" + MIN_CASCADE_DEPTH + ", "
                            + MAX_CASCADE_DEPTH + "]: " + maxCascadeDepth);
        }
        if (autoDisableThreshold < 1) {
            throw new IllegalArgumentException(
                    "autoDisableThreshold must be >= 1: " + autoDisableThreshold);
        }
        if (autoDisableWindow.isZero() || autoDisableWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "autoDisableWindow must be strictly positive: " + autoDisableWindow);
        }
    }

    /**
     * Returns the Doc 07 §9 defaults: cascade depth {@value #DEFAULT_MAX_CASCADE_DEPTH},
     * threshold {@value #DEFAULT_AUTO_DISABLE_THRESHOLD}, window 10 minutes.
     *
     * @return a config carrying the documented defaults, never {@code null}
     */
    public static RunManagerConfig defaults() {
        return new RunManagerConfig(DEFAULT_MAX_CASCADE_DEPTH, DEFAULT_AUTO_DISABLE_THRESHOLD,
                DEFAULT_AUTO_DISABLE_WINDOW);
    }
}
