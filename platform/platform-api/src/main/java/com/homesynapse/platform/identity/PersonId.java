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
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID identifying this person, never {@code null}
 */
public record PersonId(Ulid value) implements Comparable<PersonId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public PersonId {
        Objects.requireNonNull(value, "PersonId value must not be null");
    }

    /**
     * Creates a {@code PersonId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code PersonId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static PersonId of(Ulid value) {
        return new PersonId(value);
    }

    /**
     * Creates a {@code PersonId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code PersonId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static PersonId parse(String crockford) {
        return new PersonId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(PersonId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
