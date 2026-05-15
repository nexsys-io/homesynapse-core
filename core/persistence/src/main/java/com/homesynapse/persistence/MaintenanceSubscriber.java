/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.time.Duration;

/**
 * Performs periodic maintenance operations on the persistence layer: event
 * retention purge, WAL checkpoint management, and storage integrity checks.
 *
 * <p><b>Execution model (AMD-40):</b> All write operations are submitted to
 * the persistence layer's write executor (the {@code WriteCoordinator} owned
 * by {@code DatabaseExecutor}). The maintenance subscriber does NOT open its
 * own database connection — it shares the single-writer executor established
 * by AMD-26/AMD-27. Each purge SQL statement is submitted as a callable at
 * {@code WritePriority.RETENTION}, placing it behind event publishes and
 * checkpoint writes so retention never starves foreground operations.
 *
 * <p><b>Scheduling:</b> Interval-based, default every 6 hours. NOT cron-based
 * (the nightly-purge pattern causes lock starvation under sustained writes —
 * documented in Home Assistant recorder issues #88780, #94134, #115765,
 * #123348). Storage-pressure-triggered purge runs outside the normal interval
 * when free disk drops below 2× database size.
 *
 * <p><b>Bounded-chunk discipline:</b>
 * <ul>
 *   <li>Each purge transaction deletes at most {@link #DEFAULT_PURGE_BATCH_SIZE}
 *       rows.</li>
 *   <li>Each transaction holds the write lock for at most 2 seconds.</li>
 *   <li>Between transactions, the subscriber yields the write executor to
 *       allow event appends to proceed.</li>
 * </ul>
 *
 * <p><b>Subscriber checkpoint safety:</b> Retention must not delete events
 * past the oldest active subscriber's checkpoint. The implementation queries
 * {@code subscriber_checkpoints} to determine the safe retention boundary
 * before issuing DELETE statements. Subscribers in the PAUSED state (Doc 04
 * §3.4) get indefinite checkpoint protection; all others are protected up to
 * the 24-hour grace period (per the {@code subscriber_grace_period_hours}
 * config setting).
 *
 * @see RetentionPolicy
 * @see DeploymentProfile
 * @see MaintenanceResult
 */
public interface MaintenanceSubscriber {

    /**
     * Default maximum rows deleted per purge transaction.
     *
     * <p>Bounds the lock-hold time of any single DELETE statement to roughly
     * 2 seconds on Pi 5 NVMe (~500 deletes/second under contention). Larger
     * batches risk visible write-latency spikes; smaller batches add
     * coordinator-submission overhead without meaningful safety benefit.
     */
    int DEFAULT_PURGE_BATCH_SIZE = 1_000;

    /**
     * Default interval between scheduled maintenance runs.
     *
     * <p>Six hours spreads daily purge work across four windows rather than
     * concentrating it in a single nightly run, distributing the I/O cost
     * across the day. Storage-pressure-triggered purges may run more
     * frequently and are not constrained by this interval.
     */
    Duration DEFAULT_MAINTENANCE_INTERVAL = Duration.ofHours(6);

    /**
     * Executes a single bounded maintenance pass: purges expired events per
     * the active {@link RetentionPolicy}, then triggers a WAL checkpoint if
     * needed.
     *
     * <p>This method may be called on a scheduled interval (every
     * {@link #DEFAULT_MAINTENANCE_INTERVAL} by default) or triggered by storage
     * pressure. Each invocation runs one or more bounded purge transactions
     * (up to {@link #DEFAULT_PURGE_BATCH_SIZE} rows each) until either all
     * expired events are purged or the total maintenance duration exceeds a
     * reasonable budget (implementation-defined, recommended ≤ 60 s).
     *
     * <p>The method blocks until the maintenance pass completes. Callers
     * should invoke it from a virtual thread or a dedicated scheduler thread
     * — not from a foreground request path.
     *
     * @return the result of the maintenance pass (counts and timing) — never
     *         null
     */
    MaintenanceResult runMaintenance();
}
