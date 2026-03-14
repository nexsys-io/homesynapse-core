/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

/**
 * Typed identifier for a causal correlation chain.
 *
 * <p>The correlation ID links all events in a single causal chain back to the root event
 * that initiated the chain (Doc 01 §4.1). For root events (external stimuli with no prior
 * event cause), the correlation ID is set to the event's own {@code event_id} at append
 * time. For derived events, the correlation ID is inherited unchanged from the causing
 * event's causal context.</p>
 *
 * <p><strong>Non-null invariant:</strong> The correlation ID is non-null for every event
 * in the system. Root events self-correlate. This guarantees that trace queries never
 * need to special-case roots — querying by {@code correlation_id} always returns the
 * complete chain from root to leaf.</p>
 *
 * <p>The wrapped value is a 26-character ULID string (the {@code event_id} of the root
 * event in the chain) in canonical Crockford Base32 encoding per LTD-04.</p>
 *
 * @param value the ULID string of the root event in this correlation chain,
 *              never {@code null} or blank
 * @see CausationId
 * @see CausalContext
 */
public record CorrelationId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public CorrelationId {
        Objects.requireNonNull(value, "CorrelationId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CorrelationId value must not be blank");
        }
    }

    /**
     * Creates a {@code CorrelationId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code CorrelationId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static CorrelationId of(String value) {
        return new CorrelationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
