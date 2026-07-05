/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * IAS Zone cluster (0x0500) handler — the §3.12 TOLERATE path: zone status
 * observations ingest regardless of enrollment state (enrollment is a recorded
 * device fact, never an ingestion gate — the measured SNZB-03P is dual-path
 * with the Occupancy cluster active and IAS enrolled-but-silent).
 *
 * <p>Bit 0 of {@code zoneStatus} (Alarm1) carries the primary alarm; the zone
 * type selects the capability attribute ({@code motion → detected},
 * {@code contact → open}, others → the generic {@code active}).
 *
 * <p>Thread-safe: stateless behavior over the immutable zone-type binding.
 */
final class IasZoneHandler extends ZigbeeClusterHandler {

    static final int CLUSTER_ID = 0x0500;
    /** IAS attribute 0x0001: ZoneType (enum16) — the F-7a wire-learn source. */
    static final int ATTRIBUTE_ZONE_TYPE = 0x0001;
    static final int ATTRIBUTE_ZONE_STATUS = 0x0002;
    /** zoneStatus bit 0: Alarm1, the primary alarm. */
    static final int ZONE_STATUS_ALARM1 = 0x0001;
    /** ZCL8 §8.2.2.3: ZoneEnrollRequest, device → CIE (cluster-specific 0x01). */
    static final int COMMAND_ZONE_ENROLL_REQUEST = 0x01;
    /** ZCL8 §8.2.2.3: ZoneEnrollResponse, CIE → device (cluster-specific 0x00). */
    static final int COMMAND_ZONE_ENROLL_RESPONSE = 0x00;
    /** ZCL8 §8.2.2.3: enroll response code 0x00 = Success. */
    static final int ENROLL_RESPONSE_SUCCESS = 0x00;
    /** ZCL8 §8.2.2.3: the zone id the CIE assigns (single-zone table — zone 0). */
    static final int ENROLL_ZONE_ID = 0x00;

    private final ZoneType zoneType;

    IasZoneHandler(IEEEAddress device, Clock clock, ZoneType zoneType) {
        super(device, clock);
        this.zoneType = Objects.requireNonNull(zoneType, "zoneType");
    }

    @Override
    List<NormalizedAttribute> normalize(int endpoint, int clusterId,
            Map<Integer, Object> attributes) {
        Object value = attributes.get(ATTRIBUTE_ZONE_STATUS);
        if (value instanceof Long zoneStatus) {
            return normalizeZoneStatus(endpoint, zoneStatus.intValue());
        }
        return List.of();
    }

    /**
     * Normalizes a zone status bitmap — the shared path for zoneStatus attribute
     * reports and {@code ZoneStatusChangeNotification} commands (both ingest;
     * tolerate-not-require).
     *
     * @param endpoint the source endpoint
     * @param zoneStatus the Bitmap16 zone status
     * @return the alarm observation
     */
    List<NormalizedAttribute> normalizeZoneStatus(int endpoint, int zoneStatus) {
        boolean alarm = (zoneStatus & ZONE_STATUS_ALARM1) != 0;
        return List.of(new NormalizedAttribute(attributeKey(), alarm, null,
                String.valueOf(zoneStatus), null));
    }

    private String attributeKey() {
        return switch (zoneType) {
            case MOTION -> "detected";
            case CONTACT -> "open";
            case WATER_LEAK, SMOKE, VIBRATION -> "active";
        };
    }
}
