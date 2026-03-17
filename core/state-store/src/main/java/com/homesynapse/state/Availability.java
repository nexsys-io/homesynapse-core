/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

/**
 * Runtime availability status of an entity in the HomeSynapse state model.
 *
 * <p>Availability represents whether an entity's backing device is currently
 * reachable and communicating. This is a runtime state concept owned by the
 * State Store — it is orthogonal to the administrative enabled/disabled flag
 * on the entity itself. An entity can be enabled but unavailable (device
 * powered off), or disabled but available (device reachable but
 * administratively suppressed).</p>
 *
 * <p>Initialized to {@link #UNKNOWN} when an entity is first adopted.
 * Transitions are driven by {@code availability_changed} events published
 * by integration adapters when device reachability changes.</p>
 *
 * <p>Defined in Doc 03 §4.1.</p>
 *
 * @see EntityState
 * @see com.homesynapse.event.EventTypes#AVAILABILITY_CHANGED
 * @since 1.0
 */
public enum Availability {

    /**
     * The entity's backing device is reachable and communicating normally.
     * The integration adapter has confirmed connectivity.
     */
    AVAILABLE,

    /**
     * The entity's backing device is unreachable. The integration adapter
     * has detected a communication failure or the device has not responded
     * within the expected interval.
     */
    UNAVAILABLE,

    /**
     * The entity's availability has not yet been determined. This is the
     * initial state after entity adoption, before the first
     * {@code availability_changed} event is received. Also used when the
     * integration adapter itself is in an indeterminate state.
     */
    UNKNOWN
}
