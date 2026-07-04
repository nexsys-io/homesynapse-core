/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;
import java.util.Optional;

/**
 * A parsed {@code incomingMessageHandler} (0x0045) callback: the EZSP delivery
 * vehicle for every inbound APS message — ZDO responses and announces (profile
 * 0x0000) and ZCL frames (profile 0x0104) alike. Layout (UG100, bellows-mirrored):
 * {@code [type u8][EmberApsFrame 11][lastHopLqi u8][lastHopRssi s8][sender u16 LE]
 * [bindingIndex u8][addressIndex u8][length u8][message…]}.
 *
 * <p>Truncated frames parse to empty — a malformed callback is dropped at the
 * boundary, never an ingestion-loop exception.
 *
 * <p>Thread-safe: immutable record with a defensively copied message.
 *
 * @param profileId the APS profile id (0x0000 ZDO, 0x0104 Home Automation)
 * @param clusterId the APS cluster id
 * @param sourceEndpoint the sending endpoint
 * @param destinationEndpoint the destination endpoint
 * @param lastHopLqi the last-hop link quality (0–255)
 * @param lastHopRssi the last-hop RSSI in dBm
 * @param sender the sender's 16-bit network address
 * @param message the APS payload, never {@code null}
 */
record EzspIncomingMessage(
        int profileId,
        int clusterId,
        int sourceEndpoint,
        int destinationEndpoint,
        int lastHopLqi,
        int lastHopRssi,
        int sender,
        byte[] message) {

    /**
     * Creates a parsed incoming message with a defensive copy.
     *
     * @param profileId the APS profile id
     * @param clusterId the APS cluster id
     * @param sourceEndpoint the sending endpoint
     * @param destinationEndpoint the destination endpoint
     * @param lastHopLqi the last-hop LQI
     * @param lastHopRssi the last-hop RSSI
     * @param sender the sender's network address
     * @param message never {@code null}
     */
    EzspIncomingMessage {
        Objects.requireNonNull(message, "message must not be null");
        message = message.clone();
    }

    /** Returns a defensive copy of the APS payload. */
    @Override
    public byte[] message() {
        return message.clone();
    }

    /**
     * Parses the callback parameters.
     *
     * @param parameters the {@code incomingMessageHandler} frame parameters
     * @return the parsed message, or empty if truncated
     */
    static Optional<EzspIncomingMessage> parse(byte[] parameters) {
        if (parameters.length < 19) {
            return Optional.empty();
        }
        int profileId = (parameters[1] & 0xFF) | ((parameters[2] & 0xFF) << 8);
        int clusterId = (parameters[3] & 0xFF) | ((parameters[4] & 0xFF) << 8);
        int sourceEndpoint = parameters[5] & 0xFF;
        int destinationEndpoint = parameters[6] & 0xFF;
        int lastHopLqi = parameters[12] & 0xFF;
        int lastHopRssi = parameters[13];
        int sender = (parameters[14] & 0xFF) | ((parameters[15] & 0xFF) << 8);
        int length = parameters[18] & 0xFF;
        if (parameters.length < 19 + length) {
            return Optional.empty();
        }
        byte[] message = new byte[length];
        System.arraycopy(parameters, 19, message, 0, length);
        return Optional.of(new EzspIncomingMessage(profileId, clusterId,
                sourceEndpoint, destinationEndpoint, lastHopLqi, lastHopRssi,
                sender, message));
    }
}
