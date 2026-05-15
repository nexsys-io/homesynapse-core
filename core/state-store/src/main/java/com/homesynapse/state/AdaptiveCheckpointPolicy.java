/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.time.Duration;
import java.util.Objects;

/**
 * Pressure-aware checkpoint policy: shifts between a relaxed normal cadence
 * and an aggressive pressure cadence based on the subscriber's lag behind
 * the writer.
 *
 * <p><b>Reserved for post-MVP.</b> This type exists in the {@link CheckpointPolicy}
 * sealed hierarchy so that the policy interface can accommodate adaptive
 * behavior without future signature changes. M3 ships with
 * {@link FixedCheckpointPolicy} only.
 *
 * <p>When {@code readerLag >= pressureThreshold}, the pressure-mode policy
 * activates, checkpointing more frequently to release the subscriber's read
 * transaction and allow SQLite's WAL to checkpoint past the reader's position.
 * When lag drops below the threshold, normal mode resumes to reduce write
 * amplification.
 *
 * <p>The two delegated policies must themselves be {@link FixedCheckpointPolicy}
 * instances. Nesting adaptive policies inside other adaptive policies is not
 * supported — the policy decision must be deterministic in the size of the
 * configuration tree, and a single-level pressure switch is sufficient for the
 * workloads this type was designed for.
 *
 * @param normalMode        policy used when reader lag is below the pressure
 *                          threshold (non-null)
 * @param pressureMode      policy used when reader lag meets or exceeds the
 *                          threshold (non-null)
 * @param pressureThreshold reader lag (in events) that triggers pressure mode
 *                          (must be &gt; 0)
 */
public record AdaptiveCheckpointPolicy(
        FixedCheckpointPolicy normalMode,
        FixedCheckpointPolicy pressureMode,
        long pressureThreshold
) implements CheckpointPolicy {

    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if {@code pressureThreshold <= 0}
     * @throws NullPointerException     if {@code normalMode} or
     *                                  {@code pressureMode} is null
     */
    public AdaptiveCheckpointPolicy {
        Objects.requireNonNull(normalMode, "normalMode");
        Objects.requireNonNull(pressureMode, "pressureMode");
        if (pressureThreshold <= 0) {
            throw new IllegalArgumentException(
                    "pressureThreshold must be positive: " + pressureThreshold);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Selects between {@link #normalMode} and {@link #pressureMode} based
     * on {@code readerLag} relative to {@link #pressureThreshold}, then
     * delegates the decision to the selected policy.
     */
    @Override
    public boolean shouldCheckpoint(long eventsSinceLastCheckpoint,
                                    Duration timeSinceLastCheckpoint,
                                    long readerLag) {
        var active = readerLag >= pressureThreshold ? pressureMode : normalMode;
        return active.shouldCheckpoint(
                eventsSinceLastCheckpoint, timeSinceLastCheckpoint, readerLag);
    }
}
