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
 * TemperatureMeasurement cluster (0x0402) handler:
 * {@code measuredValue} (0x0000, Int16 in 0.01 °C units) → the canonical
 * {@code temperature_c} float ({@code raw / 100.0}, Doc 08 §3.5), raw retained.
 *
 * <p>The codec's int16 decode sign-extends (ZclCodec type 0x29), so a negative
 * reading arrives already negative — wire {@code 0xF830} arrives as −2000
 * (−20.00 °C) — and the ZCL invalid sentinel (wire {@code 0x8000}) arrives as
 * −32768. An invalid measurement never observes, and neither does a value
 * outside the ZCL legal band (below −273.15 °C, or above the int16 ceiling —
 * reachable only through a mis-typed wire record, e.g. the sentinel bit
 * pattern arriving uint16-typed as 32768): honest absence, the F-9 discipline
 * — a fabricated reading could feed automations real-world lies.
 *
 * <p>Thread-safe: stateless behavior.
 */
final class TemperatureMeasurementHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0402;
    static final int ATTRIBUTE_MEASURED_VALUE = 0x0000;
    /** ZCL8 §4.4 invalid-measurement sentinel: wire 0x8000, sign-extended to −32768 by the codec. */
    static final long INVALID_MEASURED_VALUE = (short) 0x8000;
    /** ZCL8 §4.4 band floor: −27315 = −273.15 °C (absolute zero) — nothing legal sits below. */
    static final long MEASURED_VALUE_MIN = -27_315;
    /** The int16 ceiling (0x7FFF): a correctly-typed record can never exceed it. */
    static final long MEASURED_VALUE_MAX = 0x7FFF;

    private static final Logger log =
            LoggerFactory.getLogger(TemperatureMeasurementHandler.class);

    TemperatureMeasurementHandler(IEEEAddress device, Clock clock) {
        super(device, clock);
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_MEASURED_VALUE);
        if (value instanceof Long raw && raw != INVALID_MEASURED_VALUE) {
            if (raw < MEASURED_VALUE_MIN || raw > MEASURED_VALUE_MAX) {
                log.debug("temperature {} is out of the ZCL band (-27315..32767 "
                                + "in 0.01 °C units); skipped (F-9)", raw);
                return List.of();
            }
            return List.of(new NormalizedAttribute("temperature_c", raw / 100.0,
                    "°C", String.valueOf(raw), null));
        }
        return List.of();
    }
}
