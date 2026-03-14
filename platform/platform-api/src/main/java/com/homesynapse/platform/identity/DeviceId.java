/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.platform.identity;

import java.util.Objects;

/**
 * Typed identifier for a physical device managed by HomeSynapse.
 *
 * <p>A device represents a single piece of physical hardware connected through an integration
 * adapter (e.g., a Zigbee smart bulb, a Z-Wave door lock). A device may expose one or more
 * {@linkplain EntityId entities} — the logical capabilities of the hardware.</p>
 *
 * <p>Device identity is tied to the physical hardware. When a device is replaced, a new
 * {@code DeviceId} is assigned. The entities previously backed by the old device can be
 * transferred to the new device via {@code entity_transferred}, preserving entity-level
 * event history.</p>
 *
 * <p>The wrapped value is a 26-character ULID string in canonical Crockford Base32 encoding
 * per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID string identifying this device, never {@code null} or blank
 * @see EntityId
 */
public record DeviceId(String value) {

    /**
     * Validates that the ULID value is non-null and non-blank.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public DeviceId {
        Objects.requireNonNull(value, "DeviceId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("DeviceId value must not be blank");
        }
    }

    /**
     * Creates a {@code DeviceId} from the given ULID string.
     *
     * @param value the ULID string, never {@code null} or blank
     * @return a new {@code DeviceId} instance
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank
     */
    public static DeviceId of(String value) {
        return new DeviceId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
