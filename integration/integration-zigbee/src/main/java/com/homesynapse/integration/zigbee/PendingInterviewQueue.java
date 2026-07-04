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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Doc 08 §3.4 pending-interview queue: clock-scheduled retry eligibility
 * (3 retries at 5/15/30 s backoff), the sleepy park (retries exhausted →
 * resume-on-ANY-frame only), and the 24 h expiry with a structured log entry.
 *
 * <p>No thread sleeps here: retries are timestamps, and the ingestion cycle
 * drives {@link #due()} each pass. A frame from a pending device — parked or
 * merely backing off — makes it immediately eligible (the §3.4 wake signal:
 * "when a sleepy device sends any frame, the adapter checks the queue and
 * resumes the interview").
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11).
 *
 * @see InterviewStateMachine
 */
final class PendingInterviewQueue {

    /** Retry backoff ladder after failed attempts (Doc 08 §3.4). */
    static final long[] RETRY_BACKOFF_MILLIS = {5_000, 15_000, 30_000};
    /** Retries before the interview parks as sleepy (Doc 08 §3.4). */
    static final int MAX_RETRIES = 3;
    /** Pending interviews expire after this long (Doc 08 §3.4). */
    static final Duration PENDING_EXPIRY = Duration.ofHours(24);

    private static final Logger log =
            LoggerFactory.getLogger(PendingInterviewQueue.class);

    /**
     * One pending interview's schedule state.
     *
     * @param ieeeAddress the device, never {@code null}
     * @param networkAddress the device's current 16-bit network address
     * @param failedAttempts attempts that have failed so far
     * @param nextEligibleAt when the next attempt may run; {@code null} while parked
     * @param enqueuedAt when the interview entered the queue (the expiry basis)
     * @param parked {@code true} once retries are exhausted — resume on frame only
     */
    record Pending(IEEEAddress ieeeAddress, int networkAddress, int failedAttempts,
            Instant nextEligibleAt, Instant enqueuedAt, boolean parked) {
    }

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Pending> pending = new LinkedHashMap<>();

    /**
     * Creates the queue.
     *
     * @param clock the time source, never {@code null}
     */
    PendingInterviewQueue(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Schedules (or re-schedules) an interview, immediately eligible. A
     * re-announce resets the retry ladder — a rejoin is a fresh device contact.
     *
     * @param ieeeAddress the device, never {@code null}
     * @param networkAddress the device's current 16-bit network address
     */
    void schedule(IEEEAddress ieeeAddress, int networkAddress) {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress");
        lock.lock();
        try {
            Instant now = clock.instant();
            pending.put(ieeeAddress.value(), new Pending(ieeeAddress,
                    networkAddress, 0, now, now, false));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the interviews eligible to run now (parked and backing-off entries
     * excluded).
     *
     * @return the due entries in queue order
     */
    List<Pending> due() {
        lock.lock();
        try {
            Instant now = clock.instant();
            List<Pending> result = new ArrayList<>();
            for (Pending entry : pending.values()) {
                if (!entry.parked() && entry.nextEligibleAt() != null
                        && !now.isBefore(entry.nextEligibleAt())) {
                    result.add(entry);
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a failed attempt: schedules the next retry on the backoff ladder,
     * or parks the interview once retries are exhausted.
     *
     * @param ieeeAddress the device, never {@code null}
     */
    void recordFailure(IEEEAddress ieeeAddress) {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress");
        lock.lock();
        try {
            Pending entry = pending.get(ieeeAddress.value());
            if (entry == null) {
                return;
            }
            int failures = entry.failedAttempts() + 1;
            if (failures > MAX_RETRIES) {
                log.debug("Interview device={} retries exhausted; parked for "
                        + "wake-frame resume", ieeeAddress);
                pending.put(ieeeAddress.value(), new Pending(entry.ieeeAddress(),
                        entry.networkAddress(), failures, null,
                        entry.enqueuedAt(), true));
                return;
            }
            Instant next = clock.instant()
                    .plusMillis(RETRY_BACKOFF_MILLIS[failures - 1]);
            pending.put(ieeeAddress.value(), new Pending(entry.ieeeAddress(),
                    entry.networkAddress(), failures, next, entry.enqueuedAt(),
                    false));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wakes a pending interview: ANY frame from the device makes it immediately
     * eligible, whether parked or backing off (Doc 08 §3.4).
     *
     * @param ieeeAddress the device that sent a frame, never {@code null}
     */
    void onFrameReceived(IEEEAddress ieeeAddress) {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress");
        lock.lock();
        try {
            Pending entry = pending.get(ieeeAddress.value());
            if (entry == null) {
                return;
            }
            pending.put(ieeeAddress.value(), new Pending(entry.ieeeAddress(),
                    entry.networkAddress(), entry.failedAttempts(),
                    clock.instant(), entry.enqueuedAt(), false));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes entries older than {@link #PENDING_EXPIRY}, logging each.
     *
     * @return the expired devices
     */
    List<IEEEAddress> expireStale() {
        lock.lock();
        try {
            Instant cutoff = clock.instant().minus(PENDING_EXPIRY);
            List<IEEEAddress> expired = new ArrayList<>();
            pending.values().removeIf(entry -> {
                if (entry.enqueuedAt().isBefore(cutoff)) {
                    expired.add(entry.ieeeAddress());
                    log.warn("zigbee.interview_expired: device={} pending since {} "
                                    + "exceeded the {}h window; removed",
                            entry.ieeeAddress(), entry.enqueuedAt(),
                            PENDING_EXPIRY.toHours());
                    return true;
                }
                return false;
            });
            return expired;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a completed interview.
     *
     * @param ieeeAddress the device, never {@code null}
     */
    void complete(IEEEAddress ieeeAddress) {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress");
        lock.lock();
        try {
            pending.remove(ieeeAddress.value());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns whether the device has a pending interview.
     *
     * @param ieeeAddress the device, never {@code null}
     * @return {@code true} if pending (eligible, backing off, or parked)
     */
    boolean isPending(IEEEAddress ieeeAddress) {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress");
        lock.lock();
        try {
            return pending.containsKey(ieeeAddress.value());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns whether the device's interview is parked (retries exhausted,
     * waiting on a wake frame).
     *
     * @param ieeeAddress the device, never {@code null}
     * @return {@code true} if parked
     */
    boolean isParked(IEEEAddress ieeeAddress) {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress");
        lock.lock();
        try {
            Pending entry = pending.get(ieeeAddress.value());
            return entry != null && entry.parked();
        } finally {
            lock.unlock();
        }
    }
}
