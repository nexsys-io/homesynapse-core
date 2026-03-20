package com.homesynapse.integration.zigbee;

/**
 * EZSP (EmberZNet Serial Protocol) transport frame.
 *
 * <p>EZSP frames represent decoded application-layer messages from Silicon Labs EFR32
 * coordinators. The ASH (Asynchronous Serial Host) framing layer handles byte stuffing,
 * data derandomization, CRC-CCITT verification, and acknowledgment at the transport level;
 * this record represents the decoded content above that layer.
 *
 * <p>EZSP version negotiation (command 0x0000) uses a legacy single-byte frame ID format.
 * All other commands use the standard frame ID format.
 *
 * <p>The {@code parameters} byte array is defensively copied in the compact constructor
 * and in the accessor to prevent external mutation of the frame's internal state.
 *
 * <p>Doc 08 §3.3, §4.2.
 *
 * <p>Thread-safe: immutable record with defensively copied byte array.
 *
 * @param frameId the EZSP command or callback identifier
 * @param isCallback {@code true} for unsolicited callbacks from the NCP (Network Co-Processor), {@code false} for responses to host commands
 * @param parameters the frame parameter bytes; the returned array is a copy — modifications do not affect this record
 * @see ZigbeeFrame
 * @see CoordinatorTransport
 */
public record EzspFrame(int frameId, boolean isCallback, byte[] parameters) implements ZigbeeFrame {

    /**
     * Creates an EZSP frame with defensive copy.
     *
     * @param frameId the EZSP frame identifier
     * @param isCallback whether this is an unsolicited callback
     * @param parameters the parameter bytes (defensively copied), never {@code null}
     */
    public EzspFrame {
        parameters = parameters.clone();
    }

    /**
     * Returns a defensive copy of the frame parameter bytes.
     *
     * <p>The returned array is a copy; modifications do not affect this record.
     *
     * @return a copy of the parameter bytes, never {@code null}
     */
    @Override
    public byte[] parameters() {
        return parameters.clone();
    }
}
