/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import java.util.Optional;

/**
 * The OPTIONAL pairing-window capability of an adapter (PJ-2, DP-PJ2-1): an adapter that
 * can admit new devices for a bounded time implements it beside
 * {@link IntegrationAdapter}; {@code IntegrationAdapter}'s surface (AMD-55) is untouched
 * and the supervisor discovers the capability by {@code instanceof}.
 *
 * <p><strong>Called on the adapter's command executor only; the supervisor is the
 * caller.</strong> {@link #openPairingWindow} runs on the hosting supervisor's
 * single-threaded per-adapter command executor — the same thread the command write path
 * uses — never on an HTTP thread and never concurrently with a command. It may block on
 * the coordinator (milliseconds), and it publishes the window's events of record itself
 * ({@link PermitJoinOpened}, {@link PermitJoinClosed}); the REST layer publishes
 * nothing.</p>
 */
public interface PairingWindowControl {

    /**
     * Opens the pairing window for {@code request}. An open while a window is already
     * open closes the prior window first (ONE {@link PermitJoinClosed}, cause
     * {@code superseded} — or {@code elapsed} if its end had already passed unobserved)
     * and then opens the new one.
     *
     * @param request the validated request; never {@code null}
     * @return the window as opened — its {@code opensAt} is the adapter's clock after the
     *         coordinator accepted the open; never {@code null}
     * @throws RuntimeException the coordinator's rejection (an NCP NAK) or transport
     *         failure, propagated so the caller's future completes exceptionally and the
     *         window stays honestly closed
     */
    PairingWindow openPairingWindow(PairingWindowRequest request);

    /**
     * The window currently open, if any — empty once the window's end has passed, whether
     * or not the adapter has yet recorded the close (never-false-ALIVE).
     *
     * @return the open window, or empty; never {@code null}
     */
    Optional<PairingWindow> currentPairingWindow();
}
