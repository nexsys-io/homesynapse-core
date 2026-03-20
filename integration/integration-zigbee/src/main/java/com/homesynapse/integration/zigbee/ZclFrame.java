package com.homesynapse.integration.zigbee;

/**
 * Protocol-level ZCL (Zigbee Cluster Library) frame representing a single cluster operation.
 *
 * <p>This is above the transport layer — ZCL frames are constructed by the protocol layer
 * from transport frames ({@link ZigbeeFrame}) and consumed by cluster handlers and
 * manufacturer codecs. Outbound ZCL frames are constructed by command dispatch and sent
 * via {@link CoordinatorProtocol#sendZclFrame(ZclFrame, IEEEAddress)}.
 *
 * <p>The {@code payload} byte array is defensively copied in the compact constructor
 * and in the accessor to prevent external mutation of the frame's internal state.
 *
 * <p>Doc 08 §4.2 (ZclFrame entry in Key Types table), §3.2 protocol layer.
 *
 * <p>Thread-safe: immutable record with defensively copied byte array.
 *
 * @param sourceEndpoint the source application endpoint (0–240)
 * @param destinationEndpoint the destination application endpoint (0–240)
 * @param clusterId the ZCL cluster identifier (0x0000–0xFFFF)
 * @param commandId the ZCL command identifier (0x00–0xFF)
 * @param isClusterSpecific {@code false} for global ZCL commands (Read Attributes, Configure Reporting), {@code true} for cluster-specific commands
 * @param manufacturerCode the manufacturer-specific code (0 if not manufacturer-specific, e.g., 0x115F for Xiaomi)
 * @param payload the ZCL frame payload bytes; the returned array is a copy — modifications do not affect this record
 * @see ZigbeeFrame
 * @see ClusterHandler
 * @see ManufacturerCodec
 * @see CoordinatorProtocol
 */
public record ZclFrame(
        int sourceEndpoint,
        int destinationEndpoint,
        int clusterId,
        int commandId,
        boolean isClusterSpecific,
        int manufacturerCode,
        byte[] payload) {

    /**
     * Creates a ZCL frame with validation and defensive copy.
     *
     * @param sourceEndpoint must be 0–240
     * @param destinationEndpoint must be 0–240
     * @param clusterId must be 0x0000–0xFFFF
     * @param commandId must be 0x00–0xFF
     * @param isClusterSpecific global vs cluster-specific flag
     * @param manufacturerCode must be non-negative (0 for standard commands)
     * @param payload the ZCL payload bytes (defensively copied), never {@code null}
     */
    public ZclFrame {
        if (sourceEndpoint < 0 || sourceEndpoint > 240) {
            throw new IllegalArgumentException(
                    "sourceEndpoint must be 0–240, got " + sourceEndpoint);
        }
        if (destinationEndpoint < 0 || destinationEndpoint > 240) {
            throw new IllegalArgumentException(
                    "destinationEndpoint must be 0–240, got " + destinationEndpoint);
        }
        if (clusterId < 0 || clusterId > 0xFFFF) {
            throw new IllegalArgumentException(
                    "clusterId must be 0x0000–0xFFFF, got " + clusterId);
        }
        if (commandId < 0 || commandId > 0xFF) {
            throw new IllegalArgumentException(
                    "commandId must be 0x00–0xFF, got " + commandId);
        }
        if (manufacturerCode < 0) {
            throw new IllegalArgumentException(
                    "manufacturerCode must be non-negative, got " + manufacturerCode);
        }
        payload = payload.clone();
    }

    /**
     * Returns a defensive copy of the ZCL frame payload bytes.
     *
     * <p>The returned array is a copy; modifications do not affect this record.
     *
     * @return a copy of the payload bytes, never {@code null}
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
