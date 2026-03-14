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
 * <p>The wrapped value is a 26-character ULID string in canonical Crockford Base32 encoding
 * per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID string identifying this automation, never {@code null} or blank
 */
public record AutomationId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public AutomationId {
        Objects.requireNonNull(value, "AutomationId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AutomationId value must not be blank");
        }
    }

    /**
     * Creates an {@code AutomationId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code AutomationId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static AutomationId of(String value) {
        return new AutomationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
