/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

/**
 * EZSP frame encoder/decoder over ASH DATA payloads (UG100, Doc 08 §3.3).
 *
 * <p>Two wire dialects exist:
 * <ul>
 *   <li><strong>Legacy</strong> — the version command ({@code 0x0000}) is ALWAYS sent
 *       first and ALWAYS uses the legacy single-byte frame ID format
 *       {@code [seq, frameControl, frameId, params…]} (W8). The legacy dialect is
 *       format-invariant across protocol versions, so its methods are static.</li>
 *   <li><strong>Extended (v8+)</strong> — every other frame:
 *       {@code [seq, fcLow, fcHigh, frameIdLow, frameIdHigh, params…]} with a 16-bit
 *       little-endian frame ID. An instance is pinned to the NEGOTIATED protocol
 *       version, which keys the status-field width seam (D-M92-4): EZSP v13 uses
 *       1-byte EmberStatus; v14 migrated to 32-bit little-endian
 *       {@code sl_status_t}.</li>
 * </ul>
 *
 * <p>Frame-control bit layout (UG100, re-derived against bellows): response low byte —
 * bit 7 direction (1 = response), bit 0 overflow, bit 1 truncated, bit 2
 * callbackPending, bits 3–4 callbackType (00 none, 01 synchronous, 10 asynchronous),
 * bits 5–6 network index; high byte — bits 0–1 frame format version (1 for v8+).
 * Callback frames are identified by the callbackType bits; their sequence byte is
 * opaque (reference hosts do not correlate on it).
 *
 * <p>Thread-safe: immutable.
 *
 * @see EzspFrame
 * @see EzspCoordinatorProtocol
 */
final class EzspCodec {

    private static final int FC_LOW_RESPONSE_BIT = 0x80;
    private static final int FC_LOW_CALLBACK_TYPE_MASK = 0x18;
    private static final int FC_LOW_CALLBACK_PENDING = 0x04;
    private static final int FC_HIGH_FORMAT_VERSION_MASK = 0x03;
    private static final int FC_HIGH_FORMAT_VERSION_1 = 0x01;
    /** First protocol version whose status fields are 32-bit sl_status_t (D-M92-4). */
    private static final int FIRST_WIDE_STATUS_VERSION = 14;

    private final int protocolVersion;

    private EzspCodec(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    /**
     * Creates a codec pinned to the negotiated protocol version.
     *
     * @param protocolVersion the version from the negotiation response (AMD-96/E6:
     *                        the negotiation at stack init is the only version truth)
     * @return a codec for that version
     */
    static EzspCodec forVersion(int protocolVersion) {
        return new EzspCodec(protocolVersion);
    }

    /**
     * Decoded legacy version-command response.
     *
     * @param sequence the echoed sequence byte
     * @param protocolVersion the NCP's EZSP protocol version
     * @param stackType the stack type (2 = mesh/EmberZNet)
     * @param stackVersion the 16-bit encoded EmberZNet stack version
     */
    record LegacyVersionResponse(
            int sequence, int protocolVersion, int stackType, int stackVersion) {
    }

    /**
     * Decoded extended-format frame with its correlation sequence.
     *
     * @param sequence the echoed sequence byte (opaque for callbacks)
     * @param callbackPending {@code true} if the NCP holds queued callbacks
     * @param frame the decoded EZSP frame
     */
    record Decoded(int sequence, boolean callbackPending, EzspFrame frame) {
    }

    /**
     * Encodes the legacy version command — format-invariant by specification (W8),
     * hence static and independent of any negotiated version.
     *
     * @param sequence the sequence byte (0–255)
     * @param desiredProtocolVersion the version the host proposes
     * @return the EZSP frame bytes {@code [seq, 0x00, 0x00, desiredVersion]}
     */
    static byte[] encodeLegacyVersionCommand(int sequence, int desiredProtocolVersion) {
        return new byte[] {
            (byte) sequence, 0x00, 0x00, (byte) desiredProtocolVersion
        };
    }

    /**
     * Decodes the legacy version response
     * {@code [seq, frameControl, 0x00, protocolVersion, stackType, stackVersion(LE16)]}.
     *
     * <p>The frame-control byte is checked by its direction bit only — the
     * callbackPending bit ({@code 0x04}) may legally be set on any response.
     *
     * @param frame the EZSP frame bytes, never {@code null}
     * @return the decoded response
     * @throws EzspFormatException if the frame is not a legacy version response
     */
    static LegacyVersionResponse decodeLegacyVersionResponse(byte[] frame) {
        if (frame.length < 7) {
            throw new EzspFormatException(
                    "legacy version response too short: " + frame.length
                            + " bytes, expected 7");
        }
        int frameControl = frame[1] & 0xFF;
        if ((frameControl & FC_LOW_RESPONSE_BIT) == 0) {
            throw new EzspFormatException(String.format(
                    "legacy version response has command direction bit: fc=0x%02X",
                    frameControl));
        }
        if ((frame[2] & 0xFF) != 0x00) {
            throw new EzspFormatException(String.format(
                    "legacy frame ID is not version (0x00): 0x%02X", frame[2] & 0xFF));
        }
        return new LegacyVersionResponse(
                frame[0] & 0xFF,
                frame[3] & 0xFF,
                frame[4] & 0xFF,
                (frame[5] & 0xFF) | ((frame[6] & 0xFF) << 8));
    }

    /**
     * Encodes an extended-format (v8+) command frame.
     *
     * @param sequence the sequence byte (0–255)
     * @param frameId the 16-bit EZSP frame ID
     * @param parameters the command parameters, never {@code null}
     * @return the EZSP frame bytes
     */
    byte[] encodeCommand(int sequence, int frameId, byte[] parameters) {
        byte[] frame = new byte[5 + parameters.length];
        frame[0] = (byte) sequence;
        frame[1] = 0x00; // command direction, network index 0, sleep mode idle
        frame[2] = FC_HIGH_FORMAT_VERSION_1;
        frame[3] = (byte) (frameId & 0xFF);
        frame[4] = (byte) ((frameId >> 8) & 0xFF);
        System.arraycopy(parameters, 0, frame, 5, parameters.length);
        return frame;
    }

    /**
     * Decodes an extended-format (v8+) response or callback frame.
     *
     * @param frame the EZSP frame bytes, never {@code null}
     * @return the decoded frame with its sequence byte
     * @throws EzspFormatException if the frame is malformed, has command direction,
     *                             or carries an unsupported frame format version
     */
    Decoded decode(byte[] frame) {
        if (frame.length < 5) {
            throw new EzspFormatException(
                    "extended frame too short: " + frame.length + " bytes, minimum 5");
        }
        int fcLow = frame[1] & 0xFF;
        int fcHigh = frame[2] & 0xFF;
        if ((fcLow & FC_LOW_RESPONSE_BIT) == 0) {
            throw new EzspFormatException(String.format(
                    "frame has command direction, expected response: fcLow=0x%02X",
                    fcLow));
        }
        if ((fcHigh & FC_HIGH_FORMAT_VERSION_MASK) != FC_HIGH_FORMAT_VERSION_1) {
            throw new EzspFormatException(String.format(
                    "unsupported frame format version: fcHigh=0x%02X", fcHigh));
        }
        boolean callback = (fcLow & FC_LOW_CALLBACK_TYPE_MASK) != 0;
        int frameId = (frame[3] & 0xFF) | ((frame[4] & 0xFF) << 8);
        byte[] parameters = new byte[frame.length - 5];
        System.arraycopy(frame, 5, parameters, 0, parameters.length);
        return new Decoded(
                frame[0] & 0xFF,
                (fcLow & FC_LOW_CALLBACK_PENDING) != 0,
                new EzspFrame(frameId, callback, parameters));
    }

    /**
     * Returns the width in bytes of a status field under the pinned version
     * (D-M92-4): 1 byte (EmberStatus) below v14, 4 bytes ({@code sl_status_t}) from
     * v14. {@code EmberNetworkStatus} fields are NOT widened in v14 — read those as
     * a single byte regardless.
     *
     * @return 1 or 4
     */
    int statusWidthBytes() {
        return protocolVersion >= FIRST_WIDE_STATUS_VERSION ? 4 : 1;
    }

    /**
     * Reads a status field at {@code offset} using the version-keyed width:
     * 1-byte EmberStatus below v14, 32-bit little-endian {@code sl_status_t} from v14.
     * Success is {@code 0} in both dialects (EmberStatus SUCCESS / SL_STATUS_OK).
     *
     * @param parameters the response parameters, never {@code null}
     * @param offset the status field offset
     * @return the status value
     * @throws EzspFormatException if the parameters are shorter than the status field
     */
    int decodeStatus(byte[] parameters, int offset) {
        int width = statusWidthBytes();
        if (parameters.length < offset + width) {
            throw new EzspFormatException(String.format(
                    "response parameters too short for a %d-byte status at offset %d: "
                            + "%d bytes", width, offset, parameters.length));
        }
        if (width == 1) {
            return parameters[offset] & 0xFF;
        }
        return (parameters[offset] & 0xFF)
                | ((parameters[offset + 1] & 0xFF) << 8)
                | ((parameters[offset + 2] & 0xFF) << 16)
                | ((parameters[offset + 3] & 0xFF) << 24);
    }

    /** Returns the pinned protocol version. */
    int protocolVersion() {
        return protocolVersion;
    }
}
