/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

import com.homesynapse.event.EventEnvelope;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standalone, thread-safe event accumulator for collecting {@link EventEnvelope}
 * instances received by subscribers, projections, or any event-processing code.
 *
 * <p>Provides {@link #awaitCount(int, Duration)} for deterministic async testing:
 * the calling thread blocks until the expected number of events have been collected
 * (by other threads calling {@link #add(EventEnvelope)}), or the timeout expires.</p>
 *
 * <p><strong>Naming distinction:</strong> This is intentionally a different class from
 * {@link GivenWhenThen.EventCollector}. That inner class is a simple {@code ArrayList}
 * wrapper used during the DSL's "when producing" phase — it is synchronous, not
 * thread-safe, and has no await capability. This standalone {@code EventCollector} is
 * thread-safe (backed by {@link CopyOnWriteArrayList}), supports cross-thread
 * accumulation, and provides {@link #awaitCount(int, Duration)} for testing async
 * subscriber delivery. They serve fundamentally different purposes and coexist.</p>
 *
 * <p><strong>Thread safety:</strong> All mutable state uses lock-free concurrent
 * data structures: {@link CopyOnWriteArrayList} for events and
 * {@link AtomicReference} for the active {@link CountDownLatch}. No
 * {@code synchronized} blocks are used (LTD-11 compliant).</p>
 *
 * @see TestSubscriber
 * @see GivenWhenThen.EventCollector
 */
public final class EventCollector {

    private final CopyOnWriteArrayList<EventEnvelope> events = new CopyOnWriteArrayList<>();
    private final AtomicReference<CountDownLatch> activeLatch = new AtomicReference<>();

    /**
     * Creates a new empty {@code EventCollector}.
     */
    public EventCollector() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    /**
     * Appends an event to this collector. Thread-safe.
     *
     * <p>If an {@link #awaitCount(int, Duration)} call is pending, this
     * method counts down the internal latch.</p>
     *
     * @param event the event envelope to collect; never {@code null}
     */
    public void add(EventEnvelope event) {
        events.add(Objects.requireNonNull(event, "event must not be null"));
        CountDownLatch latch = activeLatch.get();
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Returns an unmodifiable snapshot of all collected events.
     *
     * @return defensive copy of the events list
     */
    public List<EventEnvelope> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the number of events currently collected.
     *
     * @return event count
     */
    public int eventCount() {
        return events.size();
    }

    /**
     * Returns {@code true} if no events have been collected.
     *
     * @return whether this collector is empty
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Returns all collected events matching the given event type.
     *
     * @param eventType the event type to filter by; never {@code null}
     * @return unmodifiable list of matching events
     */
    public List<EventEnvelope> eventsOfType(String eventType) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        return events.stream()
                .filter(e -> eventType.equals(e.eventType()))
                .toList();
    }

    /**
     * Returns the most recently collected event, or empty if none collected.
     *
     * @return the last event, or empty
     */
    public Optional<EventEnvelope> lastEvent() {
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(events.get(events.size() - 1));
    }

    /**
     * Blocks until exactly {@code expectedCount} events have been collected,
     * or the timeout expires.
     *
     * <p>If events are already {@code >= expectedCount} at call time, returns
     * {@code true} immediately without blocking.</p>
     *
     * @param expectedCount the number of events to wait for; must be {@code >= 0}
     * @param timeout       the maximum time to wait; never {@code null}
     * @return {@code true} if the expected count was reached within the timeout;
     *         {@code false} on timeout
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean awaitCount(int expectedCount, Duration timeout) throws InterruptedException {
        if (events.size() >= expectedCount) {
            return true;
        }
        int remaining = expectedCount - events.size();
        CountDownLatch latch = new CountDownLatch(remaining);
        activeLatch.set(latch);
        // Re-check after setting latch — events may have been added between
        // the size check and latch installation
        if (events.size() >= expectedCount) {
            return true;
        }
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Blocks until at least {@code minCount} events have been collected,
     * or the timeout expires.
     *
     * <p>Same as {@link #awaitCount(int, Duration)} but does not require an
     * exact match — just {@code >= minCount}.</p>
     *
     * @param minCount the minimum number of events to wait for; must be {@code >= 0}
     * @param timeout  the maximum time to wait; never {@code null}
     * @return {@code true} if at least {@code minCount} events were collected
     *         within the timeout; {@code false} on timeout
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean awaitAtLeast(int minCount, Duration timeout) throws InterruptedException {
        return awaitCount(minCount, timeout);
    }

    /**
     * Resets all state: clears collected events and removes any active latch.
     *
     * <p>Intended for test isolation between {@code @BeforeEach} calls.</p>
     */
    public void clear() {
        events.clear();
        activeLatch.set(null);
    }

    @Override
    public String toString() {
        return "EventCollector[count=" + events.size() + "]";
    }
}
