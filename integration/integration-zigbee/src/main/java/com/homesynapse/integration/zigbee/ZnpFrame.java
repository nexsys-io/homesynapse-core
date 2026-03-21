/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * ZNP (Z-Stack Network Processor) transport frame.
 *
 * <p>ZNP uses the UNPI (Unified Network Processor Interface) framing format:
 * {@code SOF(0xFE) | Length | CMD0 | CMD1 | Data | FCS}. The subsystem and command
 * identifiers are extracted from CMD0 and CMD1. The {@link CommandType} is encoded
 * in CMD0 bits 7–5.
 *
 * <p>The {@code data} byte array is defensively copied in the compact constructor
 * and in the accessor to prevent external mutation of the frame's internal state.
 *
 * <p>Doc 08 §3.3, §4.2.
 *
 * <p>Thread-safe: immutable record with defensively copied byte array.
 *
 * @param subsystem the ZNP subsystem identifier extracted from CMD0 bits 4–0
 * @param commandId the command identifier within the subsystem (CMD1)
 * @param type the ZNP command type (SREQ, SRSP, or AREQ), never {@code null}
 * @param data the frame payload bytes; the returned array is a copy — modifications do not affect this record
 * @see ZigbeeFrame
 * @see CommandType
 * @see CoordinatorTransport
 */
public record ZnpFrame(int subsystem, int commandId, CommandType type, byte[] data) implements ZigbeeFrame {

    /**
     * Creates a ZNP frame with validation and defensive copy.
     *
     * @param subsystem the ZNP subsystem ID
     * @param commandId the command ID within the subsystem
     * @param type the command type, never {@code null}
     * @param data the payload bytes (defensively copied), never {@code null}
     */
    public ZnpFrame {
        Objects.requireNonNull(type, "type must not be null");
        data = data.clone();
    }

    /**
     * Returns a defensive copy of the frame payload bytes.
     *
     * <p>The returned array is a copy; modifications do not affect this record.
     *
     * @return a copy of the payload bytes, never {@code null}
     */
    @Override
    public byte[] data() {
        return data.clone();
    }
}
