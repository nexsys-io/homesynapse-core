/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.platform.identity.IntegrationId;

/**
 * Sealed root of the pairing-window event payloads (PJ-2, DP-PJ2-2): the window is a
 * declared, time-boxed, RECORDED act, and these two events are its record.
 *
 * <p>Deliberately a sibling of {@link IntegrationLifecycleEvent}, not a member of it —
 * a window event is not a health transition and carries no {@code previousState} /
 * {@code newState}. Both records are published by the ADAPTER (origin
 * {@code INTEGRATION}, subject {@code integration(id)}) and registered through
 * {@link IntegrationEvents#LIFECYCLE_EVENT_CLASSES}, the seam's codec manifest.</p>
 *
 * <ul>
 *   <li>{@link PermitJoinOpened} — {@code permit_join_opened}</li>
 *   <li>{@link PermitJoinClosed} — {@code permit_join_closed}</li>
 * </ul>
 */
public sealed interface PairingWindowEvent extends DomainEvent
        permits PermitJoinOpened, PermitJoinClosed {

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
