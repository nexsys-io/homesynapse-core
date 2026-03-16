/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.platform.identity.Ulid;

import java.util.Objects;

/**
 * Event emitted when a new device is discovered on the protocol network.
 * <p>
 * Priority: NORMAL
 * Doc 01 §4.3
 */
public record DeviceDiscoveredEvent(
        Ulid integrationId,
        String protocolAddress,
        String manufacturer,
        String model
) implements DomainEvent {

    /**
     * Constructs a DeviceDiscoveredEvent with validation.
     *
     * @param integrationId   the ULID of the integration, not null
     * @param protocolAddress the protocol address of the device, not null or blank
     * @param manufacturer    the manufacturer name, not null or blank
     * @param model           the device model, not null or blank
     */
    public DeviceDiscoveredEvent {
        Objects.requireNonNull(integrationId, "integrationId cannot be null");
        Objects.requireNonNull(protocolAddress, "protocolAddress cannot be null");
        if (protocolAddress.isBlank()) {
            throw new IllegalArgumentException("protocolAddress cannot be blank");
        }
        Objects.requireNonNull(manufacturer, "manufacturer cannot be null");
        if (manufacturer.isBlank()) {
            throw new IllegalArgumentException("manufacturer cannot be blank");
        }
        Objects.requireNonNull(model, "model cannot be null");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model cannot be blank");
        }
    }
}
