/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * An enumerated serial port as seen by the {@link PortLocator.PortEnumerator} seam —
 * Strings and ints only, no jSerialComm type (D-M92-1).
 *
 * <p>The {@code descriptor} field exists for LOGGING ONLY: locator logic must never
 * match on it (AMD-96/E2 — descriptor strings are vendor-rebranded and unstable; the
 * bench unit reports SONOFF-branded strings, not {@code Silicon_Labs_CP2102N}).
 *
 * <p>Thread-safe: immutable record.
 *
 * @param systemPath the device node path (e.g. {@code /dev/ttyUSB0}), never {@code null}
 * @param byIdPath the stable {@code /dev/serial/by-id} path, or {@code null} where
 *                 unavailable (non-Linux hosts, unnamed adapters)
 * @param vendorId the USB vendor id, or {@code -1} when unknown
 * @param productId the USB product id, or {@code -1} when unknown
 * @param descriptor the USB descriptor string — diagnostics only, never matched;
 *                   nullable
 * @see PortLocator
 */
record PortCandidate(String systemPath, String byIdPath, int vendorId, int productId,
        String descriptor) {

    /**
     * Creates a port candidate.
     *
     * @param systemPath never {@code null}
     * @param byIdPath nullable
     * @param vendorId {@code -1} when unknown
     * @param productId {@code -1} when unknown
     * @param descriptor nullable, never matched
     */
    PortCandidate {
        Objects.requireNonNull(systemPath, "systemPath");
    }

    /**
     * Returns the stable identity path: the by-id path where available, else the
     * system path.
     *
     * @return the most stable known path, never {@code null}
     */
    String stablePath() {
        return byIdPath != null ? byIdPath : systemPath;
    }
}
