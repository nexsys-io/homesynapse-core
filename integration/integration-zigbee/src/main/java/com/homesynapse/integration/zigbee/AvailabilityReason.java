/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Reason for device availability state transitions.
 *
 * <p>Each availability transition ({@code availability_changed} event) carries a reason
 * explaining why the device transitioned between online and offline states. The adapter
 * uses power-source-aware timeout logic: mains-powered devices (routers) trigger active
 * pings after 10 minutes of silence; battery-powered devices (end devices) use passive
 * timeout after 25 hours.
 *
 * <p>Doc 08 §4.4 {@code availability_changed} event payload.
 *
 * <p>Thread-safe: enum.
 *
 * @see AvailabilityTracker
 */
public enum AvailabilityReason {

    /** Initial frame received from a newly joined device. */
    FIRST_CONTACT,

    /** Active availability ping succeeded — device responded to a ZCL Read Attributes. */
    PING_SUCCESS,

    /** Any frame received from the device — passive liveness confirmation. */
    FRAME_RECEIVED,

    /** Active availability ping went unanswered within the configured timeout. */
    PING_TIMEOUT,

    /**
     * No frame received within the power-source-aware silence timeout.
     *
     * <p>Mains-powered devices: 10 minutes. Battery-powered devices: 25 hours.
     */
    SILENCE_TIMEOUT,

    /** Device sent a ZDO Leave notification, explicitly departing the network. */
    LEAVE
}
