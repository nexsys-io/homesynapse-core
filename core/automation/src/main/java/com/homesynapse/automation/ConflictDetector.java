/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;

import com.homesynapse.event.EventId;

/**
 * Scans Runs triggered by the same event for contradictory commands targeting the
 * same entity.
 *
 * <p>Produces {@code automation_conflict_detected} DIAGNOSTIC events when contradictory
 * commands are detected. In Tier 1, no automatic resolution occurs — both commands
 * execute (D6). Priority-based suppression is deferred to Tier 2.</p>
 *
 * <p>Conflict detection is post-execution, not pre-execution. All commands reach
 * their targets; the detector merely reports conflicts for monitoring and
 * diagnostics.</p>
 *
 * <p>Thread-safe. All methods may be called concurrently from multiple virtual threads.</p>
 *
 * <p>Defined in Doc 07 §3.13, §8.1.</p>
 *
 * @see RunManager
 * @see RunContext
 */
public interface ConflictDetector {

    /**
     * Scans the provided Runs for contradictory commands targeting the same entity.
     *
     * @param triggeringEventId the event that triggered all the Runs,
     *                          never {@code null}
     * @param triggeredRuns     the Run contexts to scan for conflicts,
     *                          never {@code null}
     */
    void scanForConflicts(EventId triggeringEventId, List<RunContext> triggeredRuns);
}
