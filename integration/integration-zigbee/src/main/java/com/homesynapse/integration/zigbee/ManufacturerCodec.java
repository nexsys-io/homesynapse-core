package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Map;

/**
 * Sealed interface for manufacturer-specific codec subsystems.
 *
 * <p>Tuya devices tunnel a proprietary datapoint protocol through cluster 0xEF00.
 * Xiaomi/Aqara devices embed sensor data in a custom TLV structure on the Basic
 * cluster (attribute 0xFF01, manufacturer code 0x115F) or cluster 0xFCC0. Treating
 * these as isolated subsystems prevents quirk accumulation (Doc 08 §1 design
 * principle 4: "Manufacturer quirks are isolated in codecs, not scattered across
 * the codebase").
 *
 * <p>The sealed hierarchy enables the adapter to exhaustively dispatch between
 * codec types, ensuring every manufacturer-specific frame is routed to the correct
 * decoder.
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Thread-safe: implementations must be stateless or thread-safe.
 *
 * @see TuyaDpCodec
 * @see XiaomiTlvCodec
 * @see AttributeReport
 * @see ZclFrame
 */
public sealed interface ManufacturerCodec permits TuyaDpCodec, XiaomiTlvCodec {

    /**
     * Decodes a manufacturer-specific ZCL frame into normalized attribute reports.
     *
     * <p>Called when the adapter receives a frame on a manufacturer-specific cluster
     * (e.g., 0xEF00 for Tuya, 0xFCC0 for Xiaomi). The codec interprets the
     * proprietary payload and produces HomeSynapse-normalized attribute observations.
     *
     * @param frame the incoming ZCL frame with manufacturer-specific payload, never {@code null}
     * @return the normalized attribute reports, never {@code null}; may be empty if the
     *         frame contains no reportable attributes
     */
    List<AttributeReport> decode(ZclFrame frame);

    /**
     * Constructs a manufacturer-specific ZCL frame from a HomeSynapse command.
     *
     * <p>Called when the adapter dispatches a command to a device that uses a
     * manufacturer-specific protocol. The codec translates the HomeSynapse command
     * into the manufacturer's proprietary frame format.
     *
     * @param commandType the HomeSynapse command type identifier, never {@code null}
     * @param parameters the command parameters keyed by parameter name, never {@code null}
     * @return the constructed manufacturer-specific ZCL frame, never {@code null}
     */
    ZclFrame encode(String commandType, Map<String, Object> parameters);
}
