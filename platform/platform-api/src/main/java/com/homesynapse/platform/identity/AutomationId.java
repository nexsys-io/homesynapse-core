/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for an automation rule definition within HomeSynapse.
 *
 * <p>An automation is a user-defined rule that observes events and produces actions.
 * Each automation has a stable identity across edits — modifying an automation's triggers
 * or actions does not change its {@code AutomationId}. The automation's event stream
 * (keyed by this ID as the subject reference) records its full execution history:
 * {@code automation_triggered}, {@code automation_completed}, and any diagnostic events.</p>
 *
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID identifying this automation, never {@code null}
 */
public record AutomationId(Ulid value) implements Comparable<AutomationId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public AutomationId {
        Objects.requireNonNull(value, "AutomationId value must not be null");
    }

    /**
     * Creates an {@code AutomationId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code AutomationId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static AutomationId of(Ulid value) {
        return new AutomationId(value);
    }

    /**
     * Creates an {@code AutomationId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code AutomationId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static AutomationId parse(String crockford) {
        return new AutomationId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(AutomationId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
