/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.event.AvailabilityContractDeclaredEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

/**
 * The {@link AvailabilityTracker} implementation (Doc 08 §3.11 + the §8.1 M-1
 * restart rule): power-source-aware silence timeouts — a non-mains device goes
 * unavailable passively after its silence limit (IR-121: the smallest expected
 * report interval its entities' capabilities declare, through the injected
 * lookup; 25 h when none declares); mains devices become PING CANDIDATES past
 * their reporting CONTRACT's maximum + {@link #MAINS_PING_SILENCE} = 60 s where
 * the Core configured one (AVAIL-SHAPE, IR-138 — the contract through the
 * injected lookup), past the 60-s floor alone otherwise (the active probe is
 * the M9.4 ZCL read; silence alone never marks a mains device offline, and
 * {@link #PROBE_MISSES_TO_DARK} consecutive unanswered probes do).
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
 * <p><strong>The availability record (AVAIL-API-1, AMD-103):</strong> the
 * tracker produces two more facts through the same listener. (R-B) The
 * {@code probe_answered} record: an answered probe is counted under the lock,
 * and the decision to record is taken there — the earliest answered probe
 * after the tracker starts, then the earliest at or after
 * {@link #RECORD_INTERVAL} on the injected monotonic source since the device's
 * last record; the window's counts ride the record (the recorded answer
 * counted), the counters reset and the window restarts; a miss-then-answer
 * flicker inside the interval records nothing (the counters carry it) and a
 * dark verdict stays the transition's. The window lives in the instance and
 * is never persisted: a window open at a stop is lost. (R-C) The
 * {@code availability_contract_declared} record names the arm the tracker
 * takes for a device — derived from the inputs the sweep already reads —
 * declared for every known device at construction, at every (re-)interview
 * ({@link #declareContract}), at every evaluation whose derived contract
 * differs from the last declared one, and after {@link #CONTRACT_REFRESH} on
 * the monotonic source. Both records are handed to the listener AFTER the
 * unlock with the snapshot taken at the decision; a publish is a store write
 * and is never made under this lock.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 */
final class StandardAvailabilityTracker implements AvailabilityTracker {

    /**
     * The mains silence FLOOR before an active probe is due (Doc 08 §9). IR-121
     * (J1): 60 s, from D-v94-25's acceptance — a mains device named dark in
     * ≤ 90 s. AVAIL-SHAPE (IR-138; the word {@code AVAIL-LIMIT: b-metered},
     * D-v100-9): a mains device's silence-before-probe is the reporting
     * CONTRACT the Core configured on it PLUS this floor; the floor alone where
     * it has no contract. THE DERIVATION (law #34): SOAK-NIGHT-1 named five
     * plugs dark in ten hours that were never gone, each at +65.1 s of silence
     * (this floor + the probe's full 5-s deadline), each undone by the plug's
     * own next report 69–73 s after the previous one — the plug class reports
     * on change under an {@code ActivePower} contract whose maximum the Core
     * wrote as 600 s and the device accepted at readback
     * ({@code ReportingConfigurator}'s metering rows); the night's 621-s
     * silence was that contract kept. The floor is ADDED, never maxed: a
     * report due exactly at the contract's maximum gets a whole floor to
     * arrive before the probe (the 65-vs-69-s race, at the contract's scale).
     * The probe is ONE ZCL Basic read per silent mains device per cycle (≤ 10
     * reads/min on the ten-device run fleet), each bounded by
     * {@code ZigbeeIntegrationAdapter.AVAILABILITY_PING_TIMEOUT_MILLIS} = 5 s,
     * and {@link #PROBE_MISSES_TO_DARK} consecutive misses name dark.
     *
     * <p>THE AMENDED ACCEPTANCE (D-v94-25 → D-v100-9): a mains device with a
     * reporting contract is named dark inside its contract + this floor + K
     * probes (the Shelly Gen4 plugs and the TR3: 660 + 2 × 5 s + one 50-ms
     * cycle = 670.05 s); a mains device with no contract inside 60 s + K probes
     * = 70.05 s, where the ≤ 90-s word survives. The probes are SEQUENTIAL on
     * the run thread, so N simultaneously silent contract-less devices name
     * the last one at 60 + 2·5·N + 0.05 s: the acceptance holds for N ≤ 2 —
     * the run fleet's floor class (the S31 and the Hue). REFUTABLE-BY: when
     * IR-137's INFO reading shows a class ANSWERS the probe, that class returns
     * to the floor regime by one line (the fast naming the probe was designed
     * for); the contract regime is the standing shape for a class that does
     * not answer. The radio cost is measured at R6 and this constant moves only
     * on that measurement (Z2M probes at 10 min; ours is a product number). A
     * constant by design, like {@link #LINK_SUMMARY_PERIOD}: the run's fleet
     * is fixed. Pre-J1 this was 10 min.
     */
    static final Duration MAINS_PING_SILENCE = Duration.ofSeconds(60);
    /**
     * AVAIL-SHAPE (IR-137): consecutive unanswered probes before a mains device
     * is named dark — the backstop for a lost frame plus a lost reply. A miss is
     * counted in {@link #recordCommandResult} under the lock; the count is reset
     * inside {@link #transition} by any positive record (a frame, a reply) and
     * by the dark verdict that spends it; it is per process, never persisted
     * (DP-1: a persisted miss is not this-process evidence). Between the misses
     * the device stays AVAILABLE and past its limit, so the next cycle's sweep
     * snapshots it again and the second probe fires one cycle later. The naming
     * time for a dead device is its limit + K × the 5-s deadline + (K − 1) × the
     * 50-ms cycle. No new log token: two consecutive {@code outcome=timeout}
     * lines followed by {@code zigbee.availability_link … available=false} IS
     * the record.
     */
    static final int PROBE_MISSES_TO_DARK = 2;
    /**
     * The non-mains fallback window (Doc 08 §9): a device whose entities declare
     * no expected report interval is named dark after 25 h of silence. IR-121:
     * a declared interval replaces this per device — see the constructor's
     * {@code expectedSilenceLookup}.
     */
    static final Duration BATTERY_OFFLINE_SILENCE = Duration.ofHours(25);
    /**
     * LINK-READ — the sampling rule for the link reading and the frame count.
     * <strong>Kept on every frame, written on two occasions only:</strong> the
     * reading (two {@code int}s and an {@link Instant} per device) and the
     * per-device frame count live in memory; they reach the journal when a
     * device's availability TRANSITIONS (the adapter's
     * {@code zigbee.availability_link} line) and once per this period, one
     * {@code zigbee.link_summary} line per device. Never a line per frame, and
     * never a store write: no event carries the reading in this unit.
     *
     * <p><strong>The basis.</strong> A per-frame line scales with traffic (a
     * 5 s metering reporter alone is 720 lines an hour); a per-period line
     * scales with the fleet: one line per device per ten minutes is 6 an hour
     * — at most 60 lines per device per ten hours, 600 for the ≤ ten-device
     * run fleet — and still places every silence inside a ten-minute window
     * with the last reading beside it. Before this rule a device's frame count
     * was not observable on a shipped artifact at all (IR-20: the store's rows
     * by subject bound it only from below).
     *
     * <p>A constant by design: the run's fleet is fixed. The gate is a
     * comparison on the injected {@link Clock} inside the adapter's cycle —
     * time, not cycles, and no scheduler.
     */
    static final Duration LINK_SUMMARY_PERIOD = Duration.ofMinutes(10);
    /**
     * AVAIL-API-1 (AMD-103 R-B, R-H): the record interval — at most one
     * {@code probe_answered} per device per interval within one tracker
     * instance, measured on the injected monotonic source; the earliest
     * answered probe after the tracker starts is always recorded. A code
     * constant, never a configuration key (AMD-102: a declared key is fatal
     * when misspelt), recorded in every asked contract as
     * {@code recordIntervalSeconds} so "checked hourly" is read from the files,
     * not the source; revisited from dry-run #1's numbers by the replay budget
     * (R-H).
     */
    static final Duration RECORD_INTERVAL = Duration.ofSeconds(3600);
    /**
     * AVAIL-API-1 (AMD-103 R-C): the contract refresh — a device's contract is
     * re-declared once this long has passed on the monotonic source since its
     * last contract record, so NORMAL's 90-day retention never purges the only
     * contract of a house that has not rebooted, and a projection rebuilt after
     * that purge can still compute R-G's bound.
     */
    static final Duration CONTRACT_REFRESH = Duration.ofDays(30);
    /** ZCL Basic PowerSource table: mains (single phase). */
    private static final int POWER_SOURCE_MAINS_SINGLE_PHASE = 0x01;
    /** ZCL Basic PowerSource table: mains (3 phase). */
    private static final int POWER_SOURCE_MAINS_THREE_PHASE = 0x02;

    /**
     * Availability transition sink (the availability_changed publish + persist).
     *
     * <p>LINK-READ-2 (J1): every call carries the tracker's SNAPSHOT of the
     * device at the moment of the transition — the reason, the last-seen
     * instant and the LAST link reading with the instant of the frame that
     * delivered it — taken INSIDE the lock before the sink fires, so the sink
     * never re-reads the tracker after the unlock. On a timeout verdict the
     * reading and last-seen are those of the device's last frame ({@code null}
     * when no frame reached this process — a seeded device); on an online edge
     * they are the edging frame's own.
     *
     * <p>AVAIL-API-1 (AMD-103): the same sink carries the two availability
     * records — the sampled {@code probe_answered} ({@link #onProbeRecord}) and
     * the {@code availability_contract_declared} declaration
     * ({@link #onContractDeclared}). Both fire AFTER the unlock with the
     * snapshot taken at the decision, beside (never inside) a transition.
     */
    interface TransitionListener {

        /**
         * A device's availability genuinely changed.
         *
         * @param device the device
         * @param instant the transition's instant — the evidence's or the verdict's
         * @param available the new state
         * @param reason the transition reason, never {@code null}
         * @param lastSeen the last device-originated evidence instant known at
         *        the transition, or {@code null} (unknown recency)
         * @param lastLink the last link reading kept for the device, or
         *        {@code null} when no frame delivered one to this process
         * @param lastLinkAt the receipt instant of the frame that delivered
         *        {@code lastLink}; {@code null} exactly when {@code lastLink} is
         */
        void onTransition(IEEEAddress device, Instant instant, boolean available,
                AvailabilityReason reason, Instant lastSeen, LinkReading lastLink,
                Instant lastLinkAt);

        /**
         * An answered probe closed a record window (AMD-103 R-B): the opening
         * record after the tracker's start, then one at or after
         * {@link #RECORD_INTERVAL} since the device's last record.
         *
         * @param device the device
         * @param at the answered probe's reply instant (wall clock)
         * @param windowStart {@code at} minus the window's monotonic length — the
         *        previous record's {@code at}, or the tracker's start for the
         *        opening record
         * @param answered the probes answered in the window, this one included
         * @param missed the probes missed in the window
         * @param lastLink the last link reading kept for the device, or
         *        {@code null} when no frame delivered one to this process
         * @param lastLinkAt the receipt instant of the frame that delivered
         *        {@code lastLink}; {@code null} exactly when {@code lastLink} is
         */
        void onProbeRecord(IEEEAddress device, Instant at, Instant windowStart,
                int answered, int missed, LinkReading lastLink, Instant lastLinkAt);

        /**
         * A device's availability contract was declared (AMD-103 R-C): at the
         * tracker's start, at a (re-)interview, on a change of the derived
         * contract, or at the 30-day refresh.
         *
         * @param device the device
         * @param at the declaration's instant (wall clock)
         * @param contract the contract as derived — the class, the report
         *        interval, the silence limit, the misses to dark, the record
         *        interval; never {@code null}
         */
        void onContractDeclared(IEEEAddress device, Instant at,
                AvailabilityContractDeclaredEvent contract);
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

    /**
     * AVAIL-API-1: a device's open record window as the counters read it —
     * the probes answered and missed since the device's last record (or since
     * the tracker's start). A test accessor's shape.
     *
     * @param answered the answered probes in the open window
     * @param missed the missed probes in the open window
     */
    record ProbeWindow(int answered, int missed) { }

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
        /**
         * LINK-READ: the last-hop reading of the last frame that delivered
         * one to THIS process, and that frame's instant — written together,
         * {@code null} together. A seeded entry starts without them (DP-1: no
         * reading is ever invented), and they outlive every transition: a
         * silence still answers with the reading of its last frame.
         */
        LinkReading lastLink;
        Instant lastLinkAt;
        /**
         * AVAIL-SHAPE: consecutive probe misses since the last positive record
         * — read and written under the lock only; zero after every transition.
         */
        int probeMisses;
        /**
         * AVAIL-API-1 (R-B): the record window on the monotonic source — its
         * start nanos (the tracker's start, then the last record's reading),
         * the probes answered and missed since the last record, the last
         * record's nanos and whether one was recorded this instance. Read and
         * written under the lock only; never persisted.
         */
        long windowStartNanos;
        int answeredSinceRecord;
        int missedSinceRecord;
        long lastRecordNanos;
        boolean recorded;
        /**
         * AVAIL-API-1 (R-C): the last declared contract and the monotonic
         * reading of its declaration — the on-change arm compares against it,
         * the 30-day refresh counts from it. Under the lock only.
         */
        AvailabilityContractDeclaredEvent lastDeclared;
        long lastContractNanos;

        DeviceState(long windowStartNanos) {
            this.windowStartNanos = windowStartNanos;
        }
    }

    /** One lookup as the sweep reads it: the arm, the value, whether the read failed. */
    private record Lookup(boolean mains, Optional<Duration> value, boolean failed) { }

    /** One declaration taken under the lock, handed to the listener after it. */
    private record Declaration(IEEEAddress device, Instant at,
            AvailabilityContractDeclaredEvent contract) { }

    private final Clock clock;
    private final LongSupplier monotonicNanos;
    private final ToIntFunction<IEEEAddress> powerSourceLookup;
    private final Function<IEEEAddress, Optional<Duration>> expectedSilenceLookup;
    private final Function<IEEEAddress, Optional<Duration>> mainsContractLookup;
    private final TransitionListener listener;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, DeviceState> states = new HashMap<>();
    /** The tracker's start on the monotonic source — every opening window begins here (R-B). */
    private final long startNanos;

    /**
     * Creates the tracker, initializing from the persisted sidecar seed, and
     * declares the contract of every seeded device (AMD-103 R-C: at every
     * tracker start, for every device the tracker knows).
     *
     * @param clock the time source, never {@code null} — the source of every
     *        wall instant, {@code at} included
     * @param monotonicNanos AVAIL-API-1 (AMD-103 R-B): the monotonic source the
     *        record interval and the contract refresh are measured on — a
     *        reading in nanoseconds whose origin is arbitrary and never read as
     *        a wall instant (the composition root hands
     *        {@code System::nanoTime}; the arch rule keeps it out of this
     *        module); never {@code null}
     * @param powerSourceLookup resolves a device's ZCL PowerSource value
     *        ({@code 0} when unknown), never {@code null}
     * @param expectedSilenceLookup IR-121: resolves a device's declared silence
     *        limit — the smallest expected report interval its entities'
     *        capabilities declare (source 2 of Doc 03 §3.8's chain ONLY; never
     *        the per-entity override nor the global default); empty →
     *        {@link #BATTERY_OFFLINE_SILENCE}; read at EVERY evaluation, so a
     *        capability declared later takes effect without a restart; a throw
     *        is caught per device per cycle (WARN) and reads as empty; applied
     *        OUTSIDE the lock; never {@code null}
     * @param mainsContractLookup AVAIL-SHAPE (IR-138): resolves the reporting
     *        CONTRACT the Core configured on a mains device — the smallest
     *        effective maximum interval across its configured rows, re-walked
     *        from the cached record (nothing in memory carries the drive's
     *        outcome at boot, IR-139); the device's silence-before-probe is that
     *        maximum PLUS {@link #MAINS_PING_SILENCE}; empty → the floor alone;
     *        read at EVERY evaluation; a throw is caught per device per cycle
     *        (WARN) and reads as empty; applied OUTSIDE the lock; never
     *        {@code null}
     * @param persistedSeed the per-device seed by IEEE value (the cache
     *        sidecar — availability + evidence recency), never {@code null};
     *        entry values never {@code null}, their components may be
     * @param listener the transition sink, never {@code null}
     */
    StandardAvailabilityTracker(Clock clock,
            LongSupplier monotonicNanos,
            ToIntFunction<IEEEAddress> powerSourceLookup,
            Function<IEEEAddress, Optional<Duration>> expectedSilenceLookup,
            Function<IEEEAddress, Optional<Duration>> mainsContractLookup,
            Map<Long, Seed> persistedSeed,
            TransitionListener listener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.powerSourceLookup =
                Objects.requireNonNull(powerSourceLookup, "powerSourceLookup");
        this.expectedSilenceLookup =
                Objects.requireNonNull(expectedSilenceLookup, "expectedSilenceLookup");
        this.mainsContractLookup =
                Objects.requireNonNull(mainsContractLookup, "mainsContractLookup");
        this.listener = Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(persistedSeed, "persistedSeed");
        this.startNanos = monotonicNanos.getAsLong();
        // M-1: carry the pre-restart state forward SILENTLY — no transitions
        // at initialization, planned restart or not. DP-1: lastSeen is the
        // PERSISTED evidence instant (null = unknown recency), never the boot
        // instant — a boot-time stamp is what made the battery window reset
        // on every restart (the F-14 aggravator).
        List<IEEEAddress> seeded = new ArrayList<>(persistedSeed.size());
        for (Map.Entry<Long, Seed> entry : persistedSeed.entrySet()) {
            Seed seed = entry.getValue();
            DeviceState state = new DeviceState(startNanos);
            if (seed.available() != null) {
                state.state = seed.available()
                        ? State.AVAILABLE : State.UNAVAILABLE;
                state.reason = seed.available()
                        ? AvailabilityReason.FRAME_RECEIVED
                        : AvailabilityReason.SILENCE_TIMEOUT;
            }
            state.lastSeen = seed.lastEvidenceAt();
            states.put(entry.getKey(), state);
            seeded.add(new IEEEAddress(entry.getKey()));
        }
        // AVAIL-API-1 (R-C): the boot's declaration — every seeded device, a
        // seeded-dark one included; the lookups outside the lock, the listener
        // after it. A lookup that throws declares nothing for that device now;
        // the first sweep's on-change arm declares it then.
        for (Declaration declaration : declare(seeded)) {
            listener.onContractDeclared(declaration.device(), declaration.at(),
                    declaration.contract());
        }
    }

    @Override
    public void recordFrame(IEEEAddress device, Instant timestamp,
            Optional<LinkReading> link) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(link, "link");
        transition(device, timestamp, true, null, link.orElse(null));
    }

    @Override
    public Optional<LinkReading> lastLink(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            DeviceState state = states.get(device.value());
            return state == null ? Optional.empty()
                    : Optional.ofNullable(state.lastLink);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Instant> lastLinkAt(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            DeviceState state = states.get(device.value());
            return state == null ? Optional.empty()
                    : Optional.ofNullable(state.lastLinkAt);
        } finally {
            lock.unlock();
        }
    }

    /**
     * LINK-READ-2: the last device-originated evidence instant the tracker
     * holds for the device — a frame's or a ping reply's receipt instant, or
     * the persisted seed's (DP-1: the seed sets the silence clock but is never
     * this-process evidence). Empty for an untracked device or unknown recency.
     * The adoption-time view seed reads it where no listener call carries it.
     *
     * @param device the device, never {@code null}
     * @return the last-seen instant, or empty
     */
    Optional<Instant> lastSeen(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            DeviceState state = states.get(device.value());
            return state == null ? Optional.empty()
                    : Optional.ofNullable(state.lastSeen);
        } finally {
            lock.unlock();
        }
    }

    /**
     * AVAIL-API-1: the device's open record window as the counters read it —
     * zeros for an untracked device. A test accessor.
     *
     * @param device the device, never {@code null}
     * @return the answered and missed probes since the device's last record
     */
    ProbeWindow probeWindow(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            DeviceState state = states.get(device.value());
            return state == null ? new ProbeWindow(0, 0)
                    : new ProbeWindow(state.answeredSinceRecord, state.missedSinceRecord);
        } finally {
            lock.unlock();
        }
    }

    /**
     * AVAIL-API-1: the device's last declared contract. Empty for an untracked
     * device or one whose every derivation failed so far. A test accessor.
     *
     * @param device the device, never {@code null}
     * @return the last declared contract, or empty
     */
    Optional<AvailabilityContractDeclaredEvent> declaredContract(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        lock.lock();
        try {
            DeviceState state = states.get(device.value());
            return state == null ? Optional.empty()
                    : Optional.ofNullable(state.lastDeclared);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void recordCommandResult(IEEEAddress device, boolean success,
            Instant timestamp) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(timestamp, "timestamp");
        // A ping reply is consumed by its exchange and never reaches the
        // ingestion seam — it is evidence without a link reading.
        if (success) {
            transition(device, timestamp, true, AvailabilityReason.PING_SUCCESS,
                    null);
            return;
        }
        // AVAIL-SHAPE: a miss is COUNTED, never judged alone — K consecutive
        // misses name dark (the backstop for a lost frame + a lost reply). An
        // untracked device gets its state here, as transition() would give it.
        // Between the misses the device stays AVAILABLE and past its limit, so
        // the next cycle's sweep snapshots it again and the next probe fires.
        // AVAIL-API-1 (R-B): the miss is counted for the record window HERE and
        // nowhere else, so the dark verdict never counts it twice.
        int misses;
        lock.lock();
        try {
            DeviceState state = states.computeIfAbsent(device.value(),
                    key -> new DeviceState(startNanos));
            misses = ++state.probeMisses;
            state.missedSinceRecord++;
        } finally {
            lock.unlock();
        }
        if (misses >= PROBE_MISSES_TO_DARK) {
            transition(device, timestamp, false, AvailabilityReason.PING_TIMEOUT,
                    null);
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
     * AVAIL-API-1 (AMD-103 R-C): declares the device's contract as derived now
     * — the (re-)interview arm, called by the adapter once the cache record
     * carries the PowerSource and the endpoints. Declares even when the tuple
     * is unchanged (a row per interview, each true when written); a lookup
     * that throws declares nothing (the WARN names the fallback) and the next
     * sweep's on-change arm catches up. The lookups run outside the lock; the
     * listener fires after it.
     *
     * @param device the device, never {@code null}
     */
    void declareContract(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        for (Declaration declaration : declare(List.of(device))) {
            listener.onContractDeclared(declaration.device(), declaration.at(),
                    declaration.contract());
        }
    }

    /**
     * Evaluates silence timeouts (called each ingestion cycle): a non-mains
     * device past its silence limit — the declared expected report interval
     * through the injected lookup, else 25 h (IR-121) — transitions to
     * unavailable; mains devices past their silence-before-probe — the
     * reporting contract's maximum + {@link #MAINS_PING_SILENCE} through the
     * injected contract lookup, else the 60-s floor alone (AVAIL-SHAPE,
     * IR-138) — are returned as ping candidates for the M9.4 active-ping path.
     *
     * <p>Two phases (LTD-11: never a registry or cache call under the lock):
     * the per-device snapshot {@code (device, lastSeen)} is taken UNDER the
     * lock and released; ALL lookups (the power source, then the declared
     * interval for non-mains or the contract for mains) run OUTSIDE it, the
     * compare follows, and {@link #transition} fires — itself outside the
     * lock, as before.
     *
     * <p>DP-1: seeded entries are evaluated too — AVAILABLE and seeded-UNKNOWN
     * states both (an UNKNOWN entry can only come from the seed; live
     * transitions never leave one behind). Only UNAVAILABLE is skipped: an
     * offline device is never pinged and never re-verdicted — recovery is
     * evidence-driven. A {@code null} lastSeen (unknown recency) reads as
     * infinite silence: unknown must never pass for fresh (never-false-ALIVE).
     *
     * <p>AVAIL-API-1 (R-C): the same sweep derives every device's contract
     * from the ONE lookup it already makes for the device (an UNAVAILABLE
     * device's lookup runs for the contract alone) and declares, after the
     * unlock, each whose contract differs from its last declared one or whose
     * last declaration is {@link #CONTRACT_REFRESH} old on the monotonic
     * source. A lookup that throws keeps its per-cycle WARN and declares
     * nothing for that device this cycle.
     *
     * @return the mains devices whose silence warrants an active ping
     */
    List<IEEEAddress> evaluateTimeouts() {
        record Snapshot(IEEEAddress device, Instant lastSeen, boolean unavailable) { }
        List<Snapshot> snapshots = new ArrayList<>();
        Instant now;
        lock.lock();
        try {
            now = clock.instant();
            for (Map.Entry<Long, DeviceState> entry : states.entrySet()) {
                DeviceState state = entry.getValue();
                snapshots.add(new Snapshot(new IEEEAddress(entry.getKey()),
                        state.lastSeen, state.state == State.UNAVAILABLE));
            }
        } finally {
            lock.unlock();
        }
        List<IEEEAddress> pingCandidates = new ArrayList<>();
        List<IEEEAddress> timedOut = new ArrayList<>();
        Map<IEEEAddress, AvailabilityContractDeclaredEvent> derived = new LinkedHashMap<>();
        for (Snapshot snapshot : snapshots) {
            Lookup lookup = lookupFor(snapshot.device());
            if (!lookup.failed()) {
                derived.put(snapshot.device(), contractOf(lookup));
            }
            if (snapshot.unavailable()) {
                continue;
            }
            boolean unknownRecency = snapshot.lastSeen() == null;
            Duration silence = unknownRecency ? null
                    : Duration.between(snapshot.lastSeen(), now);
            Duration limit = limitOf(lookup);
            // N-5: mains-membership test, not battery-equality — every
            // non-mains value (UNKNOWN 0x00 included) takes the passive
            // window: the declared limit, else the 25 h battery-conservative one.
            if (lookup.mains()) {
                if (unknownRecency || silence.compareTo(limit) > 0) {
                    pingCandidates.add(snapshot.device());
                }
            } else if (unknownRecency || silence.compareTo(limit) > 0) {
                timedOut.add(snapshot.device());
            }
        }
        for (IEEEAddress device : timedOut) {
            transition(device, clock.instant(), false,
                    AvailabilityReason.SILENCE_TIMEOUT, null);
        }
        for (Declaration declaration : declareIfChanged(derived)) {
            listener.onContractDeclared(declaration.device(), declaration.at(),
                    declaration.contract());
        }
        return pingCandidates;
    }

    /**
     * The one lookup per device per cycle both arms read (IR-121 for non-mains,
     * AVAIL-SHAPE IR-138 for mains) and the contract derives from. A lookup
     * failure is logged once per device per cycle
     * ({@code zigbee.availability_limit_lookup_failed}, naming the fallback
     * that applies) and reads as empty-and-failed: the limit falls to the
     * fallback — never a stuck cycle, never a verdict from a failed read — and
     * no contract is derived. Called outside the lock.
     */
    private Lookup lookupFor(IEEEAddress device) {
        boolean mains = isMainsPowered(powerSourceLookup.applyAsInt(device));
        Function<IEEEAddress, Optional<Duration>> lookup =
                mains ? mainsContractLookup : expectedSilenceLookup;
        try {
            return new Lookup(mains, lookup.apply(device), false);
        } catch (RuntimeException failure) {
            log.warn("zigbee.availability_limit_lookup_failed: device={} — {} applies "
                    + "this cycle: {}", device, mains ? "the 60-s floor" : "the 25 h window",
                    failure.getMessage());
            return new Lookup(mains, Optional.empty(), true);
        }
    }

    /**
     * The silence limit the lookup yields: the mains silence-before-probe —
     * the contract's maximum PLUS {@link #MAINS_PING_SILENCE} (a report due
     * exactly at the maximum gets a whole floor to arrive before the probe),
     * else the floor alone; the non-mains limit — the declared expected report
     * interval, else the 25 h window.
     */
    private static Duration limitOf(Lookup lookup) {
        if (lookup.mains()) {
            return lookup.value().map(limit -> limit.plus(MAINS_PING_SILENCE))
                    .orElse(MAINS_PING_SILENCE);
        }
        return lookup.value().orElse(BATTERY_OFFLINE_SILENCE);
    }

    /**
     * AVAIL-API-1 (R-C): the contract a successful lookup names — the class
     * IS the arm: {@code passive} for a non-mains source (the declared
     * interval, else 25 h; never asked, never recorded), {@code mains-metered}
     * for a mains source with a contract maximum (the maximum + the floor),
     * {@code mains-floor} without one; the asked classes record at
     * {@link #RECORD_INTERVAL}; {@code missesToDark} is
     * {@link #PROBE_MISSES_TO_DARK}.
     */
    private static AvailabilityContractDeclaredEvent contractOf(Lookup lookup) {
        int silenceLimit = (int) limitOf(lookup).toSeconds();
        Integer declared = lookup.value().map(value -> (int) value.toSeconds()).orElse(null);
        if (!lookup.mains()) {
            return new AvailabilityContractDeclaredEvent(
                    AvailabilityContractDeclaredEvent.AvailabilityClass.PASSIVE.token(),
                    declared, silenceLimit, PROBE_MISSES_TO_DARK, null);
        }
        AvailabilityContractDeclaredEvent.AvailabilityClass arm = declared != null
                ? AvailabilityContractDeclaredEvent.AvailabilityClass.MAINS_METERED
                : AvailabilityContractDeclaredEvent.AvailabilityClass.MAINS_FLOOR;
        return new AvailabilityContractDeclaredEvent(arm.token(), declared, silenceLimit,
                PROBE_MISSES_TO_DARK, (int) RECORD_INTERVAL.toSeconds());
    }

    /**
     * The forced declaration (the boot and the (re-)interview arms): every
     * device's contract derived outside the lock, written under it with the
     * monotonic reading taken there, returned for the listener. A device whose
     * lookup failed is skipped.
     */
    private List<Declaration> declare(Collection<IEEEAddress> devices) {
        Map<IEEEAddress, AvailabilityContractDeclaredEvent> derived = new LinkedHashMap<>();
        for (IEEEAddress device : devices) {
            Lookup lookup = lookupFor(device);
            if (!lookup.failed()) {
                derived.put(device, contractOf(lookup));
            }
        }
        return record(derived, true);
    }

    /** The on-change and refresh arms (the sweep): declared only when different or 30 days old. */
    private List<Declaration> declareIfChanged(
            Map<IEEEAddress, AvailabilityContractDeclaredEvent> derived) {
        return record(derived, false);
    }

    /**
     * Writes the declarations under the lock — one monotonic reading and one
     * wall instant per call — and returns them for the listener, which fires
     * after the unlock. {@code force} declares an unchanged tuple too.
     */
    private List<Declaration> record(Map<IEEEAddress, AvailabilityContractDeclaredEvent> derived,
            boolean force) {
        if (derived.isEmpty()) {
            return List.of();
        }
        List<Declaration> declarations = new ArrayList<>();
        lock.lock();
        try {
            long nowNanos = monotonicNanos.getAsLong();
            Instant at = clock.instant();
            for (Map.Entry<IEEEAddress, AvailabilityContractDeclaredEvent> entry
                    : derived.entrySet()) {
                DeviceState state = states.computeIfAbsent(entry.getKey().value(),
                        key -> new DeviceState(startNanos));
                AvailabilityContractDeclaredEvent contract = entry.getValue();
                boolean changed = !contract.equals(state.lastDeclared);
                boolean refreshDue = state.lastDeclared != null
                        && nowNanos - state.lastContractNanos >= CONTRACT_REFRESH.toNanos();
                if (force || changed || refreshDue) {
                    state.lastDeclared = contract;
                    state.lastContractNanos = nowNanos;
                    declarations.add(new Declaration(entry.getKey(), at, contract));
                }
            }
        } finally {
            lock.unlock();
        }
        return declarations;
    }

    /**
     * N-5 fail-conservative power posture: battery UNLESS the value is one of
     * the ZCL Basic PowerSource table's mains classes ({@code 0x01} mains
     * single-phase, {@code 0x02} mains 3-phase). UNKNOWN ({@code 0x00}) and
     * every exotic class (DC source, emergency supplies) fall to the passive
     * window — a possibly-sleepy device must never be false-offlined by the
     * 60-s active-ping regime.
     *
     * @param powerSource the device's ZCL Basic PowerSource value
     * @return {@code true} only for the mains classes
     */
    private static boolean isMainsPowered(int powerSource) {
        return powerSource == POWER_SOURCE_MAINS_SINGLE_PHASE
                || powerSource == POWER_SOURCE_MAINS_THREE_PHASE;
    }

    /**
     * The single state-mutation path. {@code link} is the frame's last-hop
     * reading, or {@code null} when the evidence carries none (a ping result,
     * a timeout verdict, a frame whose pair could not be constructed): it is
     * kept INSIDE the lock, with the frame's instant, BEFORE the listener
     * fires — so a transition sink that reads {@link #lastLink} sees the
     * reading of the very frame that edged the device. A {@code null} never
     * overwrites a kept reading, and no transition ever clears one.
     *
     * <p>AVAIL-API-1 (R-B): an answered probe ({@code PING_SUCCESS} — never a
     * frame's positive transition) is counted here under the lock, and the
     * decision to record is taken here: the opening record (none yet for the
     * device this instance), else one at or after {@link #RECORD_INTERVAL} on
     * the monotonic source since the last record. The snapshot — the reply's
     * instant, the window's start as {@code at} minus the window's monotonic
     * length, the counts (this answer counted), the last reading — is taken
     * inside the lock, the counters reset and the window restarted at the
     * reading taken for this decision; the listener's {@code onProbeRecord}
     * fires after the unlock, beside (never inside) a transition.</p>
     */
    private void transition(IEEEAddress device, Instant timestamp,
            boolean available, AvailabilityReason explicitReason,
            LinkReading link) {
        boolean changed;
        // LINK-READ-2: the listener's snapshot, taken INSIDE the lock after
        // the state moves — never re-read through the accessors after the
        // unlock (another frame could land between).
        AvailabilityReason reason;
        Instant lastSeen;
        LinkReading lastLink;
        Instant lastLinkAt;
        boolean record = false;
        Instant windowStart = null;
        int answered = 0;
        int missed = 0;
        lock.lock();
        try {
            DeviceState state = states.computeIfAbsent(device.value(),
                    key -> new DeviceState(startNanos));
            if (link != null) {
                state.lastLink = link;
                state.lastLinkAt = timestamp;
            }
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
            // AVAIL-SHAPE: every record through here closes the miss run — a
            // positive one by evidence (the device spoke: a frame parked during
            // a probe's own window resets it at the drain), a timeout verdict
            // because the count has been spent. Under the lock, never elsewhere.
            state.probeMisses = 0;
            if (state.state == target) {
                changed = false;
            } else {
                state.state = target;
                state.reason = explicitReason != null ? explicitReason
                        : firstContact ? AvailabilityReason.FIRST_CONTACT
                                : AvailabilityReason.FRAME_RECEIVED;
                changed = true;
            }
            if (available && explicitReason == AvailabilityReason.PING_SUCCESS) {
                long nowNanos = monotonicNanos.getAsLong();
                state.answeredSinceRecord++;
                boolean due = state.recorded
                        && nowNanos - state.lastRecordNanos >= RECORD_INTERVAL.toNanos();
                if (!state.recorded || due) {
                    record = true;
                    windowStart = timestamp.minus(Duration.ofNanos(
                            Math.max(0L, nowNanos - state.windowStartNanos)));
                    answered = state.answeredSinceRecord;
                    missed = state.missedSinceRecord;
                    state.answeredSinceRecord = 0;
                    state.missedSinceRecord = 0;
                    state.windowStartNanos = nowNanos;
                    state.lastRecordNanos = nowNanos;
                    state.recorded = true;
                }
            }
            reason = state.reason;
            lastSeen = state.lastSeen;
            lastLink = state.lastLink;
            lastLinkAt = state.lastLinkAt;
        } finally {
            lock.unlock();
        }
        if (changed) {
            log.info("zigbee.availability_changed: device={} available={}",
                    device, available);
            listener.onTransition(device, timestamp, available, reason, lastSeen,
                    lastLink, lastLinkAt);
        }
        if (record) {
            listener.onProbeRecord(device, timestamp, windowStart, answered, missed,
                    lastLink, lastLinkAt);
        }
    }
}
