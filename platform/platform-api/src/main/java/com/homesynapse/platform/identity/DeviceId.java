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
 * <p>The wrapped value is a {@link Ulid} per LTD-04. Stored as {@code BLOB(16)} in SQLite.</p>
 *
 * @param value the ULID identifying this device, never {@code null}
 * @see EntityId
 */
public record DeviceId(Ulid value) implements Comparable<DeviceId> {

    /**
     * Validates that the ULID value is non-null.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public DeviceId {
        Objects.requireNonNull(value, "DeviceId value must not be null");
    }

    /**
     * Creates a {@code DeviceId} from the given ULID.
     *
     * @param value the ULID, never {@code null}
     * @return a new {@code DeviceId} instance
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static DeviceId of(Ulid value) {
        return new DeviceId(value);
    }

    /**
     * Creates a {@code DeviceId} by parsing a 26-character Crockford Base32 ULID string.
     *
     * @param crockford the Crockford Base32 encoded ULID, never {@code null}
     * @return a new {@code DeviceId} instance
     * @throws NullPointerException     if {@code crockford} is {@code null}
     * @throws IllegalArgumentException if {@code crockford} is not a valid ULID string
     */
    public static DeviceId parse(String crockford) {
        return new DeviceId(Ulid.parse(crockford));
    }

    @Override
    public int compareTo(DeviceId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
