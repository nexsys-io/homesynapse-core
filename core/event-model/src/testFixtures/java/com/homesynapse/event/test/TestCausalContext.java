/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.test;

import java.util.Objects;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.platform.identity.UlidFactory;

/**
 * Pre-built {@link CausalContext} instances and convenience methods for
 * constructing causal chains in tests.
 *
 * <p>The high-value method is {@link #chainFrom(EventEnvelope)}, which reduces
 * the boilerplate for constructing a derived causal context from:</p>
 *
 * <pre>{@code
 * CausalContext cause = CausalContext.chain(
 *     root.causalContext().correlationId(),
 *     root.eventId().value());
 * }</pre>
 *
 * <p>to:</p>
 *
 * <pre>{@code
 * CausalContext cause = TestCausalContext.chainFrom(root);
 * }</pre>
 *
 * <p>This class is a test fixture — it lives in the {@code testFixtures} source set
 * and is consumed by downstream modules via
 * {@code testFixtures(project(":core:event-model"))}.</p>
 *
 * @see CausalContext
 * @see TestEventFactory
 */
public final class TestCausalContext {

    private TestCausalContext() {
        // Utility class — no instantiation.
    }

    /**
     * Creates a fresh root causal context with a newly generated ULID as the
     * correlation ID and {@code null} causation ID.
     *
     * <p>Equivalent to {@code CausalContext.root(UlidFactory.generate())} but
     * more readable in test code.</p>
     *
     * @return a root CausalContext where {@link CausalContext#isRoot()} is {@code true}
     */
    public static CausalContext root() {
        return CausalContext.root(UlidFactory.generate());
    }

    /**
     * Creates a root causal context tied to a specific event ID.
     *
     * <p>Use this when you have already generated the event ID and need the
     * root context to use the same ULID as its correlation ID, matching the
     * behavior of {@link com.homesynapse.event.EventPublisher#publishRoot}.</p>
     *
     * @param eventId the event ID whose ULID becomes the correlation ID;
     *                never {@code null}
     * @return a root CausalContext with correlationId equal to {@code eventId.value()}
     * @throws NullPointerException if {@code eventId} is {@code null}
     */
    public static CausalContext rootFor(EventId eventId) {
        Objects.requireNonNull(eventId, "eventId must not be null");
        return CausalContext.root(eventId.value());
    }

    /**
     * Extracts the chain context from a causing envelope for derived event
     * publication.
     *
     * <p>The returned context inherits the correlation ID from the causing
     * envelope's causal context and sets the causation ID to the causing
     * envelope's event ID — exactly the propagation rule defined in
     * Doc 01 §4.5.</p>
     *
     * @param causingEnvelope the event that caused the derived event;
     *                        never {@code null}
     * @return a derived CausalContext suitable for passing to
     *         {@link com.homesynapse.event.EventPublisher#publish}
     * @throws NullPointerException if {@code causingEnvelope} is {@code null}
     */
    public static CausalContext chainFrom(EventEnvelope causingEnvelope) {
        Objects.requireNonNull(causingEnvelope, "causingEnvelope must not be null");
        return CausalContext.chain(
                causingEnvelope.causalContext().correlationId(),
                causingEnvelope.eventId().value()
        );
    }
}
