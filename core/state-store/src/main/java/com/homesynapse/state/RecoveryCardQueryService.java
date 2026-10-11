/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.platform.identity.DeviceId;

import java.util.Optional;

/**
 * The read interface of the recovery-card view (AMD-103 R-F; AVAIL-API-1) — what the REST
 * API's three entity reads call for the v1.1.7 keys. The view decorates the state
 * projection's {@code availability}; it never governs it (AMD-103 §3).
 *
 * <p><strong>The null state is a law (AMD-103 §3):</strong> while {@link #isLive()} is
 * {@code false} — catching up, after a failed catch-up, or suspended — every v1.1.7 key is
 * {@code null} and {@code meta.recoveryViewPosition} is {@code null}; the consumer keeps its
 * S2 form. A read while not LIVE still returns what the view holds; the renderer gates.</p>
 *
 * <p>Thread-safe: lock-free or briefly locked reads from any thread.</p>
 */
public interface RecoveryCardQueryService {

    /**
     * The device's row — the newest probe record, the newest contract, the newest
     * {@code availability_changed} instant.
     *
     * @param deviceId the device, never {@code null}
     * @return the row, or empty when no record of any of the three types reached the view
     */
    Optional<RecoveryCardRecord> recordFor(DeviceId deviceId);

    /**
     * R-G's N: the count of devices whose NEWEST contract names an asked class — the
     * projection's own count over the whole view, never a page's.
     *
     * @return the asked-device count
     */
    int askedCount();

    /**
     * Whether the projection reached LIVE (the bus's REPLAY → LIVE handoff after the catch-up)
     * and is not suspended.
     *
     * @return {@code true} when the keys are served
     */
    boolean isLive();

    /**
     * The projection's position — the {@code global_position} of the last row applied, or the
     * catch-up's head; {@code meta.recoveryViewPosition} while LIVE.
     *
     * @return the position, 0 before anything was applied
     */
    long position();

    /**
     * The service of a projection that never became LIVE: no rows, no asked devices, position
     * 0 — the composition root's shape before the projection exists and the test-facing
     * endpoints' default.
     *
     * @return a service that is never LIVE
     */
    static RecoveryCardQueryService notLive() {
        return new RecoveryCardQueryService() {
            @Override
            public Optional<RecoveryCardRecord> recordFor(DeviceId deviceId) {
                return Optional.empty();
            }

            @Override
            public int askedCount() {
                return 0;
            }

            @Override
            public boolean isLive() {
                return false;
            }

            @Override
            public long position() {
                return 0L;
            }
        };
    }
}
