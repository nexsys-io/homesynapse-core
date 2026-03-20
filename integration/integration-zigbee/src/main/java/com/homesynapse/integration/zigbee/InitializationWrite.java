package com.homesynapse.integration.zigbee;

import java.util.Objects;

/**
 * ZCL attribute write executed after device adoption.
 *
 * <p>Each initialization write specifies a single attribute on a single endpoint.
 * These are used to configure device-specific settings that cannot be expressed
 * through standard cluster configuration (e.g., Aqara wall switch decoupled mode:
 * cluster 0xFCC0, attribute 0x0200, value 0x01, manufacturer code 0x115F).
 *
 * <p>Failures are logged at WARN but do not block adoption.
 *
 * <p>Doc 08 §3.6 — post-adoption attribute writes.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param endpoint the application endpoint number (1–240)
 * @param clusterId the ZCL cluster ID, must be non-negative
 * @param attributeId the ZCL attribute ID, must be non-negative
 * @param dataType the ZCL data type identifier for the value
 * @param value the value to write, never {@code null}
 * @param manufacturerCode the manufacturer-specific code (0 if not manufacturer-specific), must be non-negative
 * @see DeviceProfile
 */
public record InitializationWrite(
        int endpoint,
        int clusterId,
        int attributeId,
        int dataType,
        Object value,
        int manufacturerCode) {

    /**
     * Creates an initialization write with validation.
     *
     * @param endpoint must be 1–240
     * @param clusterId must be non-negative
     * @param attributeId must be non-negative
     * @param dataType the ZCL data type ID
     * @param value the write value, never {@code null}
     * @param manufacturerCode must be non-negative (0 for standard attributes)
     */
    public InitializationWrite {
        if (endpoint < 1 || endpoint > 240) {
            throw new IllegalArgumentException(
                    "endpoint must be 1–240, got " + endpoint);
        }
        if (clusterId < 0) {
            throw new IllegalArgumentException(
                    "clusterId must be non-negative, got " + clusterId);
        }
        if (attributeId < 0) {
            throw new IllegalArgumentException(
                    "attributeId must be non-negative, got " + attributeId);
        }
        Objects.requireNonNull(value, "value must not be null");
        if (manufacturerCode < 0) {
            throw new IllegalArgumentException(
                    "manufacturerCode must be non-negative, got " + manufacturerCode);
        }
    }
}
