/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Completeness status of a causal chain query result.
 *
 * <p>The trace query service assembles causal chains from the event store by
 * following correlation_id and causation_id references. This sealed interface
 * captures whether a chain is complete, still in-progress, or possibly
 * incomplete due to event retention purge (Doc 11 §3.4, §4.2, decision D-14).</p>
 *
 * <p>Terminal event types ({@code state_confirmed}, {@code automation_completed},
 * {@code command_failed}, {@code command_timed_out}) signal chain completion.
 * Chains without a terminal event are in-progress. Chains with gaps (missing
 * events due to retention purge) are possibly incomplete.</p>
 *
 * @see TraceChain
 * @see TraceQueryService
 */
public sealed interface TraceCompleteness
        permits TraceCompleteness.Complete,
                TraceCompleteness.InProgress,
                TraceCompleteness.PossiblyIncomplete {

    /**
     * A causal chain that terminated in a terminal event.
     *
     * <p>The chain has a definitive end: the triggering event sequence completed
     * (state confirmed, automation finished, command succeeded or failed). No
     * further events are expected.</p>
     */
    record Complete(
        /**
         * The event type of the terminal event.
         *
         * <p>Examples: "state_confirmed", "automation_completed", "command_failed",
         * "command_timed_out". This identifies which condition caused the chain to
         * terminate.</p>
         */
        String terminalEventType
    ) implements TraceCompleteness {
        /**
         * Compact constructor validating non-null fields.
         *
         * @throws NullPointerException if terminalEventType is null
         */
        public Complete {
            Objects.requireNonNull(terminalEventType, "terminalEventType cannot be null");
        }
    }

    /**
     * A causal chain that has not yet terminated.
     *
     * <p>The most recent event in the chain is not a terminal event. The chain
     * is waiting for an expected completion event (e.g., awaiting state confirmation,
     * automation completion).</p>
     *
     * <p>Chains in-progress for over 30 seconds show a warning indicator; over
     * 5 minutes, a potential failure indicator (Doc 11 §3.4, configurable via
     * {@code observability.trace.incomplete_warning_seconds} and
     * {@code incomplete_failure_seconds}).</p>
     */
    record InProgress(
        /**
         * Timestamp of the most recent event in the chain.
         *
         * <p>Non-null. Used to calculate elapsed time since the last event,
         * which drives warning/failure indicators.</p>
         */
        Instant lastEventTime,

        /**
         * Duration since the most recent event was appended.
         *
         * <p>Non-null. When this exceeds configured thresholds, the UI displays
         * warning or failure indicators (e.g., yellow after 30 seconds, red after
         * 5 minutes) to prompt investigation.</p>
         */
        Duration elapsed
    ) implements TraceCompleteness {
        /**
         * Compact constructor validating non-null fields.
         *
         * @throws NullPointerException if lastEventTime or elapsed is null
         */
        public InProgress {
            Objects.requireNonNull(lastEventTime, "lastEventTime cannot be null");
            Objects.requireNonNull(elapsed, "elapsed cannot be null");
        }
    }

    /**
     * A causal chain with one or more missing events.
     *
     * <p>The event store's retention policy or an external purge operation
     * removed events from the middle or end of the chain. The chain is
     * incomplete and may not accurately represent the true causal sequence.
     *
     * <p>Chains with missing events are still navigable, but the
     * {@link TraceNode} tree may have gaps (parent-child links broken by
     * missing intermediate events).</p>
     */
    record PossiblyIncomplete(
        /**
         * Explanation for incompleteness.
         *
         * <p>Examples: "retention purged: 3 events missing", "causation_id
         * {xxxxxxxxxxxxxxxx} not found (predates retention window)".</p>
         */
        String reason
    ) implements TraceCompleteness {
        /**
         * Compact constructor validating non-null fields.
         *
         * @throws NullPointerException if reason is null
         */
        public PossiblyIncomplete {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }
}
