/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-subscriber supervisor wrapping {@code onEvent} calls (AMD-42 §3.4.5).
 *
 * <p>Implements the exception taxonomy (DP-1), exponential backoff
 * ({@code MIN=3s, MAX=30s, jitter=0.2}), rolling 10-minute crash window (DP-2),
 * and circuit breaker (5 crashes → SUSPENDED).</p>
 *
 * <p>The supervisor classifies exceptions into two categories:</p>
 * <ul>
 *   <li><strong>Infrastructure exceptions</strong> ({@code Error}, {@code IOException},
 *       checked {@code Exception} that is NOT {@code RuntimeException}) → immediate
 *       SUSPENDED, bypass DLQ and backoff.</li>
 *   <li><strong>Callback exceptions</strong> ({@code RuntimeException}) → standard
 *       backoff path: DLQ, crash window increment, retry scheduling.</li>
 * </ul>
 *
 * <p>This class is NOT thread-safe — it is only accessed from the subscriber's
 * dedicated virtual thread.</p>
 */
final class SubscriberSupervisor {

    /** Minimum backoff duration in milliseconds. */
    private static final long MIN_BACKOFF_MS = 3_000L;

    /** Maximum backoff duration in milliseconds. */
    private static final long MAX_BACKOFF_MS = 30_000L;

    /** Jitter factor applied to backoff duration. */
    private static final double JITTER_FACTOR = 0.2;

    /** Maximum retries after initial failure per event (6 total delivery attempts). */
    private static final int MAX_RETRIES = 5;

    /** Circuit breaker threshold: crashes within the rolling window. */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    /** Rolling crash window duration. */
    private static final Duration CRASH_WINDOW = Duration.ofMinutes(10);

    private final String subscriberId;
    private final Clock clock;
    private final SubscriberDlq dlq;
    private final Deque<Instant> crashTimestamps = new ArrayDeque<>();

    /**
     * Creates a new supervisor for the given subscriber.
     *
     * @param subscriberId the subscriber's stable identifier
     * @param clock        the injected clock for timestamps and backoff timing
     * @param dlq          the subscriber's DLQ ring
     */
    SubscriberSupervisor(String subscriberId, Clock clock, SubscriberDlq dlq) {
        this.subscriberId = Objects.requireNonNull(subscriberId, "subscriberId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dlq = Objects.requireNonNull(dlq, "dlq");
    }

    /**
     * Delivers an event to the subscriber with full exception handling.
     *
     * <p>On {@link RuntimeException}, records a DLQ entry and a crash in the
     * rolling window. Does NOT retry within this call — retries are driven by
     * the subscriber's VT loop re-delivering from the pending queue. Each
     * distinct delivery attempt through this method counts as one crash.</p>
     *
     * <p>M3.2 will add scheduled retry with exponential backoff (MIN=3s, MAX=30s,
     * jitter=0.2). For M3.1, each failed delivery immediately records the crash
     * and returns, allowing the circuit breaker to trip after 5 crashes within
     * the rolling 10-minute window.</p>
     *
     * @param subscriber the subscriber callback
     * @param envelope   the event to deliver
     * @param runtime    the subscriber's runtime bundle (for mode transitions)
     * @return the delivery result indicating what happened
     */
    DeliveryResult deliver(Subscriber subscriber, EventEnvelope envelope,
                           SubscriberRuntime runtime) {
        try {
            subscriber.onEvent(envelope);
            return DeliveryResult.SUCCESS;
        } catch (RuntimeException e) {
            Instant now = clock.instant();

            // Record in DLQ
            dlq.park(new SubscriberDlq.DlqEntry(
                    envelope.globalPosition(),
                    e.getClass().getName(),
                    e.getMessage(),
                    1,
                    now,
                    now
            ));

            // Record crash in window
            recordCrash(now);

            // Check circuit breaker
            if (crashCount() >= CIRCUIT_BREAKER_THRESHOLD) {
                runtime.transitionTo(SubscriberMode.SUSPENDED);
                return DeliveryResult.CIRCUIT_BREAKER_TRIPPED;
            }

            return DeliveryResult.PARKED;
        } catch (Error e) {
            // Infrastructure: immediate SUSPENDED, bypass DLQ
            runtime.transitionTo(SubscriberMode.SUSPENDED);
            return DeliveryResult.INFRASTRUCTURE_FAILURE;
        } catch (Exception e) {
            // Checked exception (not RuntimeException) — infrastructure path
            // This catches IOException and implicitly catches SQLException
            // (which cannot be imported due to JPMS) as checked exceptions.
            runtime.transitionTo(SubscriberMode.SUSPENDED);
            return DeliveryResult.INFRASTRUCTURE_FAILURE;
        }
    }

    /**
     * Records a crash timestamp in the rolling window.
     *
     * @param now the current instant
     */
    private void recordCrash(Instant now) {
        // Evict entries older than 10 minutes
        Instant cutoff = now.minus(CRASH_WINDOW);
        while (!crashTimestamps.isEmpty() && crashTimestamps.peekFirst().isBefore(cutoff)) {
            crashTimestamps.pollFirst();
        }
        crashTimestamps.addLast(now);
    }

    /**
     * Returns the number of crashes within the current rolling window.
     *
     * @return the crash count
     */
    int crashCount() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(CRASH_WINDOW);
        while (!crashTimestamps.isEmpty() && crashTimestamps.peekFirst().isBefore(cutoff)) {
            crashTimestamps.pollFirst();
        }
        return crashTimestamps.size();
    }

    /**
     * Clears the crash window. Called on {@code resume()}.
     */
    void clearCrashWindow() {
        crashTimestamps.clear();
    }

    /**
     * Computes exponential backoff with jitter.
     *
     * @param attempt the attempt number (1-based)
     * @return the backoff duration in milliseconds
     */
    private long computeBackoff(int attempt) {
        // Exponential: MIN * 2^(attempt-1), capped at MAX
        long baseMs = Math.min(MIN_BACKOFF_MS * (1L << (attempt - 1)), MAX_BACKOFF_MS);
        // Apply jitter: base * (1 ± jitter)
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * JITTER_FACTOR;
        long result = (long) (baseMs * jitter);
        return Math.max(MIN_BACKOFF_MS, Math.min(result, MAX_BACKOFF_MS));
    }

    /**
     * Sleeps for the given backoff duration. Uses {@code Thread.sleep()} which
     * is safe on virtual threads (unmounts the carrier, no pinning).
     *
     * @param millis the duration to sleep in milliseconds
     */
    private void sleepForBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Classifies whether an exception is an infrastructure failure.
     *
     * <p>Infrastructure exceptions bypass the DLQ and backoff path and go
     * directly to SUSPENDED.</p>
     *
     * @param t the throwable to classify
     * @return {@code true} if this is an infrastructure exception
     */
    static boolean isInfrastructureException(Throwable t) {
        if (t instanceof Error) {
            return true;
        }
        if (t instanceof IOException) {
            return true;
        }
        // Any checked exception that is NOT RuntimeException
        if (t instanceof Exception && !(t instanceof RuntimeException)) {
            return true;
        }
        return false;
    }

    /**
     * Result of a delivery attempt.
     */
    enum DeliveryResult {
        /** Event delivered successfully. */
        SUCCESS,
        /** Event parked in DLQ after exhausting retries. */
        PARKED,
        /** Circuit breaker tripped — subscriber SUSPENDED. */
        CIRCUIT_BREAKER_TRIPPED,
        /** Infrastructure exception — subscriber SUSPENDED immediately. */
        INFRASTRUCTURE_FAILURE
    }
}
