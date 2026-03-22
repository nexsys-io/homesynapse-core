/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test.assertions;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.SubjectRef;

/**
 * Entry point for HomeSynapse custom AssertJ assertions.
 *
 * <p>Usage:
 * <pre>{@code
 * import static com.homesynapse.test.assertions.HomeSynapseAssertions.assertThat;
 *
 * assertThat(envelope).hasEventType("state_changed").hasPriority(EventPriority.NORMAL);
 * assertThat(context).isRootContext().hasCorrelationId(expectedId);
 * assertThat(subjectRef).isEntity().hasId(expectedUlid);
 * }</pre>
 */
public final class HomeSynapseAssertions {

    private HomeSynapseAssertions() {
        // Static entry point — not instantiable
    }

    /**
     * Creates an assertion for an {@link EventEnvelope}.
     *
     * @param actual the envelope to assert on
     * @return a new {@link EventEnvelopeAssert}
     */
    public static EventEnvelopeAssert assertThat(EventEnvelope actual) {
        return new EventEnvelopeAssert(actual);
    }

    /**
     * Creates an assertion for a {@link CausalContext}.
     *
     * @param actual the causal context to assert on
     * @return a new {@link CausalContextAssert}
     */
    public static CausalContextAssert assertThat(CausalContext actual) {
        return new CausalContextAssert(actual);
    }

    /**
     * Creates an assertion for a {@link SubjectRef}.
     *
     * @param actual the subject reference to assert on
     * @return a new {@link SubjectRefAssert}
     */
    public static SubjectRefAssert assertThat(SubjectRef actual) {
        return new SubjectRefAssert(actual);
    }
}
