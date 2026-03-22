/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test.assertions;

import com.homesynapse.event.CausalContext;
import com.homesynapse.platform.identity.Ulid;

import org.assertj.core.api.AbstractAssert;

import java.util.Objects;

/**
 * AssertJ assertion class for {@link CausalContext}.
 *
 * <p>CausalContext has exactly two fields: {@code correlationId} and
 * {@code causationId}. Root events have a null causation ID; derived
 * events have both fields populated.
 *
 * <p>Usage:
 * <pre>{@code
 * assertThat(context)
 *     .isRootContext()
 *     .hasCorrelationId(expectedId);
 * }</pre>
 *
 * @see HomeSynapseAssertions#assertThat(CausalContext)
 */
public final class CausalContextAssert
        extends AbstractAssert<CausalContextAssert, CausalContext> {

    CausalContextAssert(CausalContext actual) {
        super(actual, CausalContextAssert.class);
    }

    /**
     * Verifies the correlation ID matches.
     *
     * @param expected the expected correlation ULID
     * @return this assertion for chaining
     */
    public CausalContextAssert hasCorrelationId(Ulid expected) {
        isNotNull();
        if (!Objects.equals(actual.correlationId(), expected)) {
            failWithMessage("Expected correlationId <%s> but was <%s>",
                    expected, actual.correlationId());
        }
        return this;
    }

    /**
     * Verifies the causation ID matches.
     *
     * @param expected the expected causation ULID
     * @return this assertion for chaining
     */
    public CausalContextAssert hasCausationId(Ulid expected) {
        isNotNull();
        if (!Objects.equals(actual.causationId(), expected)) {
            failWithMessage("Expected causationId <%s> but was <%s>",
                    expected, actual.causationId());
        }
        return this;
    }

    /**
     * Verifies this represents a root event context (null causation ID).
     *
     * @return this assertion for chaining
     */
    public CausalContextAssert isRootContext() {
        isNotNull();
        if (actual.causationId() != null) {
            failWithMessage(
                    "Expected root context (null causationId) but was <%s>",
                    actual.causationId());
        }
        return this;
    }

    /**
     * Verifies this represents a caused event context (non-null causation ID).
     *
     * @return this assertion for chaining
     */
    public CausalContextAssert isCausedContext() {
        isNotNull();
        if (actual.causationId() == null) {
            failWithMessage(
                    "Expected caused context (non-null causationId) but was null");
        }
        return this;
    }
}
