/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-model device behavior overrides for the Zigbee adapter.
 *
 * <p>Device profiles capture manufacturer- and model-specific behavioral adjustments
 * that override the adapter's default cluster handler behavior. Profiles are loaded
 * from bundled {@code zigbee-profiles.json} and optional user override files. User
 * profiles take precedence over bundled profiles.
 *
 * <p>The {@link DeviceCategory} determines the overall handling strategy: standard ZCL,
 * minor quirks (reporting/range overrides), mixed standard/custom (manufacturer codec
 * for some clusters), or fully custom (proprietary protocol like Tuya 0xEF00).
 *
 * <p>Doc 08 §3.6, §4.3.
 *
 * <p>Thread-safe: immutable record with defensively copied collections.
 *
 * @param profileId unique profile identifier (e.g., {@code "ikea_tradfri_bulb"}), never {@code null}
 * @param matches the set of manufacturer/model pairs this profile applies to, never {@code null}, never empty
 * @param category the device handling category, never {@code null}
 * @param clusterOverrides per-cluster behavioral adjustments keyed by cluster ID; {@code null} if no overrides
 * @param reportingOverrides per-cluster reporting configuration overrides keyed by cluster ID; {@code null} if no overrides
 * @param manufacturerCodec the codec identifier for manufacturer-specific protocol handling; {@code null} for {@link DeviceCategory#STANDARD_ZCL} devices. Values: {@code "tuya_ef00"}, {@code "xiaomi_ff01"}, {@code "xiaomi_fcc0"}
 * @param interviewSkips set of interview steps to skip (e.g., {@code "configure_reporting"} for Xiaomi); {@code null} if all steps execute
 * @param tuyaDatapoints Tuya DP-to-capability mappings; {@code null} unless the device uses cluster 0xEF00
 * @param initializationWrites ZCL attribute writes executed after device adoption; {@code null} if no post-adoption writes needed
 * @see DeviceCategory
 * @see DeviceProfileRegistry
 * @see ManufacturerModelPair
 * @see ClusterOverride
 * @see ReportingOverride
 * @see TuyaDatapointMapping
 * @see InitializationWrite
 */
public record DeviceProfile(
        String profileId,
        Set<ManufacturerModelPair> matches,
        DeviceCategory category,
        Map<Integer, ClusterOverride> clusterOverrides,
        Map<Integer, ReportingOverride> reportingOverrides,
        String manufacturerCodec,
        Set<String> interviewSkips,
        List<TuyaDatapointMapping> tuyaDatapoints,
        List<InitializationWrite> initializationWrites) {

    /**
     * Creates a device profile with validation and defensive copies.
     *
     * @param profileId never {@code null}
     * @param matches never {@code null}, must not be empty
     * @param category never {@code null}
     * @param clusterOverrides {@code null} if no overrides
     * @param reportingOverrides {@code null} if no overrides
     * @param manufacturerCodec {@code null} for standard ZCL devices
     * @param interviewSkips {@code null} if all interview steps execute
     * @param tuyaDatapoints {@code null} unless device uses cluster 0xEF00
     * @param initializationWrites {@code null} if no post-adoption writes
     */
    public DeviceProfile {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(matches, "matches must not be null");
        matches = Set.copyOf(matches);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("matches must not be empty");
        }
        Objects.requireNonNull(category, "category must not be null");
        clusterOverrides = clusterOverrides != null ? Map.copyOf(clusterOverrides) : null;
        reportingOverrides = reportingOverrides != null ? Map.copyOf(reportingOverrides) : null;
        interviewSkips = interviewSkips != null ? Set.copyOf(interviewSkips) : null;
        tuyaDatapoints = tuyaDatapoints != null ? List.copyOf(tuyaDatapoints) : null;
        initializationWrites = initializationWrites != null ? List.copyOf(initializationWrites) : null;
    }
}
