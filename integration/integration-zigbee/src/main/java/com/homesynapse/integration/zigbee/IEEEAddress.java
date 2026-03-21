/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * Permanent 64-bit IEEE EUI-64 hardware identifier for Zigbee devices.
 *
 * <p>IEEE addresses are the stable, permanent identifiers for Zigbee devices on the mesh
 * network. Unlike 16-bit network addresses (which are transient and reassigned on rejoin),
 * IEEE addresses are burned into the radio hardware and remain constant across adapter
 * restarts, network reforms, and power cycles. The Zigbee adapter uses IEEE addresses as
 * the canonical device identity within the {@code zigbee} namespace (Identity and
 * Addressing Model §6).
 *
 * <p>This is NOT a ULID — it is a protocol-specific hardware identifier. HomeSynapse's
 * typed ULID wrappers ({@link com.homesynapse.platform.identity.DeviceId},
 * {@link com.homesynapse.platform.identity.EntityId}) represent domain-level identity
 * and are assigned during device adoption. The IEEE address is the bridge between the
 * protocol world and the domain world.
 *
 * <p>Doc 08 §5 contract: "Hardware identifiers are stable across adapter restarts."
 *
 * <p>Thread-safe: immutable record.
 *
 * @param value the 64-bit IEEE EUI-64 address as an unsigned long
 * @see com.homesynapse.platform.identity.DeviceId
 * @see com.homesynapse.platform.identity.EntityId
 */
public record IEEEAddress(long value) {

    /**
     * Creates an IEEE address with range validation.
     *
     * @param value the 64-bit IEEE EUI-64 address; must not be negative when interpreted
     *              as a signed long (the full unsigned 64-bit range is supported via
     *              {@link Long#toUnsignedString})
     */
    public IEEEAddress {
        // No range restriction — all 64-bit patterns are valid IEEE addresses.
        // 0x0000000000000000 is reserved but may appear in protocol frames.
    }

    /**
     * Formats this IEEE address as a zero-padded uppercase hexadecimal string
     * prefixed with {@code "0x"}.
     *
     * <p>Example: {@code "0x00158D00012345AB"}.
     *
     * @return the formatted hex string, never {@code null}
     */
    public String toHexString() {
        return "0x" + String.format("%016X", value);
    }

    /**
     * Parses an IEEE address from a hexadecimal string.
     *
     * <p>Accepts both {@code "0x"}-prefixed and plain hexadecimal strings.
     * Examples: {@code "0x00158D00012345AB"}, {@code "00158D00012345AB"}.
     *
     * @param hex the hexadecimal string to parse, never {@code null}
     * @return the parsed IEEE address, never {@code null}
     * @throws NumberFormatException if the string is not a valid hexadecimal value
     * @throws NullPointerException if {@code hex} is {@code null}
     */
    public static IEEEAddress fromHexString(String hex) {
        java.util.Objects.requireNonNull(hex, "hex must not be null");
        String stripped = hex.startsWith("0x") || hex.startsWith("0X")
                ? hex.substring(2)
                : hex;
        return new IEEEAddress(Long.parseUnsignedLong(stripped, 16));
    }

    /**
     * Returns the hexadecimal string representation of this IEEE address.
     *
     * <p>Delegates to {@link #toHexString()}.
     *
     * @return the formatted hex string, never {@code null}
     */
    @Override
    public String toString() {
        return toHexString();
    }
}
