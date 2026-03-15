/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for an integration adapter instance within HomeSynapse.
 *
 * <p>An integration adapter bridges HomeSynapse to a specific protocol or external system
 * (e.g., Zigbee, Z-Wave, MQTT). Each running adapter instance has a unique
 * {@code IntegrationId} that identifies it as the producer of events and the recipient
 * of commands dispatched through the integration runtime.</p>
 *
 * <p>The integration ID appears in event envelopes as part of the origin metadata and is
 * used by the command dispatch service to route {@code command_dispatched} events to the
 * correct adapter. Integration lifecycle events ({@code integration_started},
 * {@code integration_stopped}, {@code integration_health_changed}) use this ID as their
 * subject reference.</p>
 *
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID identifying this integration adapter, never {@code null}
 */
public record IntegrationId(Ulid value) implements Comparable<IntegrationId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public IntegrationId {
        Objects.requireNonNull(value, "IntegrationId value must not be null");
    }

    /**
     * Creates an {@code IntegrationId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code IntegrationId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static IntegrationId of(Ulid value) {
        return new IntegrationId(value);
    }

    /**
     * Creates an {@code IntegrationId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code IntegrationId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static IntegrationId parse(String crockford) {
        return new IntegrationId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(IntegrationId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
