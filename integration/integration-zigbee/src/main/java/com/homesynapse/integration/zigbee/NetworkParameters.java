package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * Zigbee network identity and security parameters.
 *
 * <p>Defines the RF channel, PAN (Personal Area Network) identifiers, and a reference
 * to the encrypted network key material. The network key is NEVER stored in this record —
 * {@code networkKeyRef} is an opaque reference to the encrypted material in the secrets
 * store (INV-SE-03).
 *
 * <p>Channel selection follows the two-tier model: primary channels (15, 20, 11) are
 * preferred for minimal Wi-Fi interference; fallback channels (21–26) are used when
 * primary channels are congested.
 *
 * <p>Doc 08 §3.13, §4.1.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param channel the Zigbee RF channel (11–26)
 * @param panId the 16-bit PAN identifier (0x0000–0xFFFF)
 * @param extendedPanId the 64-bit extended PAN identifier
 * @param networkKeyRef opaque reference to the encrypted network key in the secrets infrastructure, never {@code null}
 * @see CoordinatorProtocol#formNetwork(NetworkParameters)
 * @see ZigbeeAdapter#networkParameters()
 */
public record NetworkParameters(int channel, int panId, long extendedPanId, String networkKeyRef) {

    /**
     * Creates network parameters with validation.
     *
     * @param channel must be 11–26 per IEEE 802.15.4
     * @param panId must be 0x0000–0xFFFF
     * @param extendedPanId the 64-bit extended PAN ID
     * @param networkKeyRef the secrets store key reference, never {@code null}
     */
    public NetworkParameters {
        if (channel < 11 || channel > 26) {
            throw new IllegalArgumentException(
                    "channel must be 11–26, got " + channel);
        }
        if (panId < 0 || panId > 0xFFFF) {
            throw new IllegalArgumentException(
                    "panId must be 0x0000–0xFFFF, got " + panId);
        }
        Objects.requireNonNull(networkKeyRef, "networkKeyRef must not be null");
    }
}
