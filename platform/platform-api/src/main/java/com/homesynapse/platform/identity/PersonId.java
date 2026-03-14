/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for a person (occupant or user) known to HomeSynapse.
 *
 * <p>A person represents a human who interacts with the home — either as a resident whose
 * presence is tracked, or as a user who issues commands through the UI or API. Person
 * identity is the subject reference for presence events ({@code presence_signal},
 * {@code presence_changed}) and is carried as the {@code actor_ref} on events initiated
 * by authenticated users (INV-MU-01).</p>
 *
 * <p>Person identities are privacy-sensitive. Events keyed by {@code PersonId} fall under
 * the {@code presence} event category, which is a crypto-shredding boundary per
 * INV-PD-07.</p>
 *
 * <p>The wrapped value is a 26-character ULID string in canonical Crockford Base32 encoding
 * per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID string identifying this person, never {@code null} or blank
 */
public record PersonId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public PersonId {
        Objects.requireNonNull(value, "PersonId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("PersonId value must not be blank");
        }
    }

    /**
     * Creates a {@code PersonId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code PersonId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static PersonId of(String value) {
        return new PersonId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
