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
 * Payload for {@code join_rejected} events (J2b, D-v94-24): <em>a device that is not yours
 * tried to join</em>. The trust center DENIED a joiner (a 0x0024 {@code trustCenterJoin}
 * with {@code decision=DENY_JOIN}) — under a device-SCOPED pairing window, a joiner without
 * a transient-key entry (TC policy 0x0013, {@code JOINS_USE_INSTALL_CODE_KEY}); between
 * windows, a joiner a router still permitted. Published by the ADAPTER beside the ingestion
 * unit's {@code zigbee.device_join_failed} WARN (the unit stays event-free and NEVER creates
 * a device); schema version 1.
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"}); never {@code null}
 * @param joiner          the denied device's IEEE address, {@code 0x} + 16 upper-case hex;
 *                        never {@code null}
 * @param scope           the open window's scope — the one device it admits, canonical
 *                        {@code 0x} + 16 upper-case hex — when the window was scoped;
 *                        {@code null} for an un-scoped window or no window at all
 * @param status          the trust center's device-update status word ({@code UNSECURED_JOIN},
 *                        {@code SECURED_REJOIN}, {@code UNSECURED_REJOIN}, or the hex of an
 *                        unknown byte); never {@code null}
 * @param at              the adapter's clock instant of the denial; never {@code null}
 * @see PermitJoinOpened
 * @see PermitJoinClosed
 */
@EventType(EventTypes.JOIN_REJECTED)
public record JoinRejected(
        IntegrationId integrationId,
        String integrationType,
        String joiner,
        String scope,
        String status,
        Instant at
) implements PairingWindowEvent {

    public JoinRejected {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(joiner, "joiner must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(at, "at must not be null");
    }
}
