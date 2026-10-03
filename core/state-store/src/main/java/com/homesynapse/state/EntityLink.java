/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import java.time.Instant;
import java.util.Objects;

/**
 * The read model's last link reading for an entity's device (J1 / LINK-READ-2): the last-hop
 * LQI and RSSI the integration kept for the device, with the receipt instant of the frame that
 * delivered them. Carried onto {@link EntityState#link()} from the {@code availability_changed}
 * event (schema version 2) and rendered on the read API as
 * {@code "link": {"lqi": n, "rssiDbm": n, "at": "…Z"}}.
 *
 * <p>The state store owns this value: the zigbee {@code LinkReading} type never crosses the
 * module boundary. The three are written together — the event's invariant is all-or-none — so
 * an {@code EntityLink} is either whole or absent ({@code null} on the entity state), and
 * {@code at} is never {@code null} here. The ranges are the integration's (an EZSP LQI is
 * 0..255, an RSSI a signed dBm); the read model mirrors the values and does not re-validate
 * them.
 *
 * @param lqi the last-hop link quality indicator
 * @param rssiDbm the last-hop received signal strength in dBm
 * @param at the receipt instant of the frame that delivered the reading, never {@code null}
 * @see EntityState#link()
 * @since 1.0
 */
public record EntityLink(int lqi, int rssiDbm, Instant at) {

    /**
     * Validates the reading's instant.
     *
     * @param lqi the last-hop LQI
     * @param rssiDbm the last-hop RSSI in dBm
     * @param at the frame's receipt instant, never {@code null}
     */
    public EntityLink {
        Objects.requireNonNull(at, "at");
    }
}
