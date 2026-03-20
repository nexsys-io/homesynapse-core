/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.Objects;

import com.homesynapse.platform.identity.Ulid;

/**
 * Typed identifier for an individual automation Run within HomeSynapse.
 *
 * <p>Unlike {@link com.homesynapse.platform.identity.AutomationId} (which is shared
 * across subsystems and defined in platform-api), {@code RunId} is automation-internal.
 * Each Run of an automation receives a unique {@code RunId} generated at initiation time.
 * The Run's event stream is keyed by the automation's subject reference, not the Run ID —
 * the Run ID appears as a payload field for correlation.</p>
 *
 * <p>The wrapped value is a {@link Ulid} per LTD-04.</p>
 *
 * @param value the ULID identifying this Run, never {@code null}
 * @see RunManager
 * @see RunContext
 */
public record RunId(Ulid value) implements Comparable<RunId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public RunId {
        Objects.requireNonNull(value, "RunId value must not be null");
    }

    @Override
    public int compareTo(RunId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
