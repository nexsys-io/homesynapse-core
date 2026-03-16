/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Payload for {@code telemetry_summary} events — aggregated summary of raw telemetry
 * samples promoted to the domain event path (Doc 01 §4.3).
 *
 * <p>Produced by the Persistence Layer's Aggregation Engine. Each summary covers a
 * configurable time period for a single attribute on a single entity. The statistical
 * fields ({@code min}, {@code max}, {@code mean}, {@code sum}, {@code count}) describe
 * the distribution of raw sample values within the period.</p>
 *
 * <p>The {@code partial} flag indicates whether the period was fully observed. A
 * partial summary occurs when the system started or stopped mid-period, or when
 * data gaps exist. Consumers should account for partial summaries when computing
 * cross-period aggregations.</p>
 *
 * <p>Default priority: {@link EventPriority#DIAGNOSTIC DIAGNOSTIC}. Under backpressure,
 * telemetry summaries may be coalesced for non-exempt subscribers (Doc 01 §3.6).</p>
 *
 * @param attributeKey        the attribute being summarized; never {@code null} or blank
 * @param min                 the minimum sample value observed in the period
 * @param max                 the maximum sample value observed in the period
 * @param mean                the arithmetic mean of sample values in the period
 * @param sum                 the sum of all sample values in the period
 * @param count               the number of samples in the period; must be {@code >= 0}
 * @param periodStartEpochMs  the period start as milliseconds since epoch (inclusive)
 * @param periodEndEpochMs    the period end as milliseconds since epoch (exclusive);
 *                            must be greater than {@code periodStartEpochMs}
 * @param partial             {@code true} if the period was not fully observed
 * @see DomainEvent
 * @see EventTypes#TELEMETRY_SUMMARY
 */
public record TelemetrySummaryEvent(
        String attributeKey,
        double min,
        double max,
        double mean,
        double sum,
        long count,
        long periodStartEpochMs,
        long periodEndEpochMs,
        boolean partial
) implements DomainEvent {

    /**
     * Constructs a TelemetrySummaryEvent with validation.
     *
     * @param attributeKey        the attribute key, not null or blank
     * @param min                 the minimum value
     * @param max                 the maximum value
     * @param mean                the mean value
     * @param sum                 the sum value
     * @param count               the count of samples, must be non-negative
     * @param periodStartEpochMs  the period start timestamp in milliseconds
     * @param periodEndEpochMs    the period end timestamp in milliseconds, must be greater than start
     * @param partial             whether this is a partial summary
     */
    public TelemetrySummaryEvent {
        Objects.requireNonNull(attributeKey, "attributeKey cannot be null");
        if (attributeKey.isBlank()) {
            throw new IllegalArgumentException("attributeKey cannot be blank");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }
        if (periodStartEpochMs >= periodEndEpochMs) {
            throw new IllegalArgumentException("periodStartEpochMs must be less than periodEndEpochMs");
        }
    }
}
