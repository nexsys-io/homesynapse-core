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
import java.util.Set;

/**
 * Payload for {@code permit_join_closed} events (PJ-2): an integration's pairing window
 * closed. Exactly ONE close is recorded per window, naming its cause — a plain word so
 * the wire carries it as-is:
 * <ul>
 *   <li>{@value #CAUSE_ELAPSED} — the window ran to its end (the adapter's cycle observed
 *       it; {@code closedAt} is the window's own end, not the cycle's clock)</li>
 *   <li>{@value #CAUSE_SUPERSEDED} — a later open replaced it while still open
 *       ({@code closedAt} is the new open's instant)</li>
 *   <li>{@value #CAUSE_TRANSPORT_REOPENED} — the coordinator transport was reopened; the
 *       reset coordinator holds no window</li>
 *   <li>{@value #CAUSE_SHUTDOWN} — the adapter closed with the window open</li>
 * </ul>
 *
 * @param integrationId   the integration instance identity; never {@code null}
 * @param integrationType the software identity (e.g., {@code "zigbee"}); never {@code null}
 * @param cause           one of {@link #CAUSES}; never {@code null}
 * @param openedAt        the closed window's {@code opensAt}; never {@code null}
 * @param closedAt        when the window ended, per the cause above; never {@code null}
 * @see PermitJoinOpened
 */
@EventType(EventTypes.PERMIT_JOIN_CLOSED)
public record PermitJoinClosed(
        IntegrationId integrationId,
        String integrationType,
        String cause,
        Instant openedAt,
        Instant closedAt
) implements PairingWindowEvent {

    /** The window ran to its end. */
    public static final String CAUSE_ELAPSED = "elapsed";

    /** A later open replaced the window while it was still open. */
    public static final String CAUSE_SUPERSEDED = "superseded";

    /** The coordinator transport was reopened; the reset coordinator holds no window. */
    public static final String CAUSE_TRANSPORT_REOPENED = "transport_reopened";

    /** The adapter closed with the window open. */
    public static final String CAUSE_SHUTDOWN = "shutdown";

    /** The closed set of causes; the constructor rejects any other word. */
    public static final Set<String> CAUSES =
            Set.of(CAUSE_ELAPSED, CAUSE_SUPERSEDED, CAUSE_TRANSPORT_REOPENED, CAUSE_SHUTDOWN);

    public PermitJoinClosed {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(integrationType, "integrationType must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        if (!CAUSES.contains(cause)) {
            throw new IllegalArgumentException("cause must be one of " + CAUSES + ", got '"
                    + cause + "'");
        }
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
    }
}
