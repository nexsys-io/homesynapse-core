package com.homesynapse.integration.zigbee;

/**
 * ZDO Node Descriptor from the Zigbee interview pipeline.
 *
 * <p>The Node Descriptor provides device-level metadata including the logical device
 * type (coordinator, router, or end device), manufacturer code, and buffer capabilities.
 * The {@code deviceType} field determines power-source-aware availability tracking:
 * routers (type 1) are assumed mains-powered; end devices (type 2) are assumed
 * battery-powered.
 *
 * <p>Doc 08 §3.4 step 2 — Node Descriptor.
 *
 * <p>Thread-safe: immutable record.
 *
 * @param deviceType the logical device type: 0 = coordinator, 1 = router, 2 = end device
 * @param manufacturerCode the manufacturer-specific code assigned by the Zigbee Alliance
 * @param maxBufferSize the maximum size of the application-layer data unit the device can receive, must be positive
 * @param macCapabilityFlags the MAC layer capability flags bitmask
 * @see InterviewResult
 * @see ZigbeeDeviceRecord
 */
public record NodeDescriptor(int deviceType, int manufacturerCode, int maxBufferSize, int macCapabilityFlags) {

    /**
     * Creates a node descriptor with validation.
     *
     * @param deviceType must be 0 (coordinator), 1 (router), or 2 (end device)
     * @param manufacturerCode the manufacturer code
     * @param maxBufferSize must be positive
     * @param macCapabilityFlags the MAC capability flags
     */
    public NodeDescriptor {
        if (deviceType < 0 || deviceType > 2) {
            throw new IllegalArgumentException(
                    "deviceType must be 0 (coordinator), 1 (router), or 2 (end device), got " + deviceType);
        }
        if (maxBufferSize <= 0) {
            throw new IllegalArgumentException(
                    "maxBufferSize must be positive, got " + maxBufferSize);
        }
    }
}
