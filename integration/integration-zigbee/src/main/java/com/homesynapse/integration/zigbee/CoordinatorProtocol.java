/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Optional;

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
     * Enables joins that use the preconfigured well-known Trust Center link key for
     * the upcoming pairing window (M9.4-TCJ §A.1): sets the coordinator's join
     * policy and installs the well-known key as a wildcard-partnered TRANSIENT
     * credential, so a joining Zigbee 3.0 device can complete the initial APS key
     * exchange and receive the network key.
     *
     * <p>Call BEFORE {@link #permitJoin(int)}: the MAC association window alone
     * admits no Zigbee 3.0 device — without this enablement the key exchange never
     * completes, the device never announces, and adoption never fires. The
     * transient credential is bounded to the coordinator stack's transient-key
     * lifetime; it never persists as a standing credential.
     *
     * <p>A coordinator that rejects the enablement surfaces the failure to the
     * caller — the pairing window must NOT be opened over a half-enabled join
     * surface (never-false-ALIVE).
     */
    void enablePreconfiguredKeyJoins();

    /**
     * Resolves a 16-bit network address to the device's IEEE address from the
     * coordinator's own address table (F-R4-1 — interview-on-rejoin, R-10
     * Row 10 (a)): the admission hop for a device that rejoined on its own
     * authority and never announced, so its frames reach the adapter as an
     * unknown sender. Coordinator-neutral (INV-CE-04): the EZSP binding rides
     * {@code lookupEui64ByNodeId}; a ZNP coordinator binds its own table read.
     *
     * <p>Empty when the coordinator holds no entry for the address (a
     * non-success status) — the caller treats a miss as an unresolved
     * candidate, never a synthesized identity. A coordinator that does not
     * answer surfaces as the unchecked command timeout (the
     * {@link #permitJoin(int)} precedent: no new checked throw on this surface).
     *
     * @param networkAddress the 16-bit network address (0x0000–0xFFFF)
     * @return the device's IEEE address, or empty when the coordinator holds no
     *         entry for the address
     */
    Optional<IEEEAddress> lookupIeee(int networkAddress);

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
