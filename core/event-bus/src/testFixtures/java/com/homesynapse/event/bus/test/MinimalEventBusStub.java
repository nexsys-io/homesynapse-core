/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus.test;

import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberSnapshot;

import java.util.List;

/**
 * Lightweight {@link EventBus} stub for unit tests where the full
 * {@link InMemoryEventBus} is overkill.
 *
 * <p>All four abstract methods have default implementations: {@link #subscribe}
 * and {@link #unsubscribe} are no-ops, {@link #notifyEvent} is a no-op, and
 * {@link #subscriberPosition} returns 0. The four default methods on
 * {@code EventBus} retain their {@code UnsupportedOperationException} bodies.</p>
 *
 * <p>Tests override only the methods they need. When {@code EventBus} gains
 * new abstract methods (unlikely given the default-method pattern, but
 * possible), only this class needs updating — not every test file.</p>
 *
 * <p>For tests that need to assert on {@link #subscribers()}, construct with
 * a snapshot list via {@link #MinimalEventBusStub(List)}.</p>
 */
public class MinimalEventBusStub implements EventBus {

    private final List<SubscriberSnapshot> snapshots;

    /** Constructs a stub with an empty subscriber list. */
    public MinimalEventBusStub() {
        this(List.of());
    }

    /**
     * Constructs a stub that returns the given snapshots from
     * {@link #subscribers()}.
     *
     * @param snapshots the subscriber snapshots to return; never {@code null}
     */
    public MinimalEventBusStub(List<SubscriberSnapshot> snapshots) {
        this.snapshots = List.copyOf(snapshots);
    }

    @Override
    public void subscribe(SubscriberInfo subscriber) {
        // no-op
    }

    @Override
    public void unsubscribe(String subscriberId) {
        // no-op
    }

    @Override
    public void notifyEvent(long globalPosition) {
        // no-op
    }

    @Override
    public long subscriberPosition(String subscriberId) {
        return 0;
    }

    @Override
    public List<SubscriberSnapshot> subscribers() {
        return snapshots;
    }
}
