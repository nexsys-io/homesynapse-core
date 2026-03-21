/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * ZNP (Z-Stack Network Processor) frame command types identified by CMD0 bits 7–5.
 *
 * <p>ZNP uses three command types to distinguish synchronous request-response pairs
 * from asynchronous notifications. The command type is encoded in the three most
 * significant bits of the CMD0 byte in the UNPI (Unified Network Processor Interface)
 * framing format.
 *
 * <p>Doc 08 §3.3 ZNP transport. Used in {@link ZnpFrame}.
 *
 * <p>EZSP uses a different framing model (frame ID + callback flag) and does not
 * use these command types.
 *
 * <p>Thread-safe: enum.
 *
 * @see ZnpFrame
 */
public enum CommandType {

    /**
     * Synchronous request — CMD0 bits 7–5 = 0x20.
     *
     * <p>Sent by the host to the ZNP coordinator. A matching {@link #SRSP} is
     * expected within the protocol timeout.
     */
    SREQ(0x20),

    /**
     * Synchronous response — CMD0 bits 7–5 = 0x60.
     *
     * <p>Sent by the ZNP coordinator in response to a {@link #SREQ}. Matched
     * by subsystem ID and command ID.
     */
    SRSP(0x60),

    /**
     * Asynchronous notification — CMD0 bits 7–5 = 0x40.
     *
     * <p>Unsolicited callback from the ZNP coordinator (e.g., device join,
     * attribute report, leave indication). Not correlated with a prior request.
     */
    AREQ(0x40);

    private final int protocolId;

    CommandType(int protocolId) {
        this.protocolId = protocolId;
    }

    /**
     * Returns the CMD0 bits 7–5 value for this command type.
     *
     * @return the protocol identifier, one of 0x20, 0x40, or 0x60
     */
    public int protocolId() {
        return protocolId;
    }
}
