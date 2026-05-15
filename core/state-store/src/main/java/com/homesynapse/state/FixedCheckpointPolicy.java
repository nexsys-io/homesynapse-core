/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.time.Duration;
import java.util.Objects;

/**
 * Static checkpoint policy: flush when either the event count or the time
 * interval threshold is reached, whichever comes first.
 *
 * <p>This is the MVP checkpoint policy. It ignores {@code readerLag} —
 * checkpoint frequency is constant regardless of write pressure. For workloads
 * where write pressure varies significantly (e.g., enterprise deployments at
 * 20+ events/s sustained), consider {@link AdaptiveCheckpointPolicy}.
 *
 * <p>Default values for the HOME deployment profile (AMD-38, provisional
 * pending WAL pathology spike validation on hs-dev-1):
 * <ul>
 *   <li>{@code eventThreshold = 200} — bounds recovery to ~4 ms on Pi 5 NVMe</li>
 *   <li>{@code maxInterval = 2 seconds} — prevents WAL checkpoint starvation</li>
 * </ul>
 *
 * <p>The {@code shouldCheckpoint} implementation in this record is intentionally
 * Phase-2-scoped: the policy decision is the contract, and the OR comparison
 * across event count and elapsed time is inseparable from that contract.
 *
 * @param eventThreshold maximum events between checkpoints (must be &gt; 0)
 * @param maxInterval    maximum wall-clock time between checkpoints (must be
 *                       positive, non-null)
 */
public record FixedCheckpointPolicy(
        int eventThreshold,
        Duration maxInterval
) implements CheckpointPolicy {

    /**
     * Default policy for the HOME deployment profile (AMD-38, provisional).
     *
     * <p>Values: {@code eventThreshold = 200}, {@code maxInterval = 2 seconds}.
     * These values are provisional pending D1 (WAL Pathology Validation Spike
     * on hs-dev-1). If D1 results indicate the pathology does not reproduce at
     * HomeSynapse event rates, these defaults may be relaxed in a follow-up
     * amendment.
     */
    public static final FixedCheckpointPolicy HOME_DEFAULT =
            new FixedCheckpointPolicy(200, Duration.ofSeconds(2));

    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if {@code eventThreshold <= 0} or
     *                                  {@code maxInterval} is zero or negative
     * @throws NullPointerException     if {@code maxInterval} is null
     */
    public FixedCheckpointPolicy {
        Objects.requireNonNull(maxInterval, "maxInterval");
        if (eventThreshold <= 0) {
            throw new IllegalArgumentException(
                    "eventThreshold must be positive: " + eventThreshold);
        }
        if (maxInterval.isNegative() || maxInterval.isZero()) {
            throw new IllegalArgumentException(
                    "maxInterval must be positive: " + maxInterval);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} when either {@code eventsSinceLastCheckpoint}
     * has reached {@link #eventThreshold} or {@code timeSinceLastCheckpoint}
     * has reached {@link #maxInterval}. The {@code readerLag} parameter is
     * intentionally ignored — see class Javadoc for the rationale.
     */
    @Override
    public boolean shouldCheckpoint(long eventsSinceLastCheckpoint,
                                    Duration timeSinceLastCheckpoint,
                                    long readerLag) {
        return eventsSinceLastCheckpoint >= eventThreshold
                || timeSinceLastCheckpoint.compareTo(maxInterval) >= 0;
    }
}
