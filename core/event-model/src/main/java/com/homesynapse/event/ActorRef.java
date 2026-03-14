/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import java.util.Objects;

import com.homesynapse.platform.identity.PersonId;

/**
 * Identifies the user attributable to an event in a causal chain.
 *
 * <p>The actor reference carries the authenticated user identity that initiated or is
 * attributable to an event (Doc 01 §4.1 {@code actor_ref}, INV-MU-01). It is propagated
 * through the causal chain: when a user issues a command that triggers a state change
 * that triggers an automation, the actor reference on the automation event still points
 * to the original user.</p>
 *
 * <p>The actor reference is {@code null} when no user is attributable to the event —
 * for example, device-autonomous events ({@code DEVICE_AUTONOMOUS} origin),
 * system lifecycle events ({@code SYSTEM} origin), or events from integrations
 * that lack user attribution.</p>
 *
 * <p>The wrapped value is the ULID of a {@link PersonId} in canonical Crockford Base32
 * encoding per LTD-04. The actor reference is stored alongside causality fields in the
 * event envelope rather than in the payload because user attribution is a cross-cutting
 * concern, not event-type-specific data.</p>
 *
 * @param value the ULID string of the person, never {@code null} or blank
 * @see PersonId
 * @see CausalContext
 */
public record ActorRef(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public ActorRef {
        Objects.requireNonNull(value, "ActorRef value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ActorRef value must not be blank");
        }
    }

    /**
     * Creates an {@code ActorRef} from the given ULID string.
     *
     * @param value the ULID string of the person, never {@code null} or blank
     * @return a new {@code ActorRef} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static ActorRef of(String value) {
        return new ActorRef(value);
    }

    /**
     * Creates an {@code ActorRef} from a {@link PersonId}.
     *
     * @param personId the person identifier, never {@code null}
     * @return a new {@code ActorRef} instance
     */
    public static ActorRef fromPerson(PersonId personId) {
        Objects.requireNonNull(personId, "personId must not be null");
        return new ActorRef(personId.value());
    }

    @Override
    public String toString() {
        return value;
    }
}
