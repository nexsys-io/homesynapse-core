/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

/**
 * Per-priority event retention durations, in days.
 *
 * <p>Default values come from the {@code EventPriority} Javadoc (the
 * authoritative source per the governance authority chain). Operators may
 * override via YAML configuration ({@code event_model.retention.*}). Overrides
 * may extend or shorten retention; the system does not enforce a floor, so
 * operators take responsibility for shortened retention.
 *
 * <p>The maintenance subscriber consumes this record to determine which events
 * are eligible for purge. Events older than their priority's retention duration
 * are candidates; actual deletion occurs in bounded 1,000-row chunks on the
 * persistence write executor (AMD-40).
 *
 * <p>The retention durations align with the operational priorities in
 * {@code EventPriority}: CRITICAL events get the longest retention because
 * their post-incident audit value is high; DIAGNOSTIC events get the shortest
 * because they are high-volume and low per-event value.
 *
 * @param diagnosticDays retention for {@code DIAGNOSTIC} events
 *                       (source default: 7, must be ≥ 1)
 * @param normalDays     retention for {@code NORMAL} events
 *                       (source default: 90, must be ≥ 1)
 * @param criticalDays   retention for {@code CRITICAL} events
 *                       (source default: 365, must be ≥ 1)
 */
public record RetentionPolicy(
        int diagnosticDays,
        int normalDays,
        int criticalDays
) {

    /**
     * Source-specified defaults from {@code EventPriority} Javadoc.
     *
     * <p>{@code DIAGNOSTIC = 7 days}, {@code NORMAL = 90 days},
     * {@code CRITICAL = 365 days} — verified against
     * {@code com.homesynapse.event.EventPriority} source on 2026-05-15.
     */
    public static final RetentionPolicy SOURCE_DEFAULT =
            new RetentionPolicy(7, 90, 365);

    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if any component is less than 1
     */
    public RetentionPolicy {
        if (diagnosticDays < 1) {
            throw new IllegalArgumentException(
                    "diagnosticDays must be at least 1: " + diagnosticDays);
        }
        if (normalDays < 1) {
            throw new IllegalArgumentException(
                    "normalDays must be at least 1: " + normalDays);
        }
        if (criticalDays < 1) {
            throw new IllegalArgumentException(
                    "criticalDays must be at least 1: " + criticalDays);
        }
    }
}
