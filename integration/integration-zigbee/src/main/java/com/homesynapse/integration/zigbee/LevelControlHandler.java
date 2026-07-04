/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * LevelControl cluster (0x0008) handler: {@code currentLevel} (0x0000, Uint8
 * 0–254) → the canonical {@code brightness} attribute (Doc 08 §3.5 — the 0–254
 * level is canonical; percentage derives at query time).
 *
 * <p>Thread-safe: stateless behavior.
 */
final class LevelControlHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0008;
    static final int ATTRIBUTE_CURRENT_LEVEL = 0x0000;

    LevelControlHandler(IEEEAddress device, Clock clock) {
        super(device, clock);
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_CURRENT_LEVEL);
        if (value instanceof Long level && level >= 0 && level <= 254) {
            return List.of(new NormalizedAttribute("brightness", level, null,
                    null, null));
        }
        return List.of();
    }
}
