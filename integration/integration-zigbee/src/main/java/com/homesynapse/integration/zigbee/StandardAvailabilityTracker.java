/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntFunction;

/**
 * The {@link AvailabilityTracker} implementation (Doc 08 §3.11 + the §8.1 M-1
 * restart rule): power-source-aware silence timeouts — battery end devices go
 * unavailable passively after 25 h; mains devices become PING CANDIDATES after
 * 10 min (the active ping is the M9.4 ZCL read; silence alone never marks a
 * mains device offline).
 *
 * <p><strong>Restart initialization (M-1):</strong> the tracker initializes
 * each known device to its PERSISTED pre-restart availability (the cache's
 * {@code lastKnownAvailability} sidecar) and emits NO transition for it — a
 * planned restart must never produce a false unavailable→available cascade.
 * Transitions fire the injected listener only on genuine state changes.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 */
final class StandardAvailabilityTracker implements AvailabilityTracker {

    /** Mains-powered silence before an active ping is due (Doc 08 §9). */
    static final Duration MAINS_PING_SILENCE = Duration.ofMinutes(10);
    /** Battery-powered passive offline timeout (Doc 08 §9). */
    static final Duration BATTERY_OFFLINE_SILENCE = Duration.ofHours(25);
    /** ZCL PowerSource: battery. */
    private static final int POWER_SOURCE_BATTERY = 3;

    /** Availability transition sink (the availability_changed publish + persist). */
    interface TransitionListener {

        /**
         * A device's availability genuinely changed.
         *
         * @param device the device
         * @param available the new state
         */
        void onTransition(IEEEAddress device, boolean available);
    }

    private static final Logger log =
            LoggerFactory.getLogger(StandardAvailabilityTracker.class);

    private enum State { UNKNOWN, AVAILABLE, UNAVAILABLE }

    private static final class DeviceState {
        State state = State.UNKNOWN;
        AvailabilityReason reason = AvailabilityReason.FIRST_CONTACT;
        Instant lastSeen;
    }

    private final Clock clock;
    private final ToIntFunction<IEEEAddress> powerSourceLookup;
    private final TransitionListener listener;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, DeviceState> states = new HashMap<>();

    /**
     * Creates the tracker, initializing from persisted pre-restart state.
     *
     * @param clock the time source, never {@code null}
     * @param powerSourceLookup resolves a device's ZCL PowerSource value
     *        ({@code 0} when unknown), never {@code null}
     * @param persistedAvailability the pre-restart availability by IEEE value
     *        (the cache sidecar), never {@code null}
     * @param listener the transition sink, never {@code null}
     */
    StandardAvailabilityTracker(Clock clock,
            ToIntFunction<IEEEAddress> powerSourceLookup,
            Map<Long, Boolean> persistedAvailability,
            TransitionListener listener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.powerSourceLookup =
                Objects.requireNonNull(powerSourceLookup, "powerSourceLookup");
        this.listener = Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(persistedAvailability, "persistedAvailability");
        // M-1: carry the pre-restart state forward SILENTLY — no transitions
        // at initialization, planned restart or not.
        Instant now = clock.instant();
        for (Map.Entry<Long, Boolean> entry : persistedAvailability.entrySet()) {
            DeviceState state = new DeviceState();
            state.state = entry.getValue() ? State.AVAILABLE : State.UNAVAILABLE;
            state.reason = entry.getValue()
                    ? AvailabilityReason.FRAME_RECEIVED
                    : AvailabilityReason.SILENCE_TIMEOUT;
            state.lastSeen = now;
            states.put(entry.getKey(), state);
        }
    }

    @Override
    public void recordFrame(IEEEAddress device, Instant timestamp) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(timestamp, "timestamp");
        transition(device, timestamp, true, null);
    }

    @Override
    public void recordCommandResult(IEEEAddress device, boolean success,
            Instant timestamp) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(timestamp, "timestamp");
        if (success) {
            transition(device, timestamp, true, AvailabilityReason.PING_SUCCESS);
        } else {
            transition(device, timestamp, false, AvailabilityReason.PING_TIMEOUT);
        }
    }

    @Override
    public boolean isAvailable(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            DeviceState state = states.get(device.value());
            return state != null && state.state == State.AVAILABLE;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public AvailabilityReason lastReason(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            DeviceState state = states.get(device.value());
            return state != null ? state.reason : AvailabilityReason.FIRST_CONTACT;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evaluates silence timeouts (called each ingestion cycle): battery devices
     * past 25 h transition to unavailable; mains devices past 10 min are
     * returned as ping candidates for the M9.4 active-ping path.
     *
     * @return the mains devices whose silence warrants an active ping
     */
    List<IEEEAddress> evaluateTimeouts() {
        List<IEEEAddress> pingCandidates = new ArrayList<>();
        List<IEEEAddress> timedOut = new ArrayList<>();
        lock.lock();
        try {
            Instant now = clock.instant();
            for (Map.Entry<Long, DeviceState> entry : states.entrySet()) {
                DeviceState state = entry.getValue();
                if (state.state != State.AVAILABLE || state.lastSeen == null) {
                    continue;
                }
                Duration silence = Duration.between(state.lastSeen, now);
                IEEEAddress device = new IEEEAddress(entry.getKey());
                boolean battery = powerSourceLookup.applyAsInt(device)
                        == POWER_SOURCE_BATTERY;
                if (battery) {
                    if (silence.compareTo(BATTERY_OFFLINE_SILENCE) > 0) {
                        timedOut.add(device);
                    }
                } else if (silence.compareTo(MAINS_PING_SILENCE) > 0) {
                    pingCandidates.add(device);
                }
            }
        } finally {
            lock.unlock();
        }
        for (IEEEAddress device : timedOut) {
            transition(device, clock.instant(), false,
                    AvailabilityReason.SILENCE_TIMEOUT);
        }
        return pingCandidates;
    }

    private void transition(IEEEAddress device, Instant timestamp,
            boolean available, AvailabilityReason explicitReason) {
        boolean changed;
        lock.lock();
        try {
            DeviceState state = states.computeIfAbsent(device.value(),
                    key -> new DeviceState());
            State target = available ? State.AVAILABLE : State.UNAVAILABLE;
            changed = state.state != target && state.state != State.UNKNOWN
                    || state.state == State.UNKNOWN;
            boolean firstContact = state.state == State.UNKNOWN;
            if (available) {
                state.lastSeen = timestamp;
            }
            if (state.state == target) {
                changed = false;
            } else {
                state.state = target;
                state.reason = explicitReason != null ? explicitReason
                        : firstContact ? AvailabilityReason.FIRST_CONTACT
                                : AvailabilityReason.FRAME_RECEIVED;
                changed = true;
            }
        } finally {
            lock.unlock();
        }
        if (changed) {
            log.info("zigbee.availability_changed: device={} available={}",
                    device, available);
            listener.onTransition(device, available);
        }
    }
}
