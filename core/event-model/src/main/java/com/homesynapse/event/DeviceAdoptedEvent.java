/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import com.homesynapse.platform.identity.Ulid;

import java.util.Objects;

/**
 * Event emitted when a discovered device is accepted into the system.
 * <p>
 * Priority: NORMAL
 * Doc 01 §4.3
 */
public record DeviceAdoptedEvent(
        Ulid entityId
) implements DomainEvent {

    /**
     * Constructs a DeviceAdoptedEvent with validation.
     *
     * @param entityId the ULID of the entity created for the adopted device, not null
     */
    public DeviceAdoptedEvent {
        Objects.requireNonNull(entityId, "entityId cannot be null");
    }
}
