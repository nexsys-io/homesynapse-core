/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

package com.homesynapse.event;

import com.homesynapse.platform.identity.Ulid;
import java.util.Objects;

/**
 * Event published when a command is dispatched to an integration adapter.
 *
 * This is a DIAGNOSTIC priority event. It indicates that the integration adapter
 * has accepted the command for protocol delivery.
 *
 * See Reference Doc 01 §4.3.
 *
 * @param targetEntityRef ULID of the entity that the command targets. Non-null.
 * @param integrationId ULID of the integration adapter that accepted the command. Non-null.
 * @param protocolMetadata JSON string of protocol-specific dispatch metadata. Non-null.
 */
public record CommandDispatchedEvent(
        Ulid targetEntityRef,
        Ulid integrationId,
        String protocolMetadata
) implements DomainEvent {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if targetEntityRef, integrationId, or protocolMetadata is null
     */
    public CommandDispatchedEvent {
        Objects.requireNonNull(targetEntityRef, "targetEntityRef must not be null");
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(protocolMetadata, "protocolMetadata must not be null");
    }
}
