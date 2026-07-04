/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * PowerConfiguration cluster (0x0001) handler:
 * {@code batteryPercentageRemaining} (0x0021, Uint8 in 0.5 % units) → the
 * canonical {@code battery_pct} percentage ({@code raw / 2}, Doc 08 §3.5), raw
 * retained.
 *
 * <p>Thread-safe: stateless behavior.
 */
final class PowerConfigurationHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0001;
    static final int ATTRIBUTE_BATTERY_PERCENTAGE_REMAINING = 0x0021;
    /** ZCL8 §3.3.2.2.3.2: 0xFF = battery percentage unknown — not an observation. */
    static final long BATTERY_UNKNOWN_MARKER = 0xFF;
    /** ZCL8 §3.3.2.2.3.2: 200 half-percent units = 100 % — the legal ceiling. */
    static final long BATTERY_RAW_MAX = 200;

    private static final Logger log =
            LoggerFactory.getLogger(PowerConfigurationHandler.class);

    PowerConfigurationHandler(IEEEAddress device, Clock clock) {
        super(device, clock);
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_BATTERY_PERCENTAGE_REMAINING);
        if (value instanceof Long raw && raw >= 0) {
            if (raw == BATTERY_UNKNOWN_MARKER) {
                return List.of();   // F-9: the unknown-battery marker never observes
            }
            if (raw > BATTERY_RAW_MAX) {
                log.debug("battery percentage 0x{} is out of the ZCL band (>200 "
                                + "half-percent units); skipped (F-9)",
                        Long.toHexString(raw));
                return List.of();
            }
            return List.of(new NormalizedAttribute("battery_pct", raw / 2, "%",
                    String.valueOf(raw), null));
        }
        return List.of();
    }
}
