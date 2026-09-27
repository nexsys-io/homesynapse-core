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
 * IlluminanceMeasurement cluster (0x0400) handler:
 * {@code measuredValue} (0x0000, Uint16 on the ZCL8 §4.2 log scale,
 * {@code 10,000 × log10(lux) + 1}) → the canonical {@code illuminance_lux}
 * float ({@code 10^((raw − 1) / 10,000)} rounded to one decimal — Doc 08
 * §3.5's log conversion), raw retained with its dialect ({@code log10x10000+1})
 * so a reader re-derives lux from the raw.
 *
 * <p>The ZCL invalid sentinel {@code 0xFFFF} and values no uint16 carries
 * (above it — reachable only through a mis-typed wire record) never observe
 * (honest absence — the F-9 discipline; a fabricated reading could feed
 * automations real-world lies). {@code 0x0000} is the device's own statement
 * that the illuminance is too low to be measured: it observes as {@code 0.0}
 * lux — emitting nothing would leave the last daylight value standing after
 * dusk.
 *
 * <p>Thread-safe: stateless behavior.
 */
final class IlluminanceMeasurementHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0400;
    static final int ATTRIBUTE_MEASURED_VALUE = 0x0000;
    /** ZCL8 §4.2 invalid-measurement sentinel (uint16 — arrives unsigned). */
    static final long INVALID_MEASURED_VALUE = 0xFFFF;
    /** ZCL8 §4.2.2.2.1: 0x0000 — the illuminance is too low to be measured. */
    static final long TOO_LOW_TO_MEASURE = 0;
    /** The uint16 ceiling: a correctly-typed record can never exceed it. */
    static final long UINT16_MAX = 0xFFFF;
    /** The raw's dialect on the wire: MeasuredValue = 10,000 × log10(lux) + 1. */
    static final String RAW_PROTOCOL_UNIT = "log10x10000+1";

    private static final Logger log =
            LoggerFactory.getLogger(IlluminanceMeasurementHandler.class);

    IlluminanceMeasurementHandler(IEEEAddress device, Clock clock) {
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
            if (raw > UINT16_MAX) {
                log.debug("illuminance 0x{} is above the uint16 ceiling (a "
                                + "mis-typed record); skipped (F-9)",
                        Long.toHexString(raw));
                return List.of();
            }
            // One decimal for the reader (the dashboard prints the number as
            // it arrives); the raw is retained, so nothing is lost.
            double lux = raw == TOO_LOW_TO_MEASURE ? 0.0
                    : Math.round(Math.pow(10.0, (raw - 1) / 10_000.0) * 10.0)
                            / 10.0;
            return List.of(new NormalizedAttribute("illuminance_lux", lux, "lux",
                    String.valueOf(raw), RAW_PROTOCOL_UNIT));
        }
        return List.of();
    }
}
