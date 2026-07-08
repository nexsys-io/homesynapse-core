/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event-local mirror of the device-model {@code HardwareIdentifier} — a
 * {@code (namespace, value)} tuple identifying a device at the protocol level
 * (AMD-99 §3).
 *
 * <p>Lives in {@code com.homesynapse.event} because registration payloads must
 * never reference {@code com.homesynapse.device} types (the AMD-52 JPMS cycle
 * class). The registry projection derives the IEEE&rarr;deviceId map from this
 * component — there is no separate binding field on the payload.</p>
 *
 * <p>NOT an event: carries no {@code EventType} annotation and does not
 * implement {@link DomainEvent} — only the two top-level registration events do.</p>
 *
 * @param namespace the protocol or identifier namespace (e.g. {@code "zigbee"}),
 *        never {@code null}
 * @param value the identifier value within the namespace, never {@code null}
 * @see DeviceRegisteredEvent
 */
public record HardwareIdentifierRef(
        String namespace,
        String value
) {

    /**
     * Validates that both components are non-null.
     *
     * @throws NullPointerException if {@code namespace} or {@code value} is {@code null}
     */
    public HardwareIdentifierRef {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
