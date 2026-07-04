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

    /** ZCL8 §5.2.2.3.14: Move to Color Temperature command id. */
    static final int COMMAND_MOVE_TO_COLOR_TEMPERATURE = 0x0A;
    /** ZCL8 §5.2.2.2.11: the largest legal non-reserved mired value. */
    static final int MAX_LEGAL_MIREDS = 0xFEFF;

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

    @Override
    public ZclFrame buildCommand(String commandType,
            Map<String, Object> parameters) {
        if (!"set_color_temperature".equals(commandType)) {
            return super.buildCommand(commandType, parameters);
        }
        // ZCL8 §5.2.2.3.14 Move to Color Temperature: [mireds u16 LE][transition u16 LE].
        // The ingestion-side Kelvin canonicalization (AMD-96, K = round(1e6 / mireds))
        // runs in REVERSE here and ONLY here: mireds = round(1e6 / kelvin), clamped to
        // the ZCL-legal band [1, 0xFEFF]. Round-trip drift is the ±1-mired class the
        // capability tolerance absorbs — never corrected with fudge factors.
        int kelvin = intParameter(parameters, "kelvin", 0);
        int mireds = kelvin <= 0 ? MAX_LEGAL_MIREDS
                : Math.clamp(Math.round(1_000_000.0f / kelvin), 1, MAX_LEGAL_MIREDS);
        int transition = transitionDeciseconds(parameters);
        byte[] payload = {
            (byte) (mireds & 0xFF),
            (byte) ((mireds >> 8) & 0xFF),
            (byte) (transition & 0xFF),
            (byte) ((transition >> 8) & 0xFF),
        };
        return new ZclFrame(1, 1, CLUSTER_ID, COMMAND_MOVE_TO_COLOR_TEMPERATURE,
                true, 0, payload);
    }
}
