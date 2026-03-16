/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

/**
 * Processing mode governing subscriber behavior during event consumption.
 *
 * <p>The processing mode is a property of the subscriber's execution context, not of
 * individual events (Doc 01 §3.7). It determines whether side effects — actuator
 * commands, derived event production, and external notifications — are permitted
 * during event processing.</p>
 *
 * <p>The most important transition is {@link #REPLAY} to {@link #LIVE}: a subscriber
 * transitions when its checkpoint reaches within a configurable threshold of the log
 * head (default: 10 events). During REPLAY, side effects are suppressed because the
 * derived events already exist in the log from the original live processing.</p>
 *
 * @see <a href="Doc 01 §3.7">Processing Modes</a>
 */
public enum ProcessingMode {

    /**
     * Normal operation — subscriber has caught up to the log head.
     *
     * <p>Actuator commands: permitted. Derived events: emitted normally.
     * External notifications: permitted.</p>
     */
    LIVE,

    /**
     * Catch-up from a checkpoint behind the log head (startup recovery).
     *
     * <p>Actuator commands: suppressed. Derived events: not emitted (projections
     * consume existing derived events from the log). External notifications: suppressed.</p>
     *
     * <p>Transitions to {@link #LIVE} when the subscriber's checkpoint reaches within
     * the configured threshold of the log head.</p>
     */
    REPLAY,

    /**
     * Rebuilding a materialized view from scratch.
     *
     * <p>Actuator commands: suppressed. Derived events: not emitted (projection is
     * consuming, not producing). External notifications: suppressed.</p>
     *
     * <p>Used for manual projection rebuilds or snapshot invalidation recovery.</p>
     */
    PROJECTION,

    /**
     * Automation testing or what-if analysis.
     *
     * <p>Actuator commands: logged to dry-run audit, not executed. Derived events:
     * recorded to dry-run audit, not the event store. External notifications: suppressed.</p>
     */
    DRY_RUN
}
