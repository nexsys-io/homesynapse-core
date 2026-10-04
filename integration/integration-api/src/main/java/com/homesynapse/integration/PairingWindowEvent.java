/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.platform.identity.IntegrationId;

/**
 * Sealed root of the pairing-window event payloads (PJ-2, DP-PJ2-2; J2b): the window is a
 * declared, time-boxed, RECORDED act, and these three events are its record — the open,
 * the close, and the joiner the trust center turned away.
 *
 * <p>Deliberately a sibling of {@link IntegrationLifecycleEvent}, not a member of it —
 * a window event is not a health transition and carries no {@code previousState} /
 * {@code newState}. All three records are published by the ADAPTER (origin
 * {@code INTEGRATION}, subject {@code integration(id)}) and registered through
 * {@link IntegrationEvents#LIFECYCLE_EVENT_CLASSES}, the seam's codec manifest.</p>
 *
 * <ul>
 *   <li>{@link PermitJoinOpened} — {@code permit_join_opened} (schema 2 since J2b)</li>
 *   <li>{@link PermitJoinClosed} — {@code permit_join_closed}</li>
 *   <li>{@link JoinRejected} — {@code join_rejected} (J2b)</li>
 * </ul>
 */
public sealed interface PairingWindowEvent extends DomainEvent
        permits PermitJoinOpened, PermitJoinClosed, JoinRejected {

    /**
     * Returns the integration whose window this event records.
     *
     * @return the integration ID, never {@code null}
     */
    IntegrationId integrationId();

    /**
     * Returns the software identity of the integration (e.g., {@code "zigbee"}).
     *
     * @return the integration type string, never {@code null}
     */
    String integrationType();
}
