/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Per-device Zigbee protocol metadata maintained in the adapter's local cache.
 *
 * <p>Separate from the Device Model's {@code EntityRegistry} — this record holds
 * protocol-level metadata needed for transport and protocol operations (IEEE address,
 * network address, endpoint descriptors, interview state). Serialized to
 * {@code zigbee-devices.json} on adapter shutdown for persistence across restarts.
 *
 * <p>Fields may be {@code null} if the interview has not yet completed: the adapter
 * creates an initial record with only {@code ieeeAddress} and {@code lastSeen} on
 * first contact, then populates remaining fields as interview steps complete.
 *
 * <p>Doc 08 §3.14 local device metadata cache.
 *
 * <p>Thread-safe: immutable record with defensively copied list.
 *
 * @param ieeeAddress the device's permanent IEEE EUI-64 address, never {@code null}
 * @param networkAddress the device's current 16-bit network address (0x0000–0xFFFF)
 * @param nodeDescriptor the ZDO Node Descriptor; {@code null} if interview not yet complete
 * @param endpoints the endpoint descriptors; {@code null} if interview not yet complete
 * @param manufacturerName the Basic cluster manufacturer name; {@code null} if not yet read
 * @param modelIdentifier the Basic cluster model identifier; {@code null} if not yet read
 * @param powerSource the ZCL PowerSource value (0 if unknown)
 * @param lastSeen the timestamp of the most recent frame from this device, never {@code null}
 * @param interviewStatus the interview completion state, never {@code null}
 * @param matchedProfileId the device profile match result; {@code null} if not yet matched or no profile applies
 * @see IEEEAddress
 * @see InterviewResult
 * @see DeviceProfile
 */
public record ZigbeeDeviceRecord(
        IEEEAddress ieeeAddress,
        int networkAddress,
        NodeDescriptor nodeDescriptor,
        List<EndpointDescriptor> endpoints,
        String manufacturerName,
        String modelIdentifier,
        int powerSource,
        Instant lastSeen,
        InterviewStatus interviewStatus,
        String matchedProfileId) {

    /**
     * Creates a Zigbee device record with validation and defensive copy.
     *
     * @param ieeeAddress never {@code null}
     * @param networkAddress must be 0x0000–0xFFFF
     * @param nodeDescriptor {@code null} if interview not yet complete
     * @param endpoints {@code null} if interview not yet complete
     * @param manufacturerName {@code null} if not yet read
     * @param modelIdentifier {@code null} if not yet read
     * @param powerSource the ZCL PowerSource value, 0 if unknown
     * @param lastSeen never {@code null}
     * @param interviewStatus never {@code null}
     * @param matchedProfileId {@code null} if not matched
     */
    public ZigbeeDeviceRecord {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress must not be null");
        if (networkAddress < 0 || networkAddress > 0xFFFF) {
            throw new IllegalArgumentException(
                    "networkAddress must be 0x0000–0xFFFF, got " + networkAddress);
        }
        endpoints = endpoints != null ? List.copyOf(endpoints) : null;
        Objects.requireNonNull(lastSeen, "lastSeen must not be null");
        Objects.requireNonNull(interviewStatus, "interviewStatus must not be null");
    }
}
