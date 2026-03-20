/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

/**
 * Governs behavior when a trigger fires while a previous Run of the same automation
 * is still active.
 *
 * <p>Each automation definition specifies a concurrency mode that determines how
 * overlapping Runs are handled. {@link #SINGLE} is the default mode — subsequent
 * triggers are silently dropped while a Run is active. {@link #RESTART} cancels
 * the active Run via {@link Thread#interrupt()} before starting a new one.
 * {@link #QUEUED} and {@link #PARALLEL} allow multiple concurrent Runs, bounded
 * by {@code maxConcurrent} (default 10).</p>
 *
 * <p>Defined in Doc 07 §3.6, §8.2.</p>
 *
 * @see RunManager
 * @see AutomationDefinition
 */
public enum ConcurrencyMode {

    /**
     * Only one Run may be active at a time. Subsequent triggers are dropped.
     *
     * <p>This is the default mode. The dropped trigger produces a log entry at
     * the severity configured by {@link MaxExceededSeverity}.</p>
     */
    SINGLE,

    /**
     * Cancel the active Run and start a new one.
     *
     * <p>Cancellation is performed via {@link Thread#interrupt()} on the Run's
     * virtual thread. The cancelled Run transitions to {@link RunStatus#ABORTED}.</p>
     */
    RESTART,

    /**
     * Queue subsequent triggers for sequential execution after the active Run completes.
     *
     * <p>Bounded by {@code maxConcurrent}. When the queue is full, new triggers are
     * dropped at the configured {@link MaxExceededSeverity}.</p>
     */
    QUEUED,

    /**
     * Allow multiple Runs to execute concurrently on separate virtual threads.
     *
     * <p>Bounded by {@code maxConcurrent}. When the limit is reached, new triggers
     * are dropped at the configured {@link MaxExceededSeverity}.</p>
     */
    PARALLEL
}
