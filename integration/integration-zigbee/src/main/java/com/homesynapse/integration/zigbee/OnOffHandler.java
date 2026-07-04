/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * OnOff cluster (0x0006) handler: {@code onOff} (0x0000, Bool) → the canonical
 * {@code on} attribute of the {@code on_off} capability (Doc 08 §3.5; the
 * attribute key follows the in-tree capability vocabulary, which authoritatively
 * names it {@code on}).
 *
 * <p>Thread-safe: stateless behavior.
 */
final class OnOffHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0006;
    static final int ATTRIBUTE_ON_OFF = 0x0000;

    OnOffHandler(IEEEAddress device, Clock clock) {
        super(device, clock);
    }

    /** ZCL8 §3.8.2.3: Off command id. */
    static final int COMMAND_OFF = 0x00;
    /** ZCL8 §3.8.2.3: On command id. */
    static final int COMMAND_ON = 0x01;

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_ON_OFF);
        if (value instanceof Boolean on) {
            return List.of(new NormalizedAttribute("on", on, null, null, null));
        }
        return List.of();
    }

    @Override
    public ZclFrame buildCommand(String commandType,
            Map<String, Object> parameters) {
        // ZCL8 §3.8.2.3: On (0x01) / Off (0x00) carry no payload.
        return switch (commandType) {
            case "turn_on" -> new ZclFrame(1, 1, CLUSTER_ID, COMMAND_ON, true, 0,
                    new byte[0]);
            case "turn_off" -> new ZclFrame(1, 1, CLUSTER_ID, COMMAND_OFF, true, 0,
                    new byte[0]);
            default -> super.buildCommand(commandType, parameters);
        };
    }
}
