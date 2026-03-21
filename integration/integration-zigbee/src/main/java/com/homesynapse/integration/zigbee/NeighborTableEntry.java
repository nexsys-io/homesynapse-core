/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * Entry from a Zigbee mesh topology scan via ZDO Mgmt_Lqi_req.
 *
 * <p>Each entry represents a neighbor relationship in the Zigbee mesh network,
 * including the link quality indicator (LQI) and routing depth. The coordinator
 * protocol collects these entries via BFS traversal of the mesh to build a
 * complete topology map for observability and route health monitoring.
 *
 * <p>Doc 08 §3.11 topology scan.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param ieeeAddress the neighbor's permanent IEEE EUI-64 address, never {@code null}
 * @param networkAddress the neighbor's current 16-bit network address (0x0000–0xFFFF)
 * @param deviceType the logical device type: 0 = coordinator, 1 = router, 2 = end device
 * @param lqi the link quality indicator (0–255, higher is better)
 * @param depth the routing depth from the coordinator (0 for the coordinator itself)
 * @param parentIeee the parent node's IEEE address; {@code null} for the coordinator node which has no parent
 * @see CoordinatorProtocol#topologyScan()
 */
public record NeighborTableEntry(
        IEEEAddress ieeeAddress,
        int networkAddress,
        int deviceType,
        int lqi,
        int depth,
        IEEEAddress parentIeee) {

    /**
     * Creates a neighbor table entry with validation.
     *
     * @param ieeeAddress the neighbor's IEEE address, never {@code null}
     * @param networkAddress must be 0x0000–0xFFFF
     * @param deviceType must be 0 (coordinator), 1 (router), or 2 (end device)
     * @param lqi must be 0–255
     * @param depth must be non-negative
     * @param parentIeee the parent's IEEE address; {@code null} for the coordinator
     */
    public NeighborTableEntry {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress must not be null");
        if (networkAddress < 0 || networkAddress > 0xFFFF) {
            throw new IllegalArgumentException(
                    "networkAddress must be 0x0000–0xFFFF, got " + networkAddress);
        }
        if (deviceType < 0 || deviceType > 2) {
            throw new IllegalArgumentException(
                    "deviceType must be 0 (coordinator), 1 (router), or 2 (end device), got " + deviceType);
        }
        if (lqi < 0 || lqi > 255) {
            throw new IllegalArgumentException(
                    "lqi must be 0–255, got " + lqi);
        }
        if (depth < 0) {
            throw new IllegalArgumentException(
                    "depth must be non-negative, got " + depth);
        }
    }
}
