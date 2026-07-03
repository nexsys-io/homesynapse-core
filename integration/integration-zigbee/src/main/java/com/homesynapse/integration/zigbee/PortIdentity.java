/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * Stable identity of a coordinator serial port (AMD-96/E2, INV-CE-04): USB VID:PID +
 * the stable device path (the {@code /dev/serial/by-id} form where available) + the
 * probe fingerprint (the negotiated stack version, which disambiguates otherwise
 * identical bridges — MG21 vs MG24 present the same CP210x VID:PID).
 *
 * <p>Strings and ints ONLY — no jSerialComm type appears here (D-M92-1). This is a
 * value record, not an entity identity: no ULID is minted (LTD-04 note; DP-B pending).
 * USB descriptor strings are deliberately NOT part of the identity — vendors rebrand
 * them (the bench unit reports SONOFF strings, not {@code Silicon_Labs_CP2102N}).
 *
 * <p>Thread-safe: immutable record.
 *
 * @param vendorId the USB vendor id (e.g. {@code 0x10C4})
 * @param productId the USB product id (e.g. {@code 0xEA60})
 * @param stableId the stable device path — the by-id path where available, else the
 *                 system path; never {@code null}
 * @param probeFingerprint the negotiated stack version from initialization (AMD-96/E6),
 *                         or {@code null} before first contact
 * @see PortLocator
 */
record PortIdentity(int vendorId, int productId, String stableId,
        String probeFingerprint) {

    /**
     * Creates a port identity with validation.
     *
     * @param vendorId must be 0–0xFFFF
     * @param productId must be 0–0xFFFF
     * @param stableId the stable device path, never {@code null}
     * @param probeFingerprint nullable pre-contact
     */
    PortIdentity {
        if (vendorId < 0 || vendorId > 0xFFFF) {
            throw new IllegalArgumentException(
                    "vendorId must be 0-0xFFFF, got " + vendorId);
        }
        if (productId < 0 || productId > 0xFFFF) {
            throw new IllegalArgumentException(
                    "productId must be 0-0xFFFF, got " + productId);
        }
        Objects.requireNonNull(stableId, "stableId");
    }
}
