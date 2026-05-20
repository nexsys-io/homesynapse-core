/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.event.bus.SubscriberMode;

/**
 * Reports the readiness of the State Projection for serving queries.
 *
 * <p>{@code ReadinessSource} is the seam through which a query-side adapter
 * (the {@code MaterializedStateQueryService} landing in M3.6e) gates REST and
 * WebSocket traffic until the projection reaches
 * {@link SubscriberMode#LIVE LIVE}. While the projection is in
 * {@link SubscriberMode#COLD COLD},
 * {@link SubscriberMode#REPLAY REPLAY}, or
 * {@link SubscriberMode#TRANSITION TRANSITION}, the materialized state is
 * either empty or catching up — REST endpoints should return 503 Service
 * Unavailable and WebSocket subscribers should not yet receive snapshots.</p>
 *
 * <h2>Composition-root wiring</h2>
 *
 * <p>The lifecycle module's composition root (M3.6d) implements this
 * interface by delegating to the {@link StateProjection}'s
 * {@link StateProjection#currentMode() currentMode()} accessor. The
 * implementation is owned by the composition root because only it has a
 * reference to the production {@code StateProjection} instance; the
 * state-store module supplies only the interface contract.</p>
 *
 * <h2>Why not on {@link StateQueryService}</h2>
 *
 * <p>{@link StateQueryService#isReady()} returns a {@code boolean} —
 * essentially "is mode == LIVE?". {@code ReadinessSource} exposes the full
 * mode so consumers can distinguish "warming up" from "suspended due to
 * fault" for nuanced 503 messaging. The two methods are complementary: a
 * future {@link StateQueryService} implementation may delegate
 * {@code isReady()} to {@code mode() == LIVE} via this seam.</p>
 *
 * @see SubscriberMode
 * @see StateProjection
 * @see StateQueryService#isReady()
 */
public interface ReadinessSource {

    /**
     * Returns the State Projection's current lifecycle mode.
     *
     * @return the current mode (COLD, REPLAY, TRANSITION, LIVE, or SUSPENDED);
     *         never {@code null}
     */
    SubscriberMode mode();
}
