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

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_ON_OFF);
        if (value instanceof Boolean on) {
            return List.of(new NormalizedAttribute("on", on, null, null, null));
        }
        return List.of();
    }
}
