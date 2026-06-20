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

import java.util.Objects;

/**
 * A forwarding {@link EventPublisher} that bridges the inherent Phase-1 /
 * Phase-2 ordering gap in the composition root.
 *
 * <p>{@code ConfigurationService} (Doc 12 Phase 1, FOUNDATION) is the first
 * subsystem initialization step and requires an {@link EventPublisher} at
 * construction for its best-effort AMD-70 observability events. But the real
 * publisher depends on the event bus, which depends on persistence — both in
 * Phase 2 (DATA_INFRASTRUCTURE). Config's Phase-1 boot events therefore
 * <em>cannot</em> be persisted (no persistence exists yet); this is a physical
 * ordering reality, not a workaround.</p>
 *
 * <p>Before the delegate is wired, publish calls return {@code null} (a no-op
 * drop). This is safe and intentional: the sole consumer is config's
 * {@code publishObservability}, which discards the return and is explicitly
 * best-effort (AMD-70-INV-01 — "the configuration file, not the event log, is
 * the source of truth"). Once Phase 2 builds the real publisher, the composition
 * root calls {@link #setDelegate(EventPublisher)} and all subsequent config
 * events (reload/write) flow to the live bus.</p>
 *
 * <p>Package-private and constructed only by {@code HomeSynapseCore}; not for
 * general reuse (the {@code null}-return contract relaxation holds only because
 * the single consumer discards the return).</p>
 */
final class DeferredEventPublisher implements EventPublisher {

    private volatile EventPublisher delegate;

    DeferredEventPublisher() {
        // Delegate wired post-Phase-2 via setDelegate.
    }

    /**
     * Wires the real publisher. Called once, after the event bus is up.
     *
     * @param delegate the live publisher; never {@code null}
     */
    void setDelegate(EventPublisher delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public EventEnvelope publish(EventDraft draft, CausalContext cause)
            throws SequenceConflictException {
        EventPublisher current = delegate;
        return (current == null) ? null : current.publish(draft, cause);
    }

    @Override
    public EventEnvelope publishRoot(EventDraft draft) throws SequenceConflictException {
        EventPublisher current = delegate;
        return (current == null) ? null : current.publishRoot(draft);
    }
}
