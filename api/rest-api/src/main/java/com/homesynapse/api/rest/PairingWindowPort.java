/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.platform.identity.IntegrationId;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The REST layer's port to the pairing window (PJ-2, DP-PJ2-1): {@code POST
 * /api/v1/integrations/{integrationId}/permit-join} asks it to open a window; the
 * composition root bridges it to the integration supervisor. Written in JDK + platform
 * types only, so rest-api takes NO module edge to integration-api (the M7.5a
 * Object-erased installer form stands; {@link IntegrationId} is readable through
 * {@code requires transitive com.homesynapse.state} → {@code com.homesynapse.platform}).
 *
 * <p>The open runs on the adapter's own command executor, never on the HTTP thread — the
 * returned future is how the endpoint waits (bounded). Every refusal is the FUTURE's,
 * never a synchronous throw: {@link IllegalStateException} — the integration is not
 * running (or no supervisor exists yet); {@link UnsupportedOperationException} — the
 * adapter has no pairing window; {@link IllegalArgumentException} — the request's
 * bounds; any other throw — the adapter's own failure (the coordinator's rejection).</p>
 */
public interface PairingWindowPort {

    /**
     * Opens the pairing window of {@code integrationId}.
     *
     * @param integrationId   the integration to open; never {@code null}
     * @param durationSeconds the requested window length, 1–254 seconds
     * @param reason          the operator's reason, 1–120 characters; never {@code null}
     * @param actor           the caller's API key id; never {@code null}
     * @return a future completing with the window as opened, or exceptionally as documented
     *         on the type; never {@code null}
     */
    CompletableFuture<PairingWindowView> open(IntegrationId integrationId, int durationSeconds,
                                              String reason, String actor);

    /**
     * The window as the endpoint renders it — the wire's six {@code data} fields.
     *
     * @param integrationId   the integration that opened the window; never {@code null}
     * @param opensAt         the accepted open's instant; never {@code null}
     * @param closesAt        {@code opensAt + durationSeconds}; never {@code null}
     * @param durationSeconds the accepted window length in seconds
     * @param reason          the request's reason; never {@code null}
     * @param actor           the request's actor; never {@code null}
     */
    record PairingWindowView(
            IntegrationId integrationId,
            Instant opensAt,
            Instant closesAt,
            int durationSeconds,
            String reason,
            String actor
    ) {

        public PairingWindowView {
            Objects.requireNonNull(integrationId, "integrationId must not be null");
            Objects.requireNonNull(opensAt, "opensAt must not be null");
            Objects.requireNonNull(closesAt, "closesAt must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
        }
    }
}
