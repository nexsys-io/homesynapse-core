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
 * <p><strong>Power posture (M9.4b §6.10 N-5):</strong> classification is
 * fail-conservative — a device gets the battery posture UNLESS its ZCL Basic
 * PowerSource value is a mains class ({@code 0x01} mains single-phase,
 * {@code 0x02} mains 3-phase). UNKNOWN ({@code 0x00}) and every exotic class
 * therefore inherit the 25 h passive window.
 *
 * <p><strong>Restart initialization (M-1, realized by WU-AVAIL-SEED
 * DP-1):</strong> the tracker initializes each known device from its
 * persisted {@link Seed} — last-known availability plus evidence recency —
 * and emits NO transition for it (a planned restart must never produce a
 * false unavailable→available cascade; seeding publishes nothing). The seed
 * ENTERS every persisted device into tracking so {@link #evaluateTimeouts()}
 * iterates it from the first cycle, but a seeded value never counts as fresh
 * evidence: the silence clock rides the persisted instant, and an absent
 * instant (unknown recency) is INFINITELY stale — never-false-ALIVE; the
 * lawful failure direction is a brief false-UNAVAILABLE that self-heals on
 * the next report. Transitions fire the injected listener only on genuine
 * state changes.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 */
final class StandardAvailabilityTracker implements AvailabilityTracker {

    /** Mains-powered silence before an active ping is due (Doc 08 §9). */
    static final Duration MAINS_PING_SILENCE = Duration.ofMinutes(10);
    /** Battery-powered passive offline timeout (Doc 08 §9). */
    static final Duration BATTERY_OFFLINE_SILENCE = Duration.ofHours(25);
    /** ZCL Basic PowerSource table: mains (single phase). */
    private static final int POWER_SOURCE_MAINS_SINGLE_PHASE = 0x01;
    /** ZCL Basic PowerSource table: mains (3 phase). */
    private static final int POWER_SOURCE_MAINS_THREE_PHASE = 0x02;

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

    /**
     * One persisted seed entry (WU-AVAIL-SEED DP-1): the sidecar's last-known
     * availability plus DP-4's persisted evidence recency. Both components are
     * nullable by design — {@code available} null means the device never
     * transitioned (it seeds UNKNOWN and its first evidence edges normally);
     * {@code lastEvidenceAt} null means unknown recency (treated as infinitely
     * stale — an old-format sidecar must never read as fresh).
     *
     * @param available the persisted last-known availability, or {@code null}
     * @param lastEvidenceAt the persisted evidence instant, or {@code null}
     */
    record Seed(Boolean available, Instant lastEvidenceAt) { }

    private static final Logger log =
            LoggerFactory.getLogger(StandardAvailabilityTracker.class);

    private enum State { UNKNOWN, AVAILABLE, UNAVAILABLE }

    private static final class DeviceState {
        State state = State.UNKNOWN;
        AvailabilityReason reason = AvailabilityReason.FIRST_CONTACT;
        Instant lastSeen;
        /**
         * True once THIS process observed device-originated evidence (a frame
         * or a ping reply). A seeded entry starts false — a persisted value is
         * never this-process evidence (DP-1) — so consumers that assert
         * liveness (the adoption-time view seed) cannot ride the seed.
         */
        boolean evidenced;
    }

    private final Clock clock;
    private final ToIntFunction<IEEEAddress> powerSourceLookup;
    private final TransitionListener listener;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, DeviceState> states = new HashMap<>();

    /**
     * Creates the tracker, initializing from the persisted sidecar seed.
     *
     * @param clock the time source, never {@code null}
     * @param powerSourceLookup resolves a device's ZCL PowerSource value
     *        ({@code 0} when unknown), never {@code null}
     * @param persistedSeed the per-device seed by IEEE value (the cache
     *        sidecar — availability + evidence recency), never {@code null};
     *        entry values never {@code null}, their components may be
     * @param listener the transition sink, never {@code null}
     */
    StandardAvailabilityTracker(Clock clock,
            ToIntFunction<IEEEAddress> powerSourceLookup,
            Map<Long, Seed> persistedSeed,
            TransitionListener listener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.powerSourceLookup =
                Objects.requireNonNull(powerSourceLookup, "powerSourceLookup");
        this.listener = Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(persistedSeed, "persistedSeed");
        // M-1: carry the pre-restart state forward SILENTLY — no transitions
        // at initialization, planned restart or not. DP-1: lastSeen is the
        // PERSISTED evidence instant (null = unknown recency), never the boot
        // instant — a boot-time stamp is what made the battery window reset
        // on every restart (the F-14 aggravator).
        for (Map.Entry<Long, Seed> entry : persistedSeed.entrySet()) {
            Seed seed = entry.getValue();
            DeviceState state = new DeviceState();
            if (seed.available() != null) {
                state.state = seed.available()
                        ? State.AVAILABLE : State.UNAVAILABLE;
                state.reason = seed.available()
                        ? AvailabilityReason.FRAME_RECEIVED
                        : AvailabilityReason.SILENCE_TIMEOUT;
            }
            state.lastSeen = seed.lastEvidenceAt();
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
     * True only when the device is AVAILABLE on THIS process's own evidence —
     * a seeded value never qualifies (DP-1: never manufacture an evidence-free
     * "available"). Pre-seed this was equivalent to {@link #isAvailable}; the
     * distinction exists exactly for liveness-asserting consumers such as the
     * adoption-time view seed.
     *
     * @param device the device, never {@code null}
     * @return whether the device is available on in-process evidence
     */
    boolean isEvidencedAvailable(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            DeviceState state = states.get(device.value());
            return state != null && state.state == State.AVAILABLE
                    && state.evidenced;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evaluates silence timeouts (called each ingestion cycle): battery devices
     * past 25 h transition to unavailable; mains devices past 10 min are
     * returned as ping candidates for the M9.4 active-ping path.
     *
     * <p>DP-1: seeded entries are evaluated too — AVAILABLE and seeded-UNKNOWN
     * states both (an UNKNOWN entry can only come from the seed; live
     * transitions never leave one behind). Only UNAVAILABLE is skipped: an
     * offline device is never pinged and never re-verdicted — recovery is
     * evidence-driven. A {@code null} lastSeen (unknown recency) reads as
     * infinite silence: unknown must never pass for fresh (never-false-ALIVE).
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
                if (state.state == State.UNAVAILABLE) {
                    continue;
                }
                boolean unknownRecency = state.lastSeen == null;
                Duration silence = unknownRecency ? null
                        : Duration.between(state.lastSeen, now);
                IEEEAddress device = new IEEEAddress(entry.getKey());
                // N-5: mains-membership test, not battery-equality — every
                // non-mains value (UNKNOWN 0x00 included) takes the 25 h
                // battery-conservative window.
                if (isMainsPowered(powerSourceLookup.applyAsInt(device))) {
                    if (unknownRecency
                            || silence.compareTo(MAINS_PING_SILENCE) > 0) {
                        pingCandidates.add(device);
                    }
                } else if (unknownRecency
                        || silence.compareTo(BATTERY_OFFLINE_SILENCE) > 0) {
                    timedOut.add(device);
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

    /**
     * N-5 fail-conservative power posture: battery UNLESS the value is one of
     * the ZCL Basic PowerSource table's mains classes ({@code 0x01} mains
     * single-phase, {@code 0x02} mains 3-phase). UNKNOWN ({@code 0x00}) and
     * every exotic class (DC source, emergency supplies) fall to the 25 h
     * battery window — a possibly-sleepy device must never be false-offlined
     * by the 10-min active-ping regime.
     *
     * @param powerSource the device's ZCL Basic PowerSource value
     * @return {@code true} only for the mains classes
     */
    private static boolean isMainsPowered(int powerSource) {
        return powerSource == POWER_SOURCE_MAINS_SINGLE_PHASE
                || powerSource == POWER_SOURCE_MAINS_THREE_PHASE;
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
                // DP-1: any positive record is THIS-process device-originated
                // evidence (a frame or a ping reply) — even without an edge,
                // the seeded-stale mark clears here.
                state.evidenced = true;
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
