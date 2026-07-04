/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

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

    PowerConfigurationHandler(IEEEAddress device, Clock clock) {
        super(device, clock);
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_BATTERY_PERCENTAGE_REMAINING);
        if (value instanceof Long raw && raw >= 0) {
            return List.of(new NormalizedAttribute("battery_pct", raw / 2, "%",
                    String.valueOf(raw), null));
        }
        return List.of();
    }
}
