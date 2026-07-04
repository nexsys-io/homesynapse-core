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

    /** ZCL8 §3.10.2.3.5: Move to Level (with On/Off) command id. */
    static final int COMMAND_MOVE_TO_LEVEL_WITH_ON_OFF = 0x04;

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

    @Override
    public ZclFrame buildCommand(String commandType,
            Map<String, Object> parameters) {
        if (!"set_brightness".equals(commandType)) {
            return super.buildCommand(commandType, parameters);
        }
        // ZCL8 §3.10.2.3.5 Move to Level (with On/Off): [level u8][transition u16 LE];
        // level = round(percent × 254 / 100) clamped [0, 254] — the capability domain
        // is percent; the 0–254 wire level exists only at this boundary.
        int percent = intParameter(parameters, "level", 0);
        int level = Math.clamp(Math.round(percent * 254 / 100.0f), 0, 254);
        int transition = transitionDeciseconds(parameters);
        byte[] payload = {
            (byte) level,
            (byte) (transition & 0xFF),
            (byte) ((transition >> 8) & 0xFF),
        };
        return new ZclFrame(1, 1, CLUSTER_ID, COMMAND_MOVE_TO_LEVEL_WITH_ON_OFF,
                true, 0, payload);
    }
}
