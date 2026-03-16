/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Objects;

/**
 * A (namespace, value) tuple identifying a device at the protocol level.
 *
 * <p>Hardware identifiers are used by the discovery pipeline to deduplicate
 * devices across discovery sessions. A single device may have multiple
 * hardware identifiers (e.g., a Zigbee IEEE address and a manufacturer-specific
 * serial number).</p>
 *
 * <p>Example: {@code ("zigbee_ieee", "00:11:22:33:44:55:66:77")}.</p>
 *
 * <p>Defined in Doc 02 §8.2.</p>
 *
 * @param namespace the protocol or identifier namespace, never {@code null}
 * @param value the identifier value within the namespace, never {@code null}
 * @see Device#hardwareIdentifiers()
 * @see DiscoveryPipeline
 * @since 1.0
 */
public record HardwareIdentifier(
        String namespace,
        String value
) {

    /**
     * Validates that both fields are non-null.
     *
     * @throws NullPointerException if {@code namespace} or {@code value} is {@code null}
     */
    public HardwareIdentifier {
        Objects.requireNonNull(namespace, "HardwareIdentifier namespace must not be null");
        Objects.requireNonNull(value, "HardwareIdentifier value must not be null");
    }
}
