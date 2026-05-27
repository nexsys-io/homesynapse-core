/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.bus.EventBus;

import java.util.Objects;

/**
 * Decorator that persists an event via the delegate {@link EventPublisher}
 * and then notifies the {@link EventBus} of the new global position.
 *
 * <p>This bridges the publish/notify gap (M3.7 Finding 2): the raw
 * persistence-layer publisher writes to SQLite but has no dependency on the
 * bus module. The composition root constructs this decorator to close the
 * gap, ensuring every successful persist is immediately visible to
 * bus subscribers.</p>
 *
 * <p>Notification happens AFTER the delegate returns successfully.
 * If the delegate throws {@link SequenceConflictException}, no notification
 * is sent — the event was never persisted, so there is nothing to notify
 * (preserves INV-ES-04).</p>
 *
 * <p>Package-private — constructed only inside
 * {@link HomeSynapseCore#start()}.</p>
 */
final class NotifyingEventPublisher implements EventPublisher {

    private final EventPublisher delegate;
    private final EventBus bus;

    NotifyingEventPublisher(EventPublisher delegate, EventBus bus) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bus = Objects.requireNonNull(bus, "bus");
    }

    @Override
    public EventEnvelope publish(EventDraft draft, CausalContext cause)
            throws SequenceConflictException {
        EventEnvelope envelope = delegate.publish(draft, cause);
        bus.notifyEvent(envelope.globalPosition());
        return envelope;
    }

    @Override
    public EventEnvelope publishRoot(EventDraft draft)
            throws SequenceConflictException {
        EventEnvelope envelope = delegate.publishRoot(draft);
        bus.notifyEvent(envelope.globalPosition());
        return envelope;
    }
}
