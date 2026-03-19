/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Result of a retention sweep in the Persistence Layer (Doc 04 §8.6).
 *
 * <p>{@code RetentionResult} documents the outcome of
 * {@link MaintenanceService#runRetention()}, reporting how many events were
 * purged by priority tier. Retention is priority-based: {@code DIAGNOSTIC}
 * events expire first (7-day default), then {@code NORMAL} events (90-day
 * default), and {@code CRITICAL} events last (365-day default). The retention
 * sweep is interruptible, yielding every batch of deletions (Doc 04 §9).</p>
 *
 * @param eventsDeleted           total number of events deleted across all
 *                                priority tiers
 * @param diagnosticEventsDeleted number of {@code DIAGNOSTIC} priority events
 *                                deleted
 * @param normalEventsDeleted     number of {@code NORMAL} priority events
 *                                deleted
 * @param criticalEventsDeleted   number of {@code CRITICAL} priority events
 *                                deleted
 * @param durationMs              wall-clock duration of the retention sweep in
 *                                milliseconds
 *
 * @see MaintenanceService#runRetention()
 * @see com.homesynapse.event.EventPriority
 * @since 1.0
 */
public record RetentionResult(
        long eventsDeleted,
        long diagnosticEventsDeleted,
        long normalEventsDeleted,
        long criticalEventsDeleted,
        long durationMs
) { }
