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
 * <p>The wrapped value is a 26-character ULID string in canonical Crockford Base32 encoding
 * per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID string identifying this integration adapter, never {@code null} or blank
 */
public record IntegrationId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public IntegrationId {
        Objects.requireNonNull(value, "IntegrationId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("IntegrationId value must not be blank");
        }
    }

    /**
     * Creates an {@code IntegrationId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code IntegrationId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static IntegrationId of(String value) {
        return new IntegrationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
