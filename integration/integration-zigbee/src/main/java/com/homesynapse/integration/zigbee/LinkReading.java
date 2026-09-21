/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Optional;

/**
 * One last-hop link reading, as the NCP delivered it beside an inbound APS
 * message — the {@code incomingMessageHandler} (0x0045) pair
 * {@code [lastHopLqi u8][lastHopRssi s8]} (parsed by the package-private
 * {@code EzspIncomingMessage}).
 *
 * <p><strong>The type carries the WIRE domain and nothing narrower:</strong>
 * {@code 0 ≤ lqi ≤ 255} (u8) and {@code −128 ≤ rssiDbm ≤ 127} (s8) — the domain
 * a correct decode can legally produce. A value outside it is a programming
 * error (a caller that did not decode u8 / s8 — the unsigned misdecode of the
 * wire byte {@code 0xC4} reads 196, never −60), so the constructor THROWS and
 * never clamps. A physical plausibility — an NCP does not report an RSSI above
 * 0 dBm — is deliberately NOT an invariant here: it is the ingestion unit's
 * once-per-device {@code zigbee.link_reading_suspect} WARN.
 *
 * <p><strong>The frame path never constructs directly.</strong>
 * {@link #fromWire(int, int)} is the total factory — the only place a raw
 * delivered pair is judged — so no value a frame can carry reaches a throw
 * (a diagnostic reading must never end the ingestion loop).
 *
 * <p>Public because it rides {@link AvailabilityTracker}'s signature in the
 * exported package; it carries two {@code int}s and no type of another module.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param lqi the last-hop link quality indicator, 0–255
 * @param rssiDbm the last-hop received signal strength in dBm, −128–127
 */
public record LinkReading(int lqi, int rssiDbm) {

    // ── The EZSP wire domain of the 0x0045 last-hop pair (UG100) ────────────
    private static final int LQI_MIN = 0;
    private static final int LQI_MAX = 0xFF;
    private static final int RSSI_MIN_DBM = Byte.MIN_VALUE;
    private static final int RSSI_MAX_DBM = Byte.MAX_VALUE;

    /**
     * Creates a reading, asserting the wire domain.
     *
     * @param lqi the last-hop LQI, 0–255
     * @param rssiDbm the last-hop RSSI in dBm, −128–127
     * @throws IllegalArgumentException if either value is outside the wire
     *         domain — never clamped
     */
    public LinkReading {
        if (lqi < LQI_MIN || lqi > LQI_MAX) {
            throw new IllegalArgumentException("lqi must be within " + LQI_MIN
                    + ".." + LQI_MAX + " (u8), got " + lqi);
        }
        if (rssiDbm < RSSI_MIN_DBM || rssiDbm > RSSI_MAX_DBM) {
            throw new IllegalArgumentException("rssiDbm must be within "
                    + RSSI_MIN_DBM + ".." + RSSI_MAX_DBM + " (s8), got "
                    + rssiDbm);
        }
    }

    /**
     * The total factory over a raw delivered pair: the reading when the pair is
     * inside the wire domain, empty otherwise. Never throws.
     *
     * @param lqi the delivered last-hop LQI value
     * @param rssi the delivered last-hop RSSI value
     * @return the reading, or empty when the pair is outside the wire domain
     */
    public static Optional<LinkReading> fromWire(int lqi, int rssi) {
        if (lqi < LQI_MIN || lqi > LQI_MAX
                || rssi < RSSI_MIN_DBM || rssi > RSSI_MAX_DBM) {
            return Optional.empty();
        }
        return Optional.of(new LinkReading(lqi, rssi));
    }
}
