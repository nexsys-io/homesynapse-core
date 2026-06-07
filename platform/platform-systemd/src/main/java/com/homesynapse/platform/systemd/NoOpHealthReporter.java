/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.systemd;

import com.homesynapse.platform.HealthReporter;

/**
 * No-op {@link HealthReporter} for non-systemd and development tiers (macOS, containers
 * without {@code sd_notify}, local runs). Every method does nothing — there is no
 * supervisor to report to, so no I/O is performed.
 *
 * <p>Selection of this implementation versus {@link SystemdHealthReporter} is the
 * composition root's responsibility (lifecycle / M13) and is deliberately not performed
 * here.</p>
 *
 * <p>Thread-safe: stateless.</p>
 *
 * @see HealthReporter
 * @see SystemdHealthReporter
 */
public final class NoOpHealthReporter implements HealthReporter {

    /** Creates a no-op health reporter. */
    public NoOpHealthReporter() {
        // Stateless; selected on non-systemd / development tiers.
    }

    @Override
    public void reportReady() {
        // No supervisor to notify.
    }

    @Override
    public void reportWatchdog() {
        // No supervisor to notify.
    }

    @Override
    public void reportStopping() {
        // No supervisor to notify.
    }

    @Override
    public void reportStatus(String message) {
        // No supervisor to notify.
    }
}
