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
 * RelativeHumidity cluster (0x0405) handler:
 * {@code measuredValue} (0x0000, Uint16 in 0.01 %RH units) → the canonical
 * {@code humidity_pct} float ({@code raw / 100.0}, Doc 08 §3.5), raw retained.
 *
 * <p>The ZCL invalid sentinel {@code 0xFFFF} and out-of-band values above
 * 10000 (100 %RH — the legal ceiling) never observe (honest absence — the F-9
 * discipline; a fabricated reading could feed automations real-world lies).
 *
 * <p>Thread-safe: stateless behavior.
 */
final class RelativeHumidityHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0405;
    static final int ATTRIBUTE_MEASURED_VALUE = 0x0000;
    /** ZCL8 §4.7 invalid-measurement sentinel (uint16 — arrives unsigned). */
    static final long INVALID_MEASURED_VALUE = 0xFFFF;
    /** ZCL8 §4.7: MeasuredValue = 100 × %RH; 10000 = 100 % — the legal ceiling. */
    static final long MEASURED_VALUE_MAX = 10_000;

    private static final Logger log =
            LoggerFactory.getLogger(RelativeHumidityHandler.class);

    RelativeHumidityHandler(IEEEAddress device, Clock clock) {
        super(device, clock);
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_MEASURED_VALUE);
        if (value instanceof Long raw && raw >= 0) {
            if (raw == INVALID_MEASURED_VALUE) {
                return List.of();   // the invalid marker never observes (F-9)
            }
            if (raw > MEASURED_VALUE_MAX) {
                log.debug("humidity 0x{} is out of the ZCL band (>10000 = "
                                + "100 %RH); skipped (F-9)",
                        Long.toHexString(raw));
                return List.of();
            }
            return List.of(new NormalizedAttribute("humidity_pct", raw / 100.0,
                    "%", String.valueOf(raw), null));
        }
        return List.of();
    }
}
