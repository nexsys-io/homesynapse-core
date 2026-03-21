/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Map;

/**
 * Per-cluster behavioral adjustments within a device profile.
 *
 * <p>Cluster overrides take precedence over standard cluster handler behavior. They
 * can modify how specific attributes are interpreted or disable the default handler
 * entirely (e.g., when a manufacturer codec handles the cluster instead).
 *
 * <p>Doc 08 §3.6 {@link DeviceProfile#clusterOverrides()}.
 *
 * <p>Thread-safe: immutable record with defensively copied map.
 *
 * @param clusterId the ZCL cluster ID to override, must be non-negative
 * @param attributeOverrides per-attribute override descriptions keyed by attribute ID, never {@code null}
 * @param disableDefaultHandler if {@code true}, the standard cluster handler is bypassed for this cluster
 * @see DeviceProfile
 * @see ClusterHandler
 */
public record ClusterOverride(int clusterId, Map<Integer, String> attributeOverrides, boolean disableDefaultHandler) {

    /**
     * Creates a cluster override with validation and defensive copy.
     *
     * @param clusterId must be non-negative
     * @param attributeOverrides the attribute overrides, never {@code null}
     * @param disableDefaultHandler whether to bypass the default cluster handler
     */
    public ClusterOverride {
        if (clusterId < 0) {
            throw new IllegalArgumentException(
                    "clusterId must be non-negative, got " + clusterId);
        }
        attributeOverrides = Map.copyOf(attributeOverrides);
    }
}
