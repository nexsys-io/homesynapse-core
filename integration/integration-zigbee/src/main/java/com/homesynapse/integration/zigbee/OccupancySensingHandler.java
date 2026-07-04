/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * OccupancySensing cluster (0x0406) handler — the hero-trigger cluster:
 * {@code occupancy} (0x0000, Bitmap8) bit 0 → the canonical {@code occupied}
 * boolean (Doc 08 §3.5; the measured SNZB-03P active path — the hero automation
 * binds {@code occupancy.occupied}, never a {@code motion} archetype assumption).
 *
 * <p>Thread-safe: stateless behavior.
 */
final class OccupancySensingHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0406;
    static final int ATTRIBUTE_OCCUPANCY = 0x0000;

    OccupancySensingHandler(IEEEAddress device, Clock clock) {
        super(device, clock);
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_OCCUPANCY);
        if (value instanceof Long bitmap) {
            boolean occupied = (bitmap & 0x01) != 0;
            return List.of(new NormalizedAttribute("occupied", occupied, null,
                    String.valueOf(bitmap), null));
        }
        return List.of();
    }
}
