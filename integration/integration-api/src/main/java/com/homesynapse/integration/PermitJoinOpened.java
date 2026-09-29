/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.EventType;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.IntegrationId;

import java.time.Instant;
import java.util.Objects;

/**
 * Payload for {@code permit_join_opened} events (PJ-2): an integration opened its pairing
 * window — a declared, time-boxed act with a reason and an actor of record. Published by
 * the adapter after the coordinator accepted the open; the fields mirror the
 * {@link PairingWindow} the adapter returned.
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"}); never {@code null}
 * @param durationSeconds the accepted window length in seconds
 * @param reason          the operator's reason; never {@code null}
 * @param actor           the caller's API key id; never {@code null}
 * @param opensAt         the adapter's clock instant of the accepted open; never {@code null}
 * @param closesAt        {@code opensAt + durationSeconds}; never {@code null}
 * @see PermitJoinClosed
 */
@EventType(EventTypes.PERMIT_JOIN_OPENED)
public record PermitJoinOpened(
        IntegrationId integrationId,
        String integrationType,
        int durationSeconds,
        String reason,
        String actor,
        Instant opensAt,
        Instant closesAt
) implements PairingWindowEvent {

    public PermitJoinOpened {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(opensAt, "opensAt must not be null");
        Objects.requireNonNull(closesAt, "closesAt must not be null");
    }
}
