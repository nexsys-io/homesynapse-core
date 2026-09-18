/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory for the per-device cluster handler table (Doc 08 §3.5 — the M9.3
 * report-path set: OnOff, LevelControl, ColorControl-CT, OccupancySensing,
 * PowerConfiguration, IAS Zone tolerate-path; + the M9.7-W2 Wave-2 pair:
 * TemperatureMeasurement, RelativeHumidity; + the ENERGY-READ metering pair:
 * ElectricalMeasurement, Metering — each attached ONLY when the device's
 * formatting for that cluster is known). Clusters without a handler —
 * including the measured manufacturer-specific deltas 0xFC01/0xFC04/0xFC57,
 * and a metering cluster whose formatting was never read — are skipped
 * gracefully by the ingestion dispatch, never errors.
 *
 * <p>Thread-safe: the returned map and every handler are immutable.
 */
final class ClusterHandlers {

    private ClusterHandlers() {
    }

    /**
     * Builds the handler table for one device: the eight report-path handlers
     * always, and a metering handler per cluster whose formatting the device
     * declared — eight, nine or ten entries. A metering handler is never built
     * on a guessed scale: no formatting, no handler.
     *
     * @param device the device the handlers serve, never {@code null}
     * @param clock the time source, never {@code null}
     * @param zoneType the IAS zone type for the 0x0500 handler, never
     *        {@code null} (defaults to {@link ZoneType#MOTION} upstream when the
     *        device has not enrolled)
     * @param formatting the metering formatting the device declared;
     *        {@code null} when none was learned
     * @return the cluster-id → handler table
     */
    static Map<Integer, ZigbeeClusterHandler> forDevice(IEEEAddress device,
            Clock clock, ZoneType zoneType, MeteringFormatting formatting) {
        Map<Integer, ZigbeeClusterHandler> handlers = new LinkedHashMap<>();
        handlers.put(OnOffHandler.CLUSTER_ID, new OnOffHandler(device, clock));
        handlers.put(LevelControlHandler.CLUSTER_ID,
                new LevelControlHandler(device, clock));
        handlers.put(ColorControlHandler.CLUSTER_ID,
                new ColorControlHandler(device, clock));
        handlers.put(OccupancySensingHandler.CLUSTER_ID,
                new OccupancySensingHandler(device, clock));
        handlers.put(PowerConfigurationHandler.CLUSTER_ID,
                new PowerConfigurationHandler(device, clock));
        handlers.put(TemperatureMeasurementHandler.CLUSTER_ID,
                new TemperatureMeasurementHandler(device, clock));
        handlers.put(RelativeHumidityHandler.CLUSTER_ID,
                new RelativeHumidityHandler(device, clock));
        handlers.put(IasZoneHandler.CLUSTER_ID,
                new IasZoneHandler(device, clock, zoneType));
        if (formatting != null && formatting.hasElectrical()) {
            handlers.put(ElectricalMeasurementHandler.CLUSTER_ID,
                    new ElectricalMeasurementHandler(device, clock, formatting));
        }
        if (formatting != null && formatting.hasMetering()) {
            handlers.put(MeteringHandler.CLUSTER_ID,
                    new MeteringHandler(device, clock, formatting));
        }
        return Map.copyOf(handlers);
    }
}
