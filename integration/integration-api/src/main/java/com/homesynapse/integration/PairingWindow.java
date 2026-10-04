/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.platform.identity.IntegrationId;

import java.time.Instant;
import java.util.Objects;

/**
 * An open pairing window as the adapter holds it (PJ-2): the interval the coordinator
 * admits joins, plus the request that opened it — including, since J2b, the request's
 * device scope. Returned by {@link PairingWindowControl#openPairingWindow} and mirrored
 * field-for-field by the {@link PermitJoinOpened} event of record.
 *
 * @param integrationId   the integration that opened the window; never {@code null}
 * @param opensAt         the adapter's clock instant after the coordinator accepted the
 *                        open; never {@code null}
 * @param closesAt        {@code opensAt + durationSeconds} — asserted, never computed
 *                        elsewhere; never {@code null}
 * @param durationSeconds the accepted window length in seconds
 * @param reason          the request's reason; never {@code null}
 * @param actor           the request's actor; never {@code null}
 * @param scope           the one device this window admits — the request's scope in its
 *                        canonical form ({@code 0x} + 16 upper-case hex) — or
 *                        {@code null} for an un-scoped window (J2b)
 */
public record PairingWindow(
        IntegrationId integrationId,
        Instant opensAt,
        Instant closesAt,
        int durationSeconds,
        String reason,
        String actor,
        String scope
) {

    public PairingWindow {
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(opensAt, "opensAt must not be null");
        Objects.requireNonNull(closesAt, "closesAt must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        if (!closesAt.equals(opensAt.plusSeconds(durationSeconds))) {
            throw new IllegalArgumentException("closesAt must equal opensAt + "
                    + durationSeconds + " s: opensAt=" + opensAt + " closesAt=" + closesAt);
        }
    }
}
