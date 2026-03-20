/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Log severity when a trigger is dropped due to concurrency mode constraints.
 *
 * <p>Configured per-automation via the {@code max_exceeded_severity} field in
 * {@code automations.yaml}. {@link #INFO} is the default. {@link #SILENT}
 * suppresses the log entry entirely. {@link #WARNING} escalates the log level
 * for automations where dropped triggers indicate a configuration problem.</p>
 *
 * <p>Defined in Doc 07 §3.3, §8.2.</p>
 *
 * @see AutomationDefinition
 * @see ConcurrencyMode
 */
public enum MaxExceededSeverity {

    /**
     * Suppress the log entry entirely when a trigger is dropped.
     */
    SILENT,

    /**
     * Log at INFO level when a trigger is dropped. This is the default.
     */
    INFO,

    /**
     * Log at WARNING level when a trigger is dropped.
     */
    WARNING
}
