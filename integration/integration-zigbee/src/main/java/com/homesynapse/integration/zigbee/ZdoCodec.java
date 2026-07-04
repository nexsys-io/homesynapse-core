/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure ZDP (ZDO) frame codec for the interview pipeline: request encoding and
 * response parsing for Node_Desc (0x0002/0x8002), Active_EP (0x0005/0x8005),
 * Simple_Desc (0x0004/0x8004), and Device_annce (0x0013) — Doc 08 §3.4 steps 2–4.
 *
 * <p>ZDP layout: every message starts with a ZDO transaction sequence number;
 * multi-byte fields are little-endian. Malformed or failed responses parse to
 * {@code Optional.empty()} — a corrupt frame is a step failure, never an
 * exception (the interview retry policy owns the consequence).
 *
 * <p>Thread-safe: stateless utility.
 */
final class ZdoCodec {

    /** ZDO cluster ids (ZDP): requests. */
    static final int CLUSTER_NODE_DESC_REQ = 0x0002;
    static final int CLUSTER_SIMPLE_DESC_REQ = 0x0004;
    static final int CLUSTER_ACTIVE_EP_REQ = 0x0005;
    /** ZDO cluster ids (ZDP): responses are request | 0x8000. */
    static final int CLUSTER_NODE_DESC_RSP = 0x8002;
    static final int CLUSTER_SIMPLE_DESC_RSP = 0x8004;
    static final int CLUSTER_ACTIVE_EP_RSP = 0x8005;
    /** Device_annce (unsolicited, ZDP 0x0013). */
    static final int CLUSTER_DEVICE_ANNOUNCE = 0x0013;

    private ZdoCodec() {
    }

    /**
     * A parsed ZDP Device_annce: the join/rejoin signal that feeds the pending
     * interview queue and clears the ingestion dedup scope.
     *
     * @param networkAddress the announced 16-bit network address
     * @param ieeeAddress the announcing device's IEEE address
     * @param macCapabilityFlags the MAC capability flags (bit 1: rx-on-when-idle)
     */
    record DeviceAnnounce(int networkAddress, IEEEAddress ieeeAddress,
            int macCapabilityFlags) {
    }

    /**
     * Encodes a nwk-addressed ZDP request body ({@code [tsn][nwk LE]}) — the
     * shared shape of Node_Desc_req and Active_EP_req.
     *
     * @param tsn the ZDO transaction sequence number (0–255)
     * @param networkAddress the target's 16-bit network address
     * @return the ZDP message
     */
    static byte[] encodeAddressRequest(int tsn, int networkAddress) {
        return new byte[] {
                (byte) tsn,
                (byte) (networkAddress & 0xFF),
                (byte) ((networkAddress >> 8) & 0xFF)
        };
    }

    /**
     * Encodes a Simple_Desc_req body ({@code [tsn][nwk LE][endpoint]}).
     *
     * @param tsn the ZDO transaction sequence number (0–255)
     * @param networkAddress the target's 16-bit network address
     * @param endpoint the endpoint to describe (1–240)
     * @return the ZDP message
     */
    static byte[] encodeSimpleDescriptorRequest(int tsn, int networkAddress,
            int endpoint) {
        return new byte[] {
                (byte) tsn,
                (byte) (networkAddress & 0xFF),
                (byte) ((networkAddress >> 8) & 0xFF),
                (byte) endpoint
        };
    }

    /**
     * Parses a Node_Desc_rsp into a {@link NodeDescriptor}.
     *
     * @param message the ZDP message ({@code [tsn][status][nwk LE][descriptor…]})
     * @return the descriptor, or empty on failure status or truncation
     */
    static Optional<NodeDescriptor> parseNodeDescriptorResponse(byte[] message) {
        if (message.length < 10 || message[1] != 0) {
            return Optional.empty();
        }
        int logicalType = message[4] & 0x07;
        int macCapabilityFlags = message[6] & 0xFF;
        int manufacturerCode = (message[7] & 0xFF) | ((message[8] & 0xFF) << 8);
        int maxBufferSize = message[9] & 0xFF;
        if (logicalType > 2 || maxBufferSize <= 0) {
            return Optional.empty();
        }
        return Optional.of(new NodeDescriptor(logicalType, manufacturerCode,
                maxBufferSize, macCapabilityFlags));
    }

    /**
     * Parses an Active_EP_rsp into the endpoint list.
     *
     * @param message the ZDP message ({@code [tsn][status][nwk LE][count][eps…]})
     * @return the endpoint ids in wire order, or empty on failure/truncation
     */
    static Optional<List<Integer>> parseActiveEndpointsResponse(byte[] message) {
        if (message.length < 5 || message[1] != 0) {
            return Optional.empty();
        }
        int count = message[4] & 0xFF;
        if (message.length < 5 + count) {
            return Optional.empty();
        }
        List<Integer> endpoints = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            endpoints.add(message[5 + i] & 0xFF);
        }
        return Optional.of(endpoints);
    }

    /**
     * Parses a Simple_Desc_rsp into an {@link EndpointDescriptor}.
     *
     * @param message the ZDP message
     *        ({@code [tsn][status][nwk LE][length][ep][profile LE][deviceType LE]
     *        [version][inCount][in…][outCount][out…]})
     * @return the descriptor, or empty on failure/truncation
     */
    static Optional<EndpointDescriptor> parseSimpleDescriptorResponse(byte[] message) {
        if (message.length < 12 || message[1] != 0) {
            return Optional.empty();
        }
        int endpoint = message[5] & 0xFF;
        int profileId = (message[6] & 0xFF) | ((message[7] & 0xFF) << 8);
        int deviceTypeId = (message[8] & 0xFF) | ((message[9] & 0xFF) << 8);
        int inCount = message[11] & 0xFF;
        int inEnd = 12 + inCount * 2;
        if (message.length < inEnd + 1) {
            return Optional.empty();
        }
        List<Integer> inputClusters = readClusterList(message, 12, inCount);
        int outCount = message[inEnd] & 0xFF;
        if (message.length < inEnd + 1 + outCount * 2) {
            return Optional.empty();
        }
        List<Integer> outputClusters = readClusterList(message, inEnd + 1, outCount);
        if (endpoint < 1 || endpoint > 240) {
            return Optional.empty();
        }
        return Optional.of(new EndpointDescriptor(endpoint, profileId, deviceTypeId,
                inputClusters, outputClusters));
    }

    /**
     * Parses a Device_annce ({@code [tsn][nwk LE][ieee LE 8][capability]}).
     *
     * @param message the ZDP message
     * @return the announce, or empty on truncation
     */
    static Optional<DeviceAnnounce> parseDeviceAnnounce(byte[] message) {
        if (message.length < 12) {
            return Optional.empty();
        }
        int networkAddress = (message[1] & 0xFF) | ((message[2] & 0xFF) << 8);
        long ieee = 0;
        for (int i = 0; i < 8; i++) {
            ieee |= (long) (message[3 + i] & 0xFF) << (8 * i);
        }
        return Optional.of(new DeviceAnnounce(networkAddress,
                new IEEEAddress(ieee), message[11] & 0xFF));
    }

    private static List<Integer> readClusterList(byte[] message, int offset,
            int count) {
        List<Integer> clusters = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int base = offset + i * 2;
            clusters.add((message[base] & 0xFF) | ((message[base + 1] & 0xFF) << 8));
        }
        return clusters;
    }
}
