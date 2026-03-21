/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.List;

/**
 * Abstraction over Zigbee protocol operations above the transport layer.
 *
 * <p>The protocol layer runs on virtual threads, using {@code CompletableFuture<T>} for
 * synchronous request-response correlation with timeout. The coordinator abstraction makes
 * the choice between ZNP (CC2652) and EZSP (EFR32) invisible to the rest of the adapter
 * (Doc 08 §5 contract: "Coordinator type is an internal detail" — INV-CE-04).
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Thread-safe: methods may be called from the adapter's virtual threads and the
 * command dispatch thread.
 *
 * @see CoordinatorTransport
 * @see ZclFrame
 * @see NetworkParameters
 * @see InterviewResult
 * @see NeighborTableEntry
 */
public interface CoordinatorProtocol {

    /**
     * Forms a new Zigbee network with the given parameters.
     *
     * <p>Configures the coordinator's channel, PAN ID, and extended PAN ID, generates
     * or loads the network key, and starts the network. The coordinator begins accepting
     * device joins only after an explicit {@link #permitJoin(int)} call.
     *
     * @param params the network parameters, never {@code null}
     */
    void formNetwork(NetworkParameters params);

    /**
     * Resumes an existing Zigbee network from stored parameters.
     *
     * <p>Restores the coordinator's network state without reforming. Previously joined
     * devices will reconnect automatically on their next communication attempt.
     */
    void resumeNetwork();

    /**
     * Enables device pairing for the specified duration.
     *
     * @param durationSeconds the pairing window duration in seconds (max 254 per Zigbee spec);
     *                        0 closes the pairing window immediately
     */
    void permitJoin(int durationSeconds);

    /**
     * Sends a ZCL frame to the target device.
     *
     * @param frame the ZCL frame to send, never {@code null}
     * @param target the target device's IEEE address, never {@code null}
     */
    void sendZclFrame(ZclFrame frame, IEEEAddress target);

    /**
     * Executes the full interview pipeline for a device.
     *
     * <p>The interview sequence: Node Descriptor → Active Endpoints → Simple Descriptors
     * → Basic cluster read (manufacturer name, model identifier, power source).
     *
     * @param device the device's IEEE address, never {@code null}
     * @return the interview result containing collected metadata, never {@code null}
     */
    InterviewResult interview(IEEEAddress device);

    /**
     * Performs BFS mesh topology discovery via ZDO Mgmt_Lqi_req.
     *
     * <p>Traverses the mesh network breadth-first starting from the coordinator,
     * collecting neighbor table entries from each router to build a complete topology map.
     *
     * @return the neighbor table entries from the topology scan, never {@code null}
     */
    List<NeighborTableEntry> topologyScan();

    /**
     * Coordinator liveness check.
     *
     * <p>Sends a lightweight ping to the coordinator: SYS_PING for ZNP, nop() for EZSP.
     * Used by the health reporter to confirm coordinator connectivity.
     *
     * @return {@code true} if the coordinator responded, {@code false} if the ping timed out
     */
    boolean ping();
}
