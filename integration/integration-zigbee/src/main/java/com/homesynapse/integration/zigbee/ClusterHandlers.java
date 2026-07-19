/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.util.Map;

/**
 * Factory for the per-device cluster handler table (Doc 08 §3.5 — the M9.3
 * report-path set: OnOff, LevelControl, ColorControl-CT, OccupancySensing,
 * PowerConfiguration, IAS Zone tolerate-path; + the M9.7-W2 Wave-2 pair:
 * TemperatureMeasurement, RelativeHumidity). Clusters without a handler —
 * including the measured manufacturer-specific deltas 0xFC01/0xFC04/0xFC57 —
 * are skipped gracefully by the ingestion dispatch, never errors.
 *
 * <p>Thread-safe: the returned map and every handler are immutable.
 */
final class ClusterHandlers {

    private ClusterHandlers() {
    }

    /**
     * Builds the handler table for one device.
     *
     * @param device the device the handlers serve, never {@code null}
     * @param clock the time source, never {@code null}
     * @param zoneType the IAS zone type for the 0x0500 handler, never
     *        {@code null} (defaults to {@link ZoneType#MOTION} upstream when the
     *        device has not enrolled)
     * @return the cluster-id → handler table
     */
    static Map<Integer, ZigbeeClusterHandler> forDevice(IEEEAddress device,
            Clock clock, ZoneType zoneType) {
        return Map.of(
                OnOffHandler.CLUSTER_ID, new OnOffHandler(device, clock),
                LevelControlHandler.CLUSTER_ID,
                new LevelControlHandler(device, clock),
                ColorControlHandler.CLUSTER_ID,
                new ColorControlHandler(device, clock),
                OccupancySensingHandler.CLUSTER_ID,
                new OccupancySensingHandler(device, clock),
                PowerConfigurationHandler.CLUSTER_ID,
                new PowerConfigurationHandler(device, clock),
                TemperatureMeasurementHandler.CLUSTER_ID,
                new TemperatureMeasurementHandler(device, clock),
                RelativeHumidityHandler.CLUSTER_ID,
                new RelativeHumidityHandler(device, clock),
                IasZoneHandler.CLUSTER_ID,
                new IasZoneHandler(device, clock, zoneType));
    }
}
