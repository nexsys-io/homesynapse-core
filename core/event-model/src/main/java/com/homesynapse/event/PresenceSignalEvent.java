/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted for a raw presence signal from an integration.
 * <p>
 * Example signalType values: "wifi_probe", "ble_beacon", "gps_geofence".
 * Priority: DIAGNOSTIC
 * Doc 01 §4.3
 */
@EventType(EventTypes.PRESENCE_SIGNAL)
public record PresenceSignalEvent(
        String signalType,
        String signalSource,
        String signalData
) implements DomainEvent {

    /**
     * Constructs a PresenceSignalEvent with validation.
     *
     * @param signalType   the type of presence signal, not null or blank
     * @param signalSource the source of the signal, not null
     * @param signalData   the signal data payload, not null
     */
    public PresenceSignalEvent {
        Objects.requireNonNull(signalType, "signalType cannot be null");
        if (signalType.isBlank()) {
            throw new IllegalArgumentException("signalType cannot be blank");
        }
        Objects.requireNonNull(signalSource, "signalSource cannot be null");
        Objects.requireNonNull(signalData, "signalData cannot be null");
    }
}
