/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

/**
 * Typed identifier for the immediate causal predecessor of an event.
 *
 * <p>The causation ID records the {@code event_id} of the immediately preceding event in
 * a causal chain (Doc 01 §4.1). It is {@code null} for root events only — events that
 * represent external stimuli with no prior event cause (e.g., a physical button press
 * producing a {@code state_reported} event).</p>
 *
 * <p>Together with {@link CorrelationId}, the causation ID enables full causal chain
 * reconstruction: the correlation ID groups all events in a chain, while the causation
 * ID defines the parent-child ordering within the chain. The Causal Chain Projection
 * (Doc 01 §4.5) indexes these relationships for efficient trace queries.</p>
 *
 * <p>The wrapped value is a 26-character ULID string (the {@code event_id} of the
 * immediately preceding event) in canonical Crockford Base32 encoding per LTD-04.</p>
 *
 * @param value the ULID string of the causing event, never {@code null} or blank
 * @see CorrelationId
 * @see CausalContext
 */
public record CausationId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public CausationId {
        Objects.requireNonNull(value, "CausationId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CausationId value must not be blank");
        }
    }

    /**
     * Creates a {@code CausationId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code CausationId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static CausationId of(String value) {
        return new CausationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
