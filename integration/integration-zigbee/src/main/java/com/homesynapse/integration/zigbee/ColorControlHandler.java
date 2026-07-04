/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * ColorControl cluster (0x0300) handler, color-temperature scope (AMD-96):
 * {@code colorTemperatureMireds} (0x0007, Uint16) → the canonical
 * {@code color_temp_kelvin} attribute, converted AT INGESTION as
 * {@code K = 1,000,000 / mireds} rounded to nearest (Doc 02 §3.6 Kelvin-canonical
 * + §3.7 convert-at-ingestion), with the RAW MIREDS RETAINED for auditability.
 *
 * <p>The rounding choice is pinned by the measured ±1-mired drift rows: the
 * Wave-1 bulb re-derives commanded 153 as 154 — 6536 K vs 6494 K — and the
 * fixture-replay regression asserts nearest-rounding on both.
 *
 * <p>Full color (hue/sat, xy) is post-MVP (AMD-96); those attributes are
 * deliberately ignored here.
 *
 * <p>Thread-safe: stateless behavior.
 */
final class ColorControlHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0300;
    static final int ATTRIBUTE_COLOR_TEMPERATURE_MIREDS = 0x0007;

    ColorControlHandler(IEEEAddress device, Clock clock) {
        super(device, clock);
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_COLOR_TEMPERATURE_MIREDS);
        if (value instanceof Long mireds && mireds > 0) {
            long kelvin = Math.round(1_000_000.0 / mireds);
            return List.of(new NormalizedAttribute("color_temp_kelvin", kelvin,
                    "K", String.valueOf(mireds), "mired"));
        }
        return List.of();
    }
}
