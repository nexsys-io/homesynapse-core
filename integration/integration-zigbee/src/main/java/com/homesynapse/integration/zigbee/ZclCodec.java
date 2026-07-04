/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pure ZCL frame codec: header parsing, attribute-record decoding (Report
 * Attributes 0x0A and Read Attributes Response 0x01), and Read Attributes (0x00)
 * encoding — the shared surface beneath the interview Basic-cluster read and the
 * ingestion path (Doc 08 §3.4 step 5, §3.5).
 *
 * <p>Decoding is total: a truncated header or record parses to empty/partial
 * results with a DEBUG log, never an exception — a malformed frame from a real
 * device must never take down the ingestion loop. An UNKNOWN attribute data type
 * stops record parsing at that record (its length is unknowable), keeping the
 * records already decoded.
 *
 * <p>Thread-safe: stateless utility.
 */
final class ZclCodec {

    /** ZCL global command ids. */
    static final int COMMAND_READ_ATTRIBUTES = 0x00;
    static final int COMMAND_READ_ATTRIBUTES_RESPONSE = 0x01;
    static final int COMMAND_REPORT_ATTRIBUTES = 0x0A;
    static final int COMMAND_DEFAULT_RESPONSE = 0x0B;

    private static final Logger log = LoggerFactory.getLogger(ZclCodec.class);

    private ZclCodec() {
    }

    /**
     * A parsed ZCL frame header.
     *
     * @param clusterSpecific {@code true} for cluster-specific commands (frame
     *        type 01 — e.g., IAS ZoneStatusChangeNotification)
     * @param manufacturerCode the manufacturer code, or {@code -1} when the frame
     *        is not manufacturer-specific
     * @param transactionSequence the ZCL transaction sequence number
     * @param commandId the command id
     * @param payloadOffset the offset of the command payload within the frame
     */
    record ZclHeader(boolean clusterSpecific, int manufacturerCode,
            int transactionSequence, int commandId, int payloadOffset) {
    }

    /**
     * Parses the ZCL frame header.
     *
     * @param payload the ZCL frame bytes
     * @return the header, or empty if truncated
     */
    static Optional<ZclHeader> parseHeader(byte[] payload) {
        if (payload.length < 3) {
            return Optional.empty();
        }
        int frameControl = payload[0] & 0xFF;
        boolean clusterSpecific = (frameControl & 0x03) == 0x01;
        boolean manufacturerSpecific = (frameControl & 0x04) != 0;
        int offset = 1;
        int manufacturerCode = -1;
        if (manufacturerSpecific) {
            if (payload.length < 5) {
                return Optional.empty();
            }
            manufacturerCode = (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8);
            offset = 3;
        }
        int transactionSequence = payload[offset] & 0xFF;
        int commandId = payload[offset + 1] & 0xFF;
        return Optional.of(new ZclHeader(clusterSpecific, manufacturerCode,
                transactionSequence, commandId, offset + 2));
    }

    /**
     * Decodes Report Attributes records ({@code [attrId LE][type][value]}…).
     *
     * @param payload the ZCL frame bytes
     * @param offset the command payload offset (from the parsed header)
     * @return the decoded attributes keyed by attribute id, in wire order
     */
    static Map<Integer, Object> parseAttributeReports(byte[] payload, int offset) {
        Map<Integer, Object> attributes = new LinkedHashMap<>();
        int position = offset;
        while (position + 3 <= payload.length) {
            int attributeId = (payload[position] & 0xFF)
                    | ((payload[position + 1] & 0xFF) << 8);
            int dataType = payload[position + 2] & 0xFF;
            Decoded decoded = decodeValue(dataType, payload, position + 3);
            if (decoded == null) {
                log.debug("ZCL attribute 0x{} carries unknown data type 0x{}; "
                                + "remaining records skipped",
                        Integer.toHexString(attributeId),
                        Integer.toHexString(dataType));
                break;
            }
            if (decoded.value() != null) {          // F-9: null = invalid marker, dropped
                attributes.put(attributeId, decoded.value());
            }
            position = decoded.nextOffset();
        }
        return attributes;
    }

    /**
     * Decodes Read Attributes Response records
     * ({@code [attrId LE][status][type][value]}…); failed records are skipped.
     *
     * @param payload the ZCL frame bytes
     * @param offset the command payload offset (from the parsed header)
     * @return the decoded attributes keyed by attribute id, in wire order
     */
    static Map<Integer, Object> parseReadAttributesResponse(byte[] payload,
            int offset) {
        Map<Integer, Object> attributes = new LinkedHashMap<>();
        int position = offset;
        while (position + 3 <= payload.length) {
            int attributeId = (payload[position] & 0xFF)
                    | ((payload[position + 1] & 0xFF) << 8);
            int status = payload[position + 2] & 0xFF;
            if (status != 0) {
                position += 3;
                continue;
            }
            if (position + 4 > payload.length) {
                break;
            }
            int dataType = payload[position + 3] & 0xFF;
            Decoded decoded = decodeValue(dataType, payload, position + 4);
            if (decoded == null) {
                log.debug("ZCL read response attribute 0x{} carries unknown data "
                                + "type 0x{}; remaining records skipped",
                        Integer.toHexString(attributeId),
                        Integer.toHexString(dataType));
                break;
            }
            if (decoded.value() != null) {          // F-9: null = invalid marker, dropped
                attributes.put(attributeId, decoded.value());
            }
            position = decoded.nextOffset();
        }
        return attributes;
    }

    /**
     * Encodes a global Read Attributes command
     * ({@code [fc 0x00][tsn][cmd 0x00][attr ids LE…]}).
     *
     * @param tsn the ZCL transaction sequence number (0–255)
     * @param attributeIds the attribute ids to read
     * @return the ZCL frame bytes
     */
    static byte[] encodeReadAttributes(int tsn, int[] attributeIds) {
        byte[] frame = new byte[3 + attributeIds.length * 2];
        frame[0] = 0x00;
        frame[1] = (byte) tsn;
        frame[2] = (byte) COMMAND_READ_ATTRIBUTES;
        for (int i = 0; i < attributeIds.length; i++) {
            frame[3 + i * 2] = (byte) (attributeIds[i] & 0xFF);
            frame[4 + i * 2] = (byte) ((attributeIds[i] >> 8) & 0xFF);
        }
        return frame;
    }

    private record Decoded(Object value, int nextOffset) {
    }

    private static Decoded decodeValue(int dataType, byte[] payload, int offset) {
        return switch (dataType) {
            case 0x10 -> { // bool
                if (offset + 1 > payload.length) {
                    yield null;
                }
                int marker = payload[offset] & 0xFF;
                if (marker != 0x00 && marker != 0x01) {
                    // F-9: any other marker (e.g. 0xFF "invalid") is NOT a value
                    // observation — skip THIS record, keep the rest of the frame
                    // (a null value; the callers drop it and continue).
                    log.debug("ZCL bool attribute carries invalid marker 0x{}; "
                            + "record skipped (F-9)", Integer.toHexString(marker));
                    yield new Decoded(null, offset + 1);
                }
                yield new Decoded(marker == 0x01, offset + 1);
            }
            case 0x18, 0x20, 0x30 -> // map8, uint8, enum8
                    unsignedLittleEndian(payload, offset, 1);
            case 0x19, 0x21, 0x31 -> // map16, uint16, enum16
                    unsignedLittleEndian(payload, offset, 2);
            case 0x22 -> unsignedLittleEndian(payload, offset, 3); // uint24
            case 0x23 -> unsignedLittleEndian(payload, offset, 4); // uint32
            case 0x25 -> unsignedLittleEndian(payload, offset, 6); // uint48
            case 0x28 -> signedLittleEndian(payload, offset, 1); // int8
            case 0x29 -> signedLittleEndian(payload, offset, 2); // int16
            case 0x2B -> signedLittleEndian(payload, offset, 4); // int32
            case 0x42 -> { // character string, length-prefixed
                if (offset + 1 > payload.length) {
                    yield null;
                }
                int length = payload[offset] & 0xFF;
                if (length == 0xFF || offset + 1 + length > payload.length) {
                    yield null;
                }
                yield new Decoded(new String(payload, offset + 1, length,
                        StandardCharsets.UTF_8), offset + 1 + length);
            }
            default -> null;
        };
    }

    private static Decoded unsignedLittleEndian(byte[] payload, int offset,
            int width) {
        if (offset + width > payload.length) {
            return null;
        }
        long value = 0;
        for (int i = 0; i < width; i++) {
            value |= (long) (payload[offset + i] & 0xFF) << (8 * i);
        }
        return new Decoded(value, offset + width);
    }

    private static Decoded signedLittleEndian(byte[] payload, int offset, int width) {
        Decoded unsigned = unsignedLittleEndian(payload, offset, width);
        if (unsigned == null) {
            return null;
        }
        long raw = (Long) unsigned.value();
        int shift = 64 - width * 8;
        return new Decoded((raw << shift) >> shift, unsigned.nextOffset());
    }
}
