/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Objects;

/**
 * Collected device metadata from the Zigbee interview pipeline.
 *
 * <p>Produced after a successful or partial interview (steps 2–6 of Doc 08 §3.4).
 * The interview gathers: Node Descriptor (device type, manufacturer code), Active
 * Endpoints, Simple Descriptors (clusters per endpoint), and Basic cluster attributes
 * (manufacturer name, model identifier, power source). Consumed by the
 * {@code device_discovered} event builder.
 *
 * <p>Doc 08 §3.4 interview pipeline result.
 *
 * <p>Thread-safe: immutable record with defensively copied list.
 *
 * @param ieeeAddress the device's permanent IEEE EUI-64 address, never {@code null}
 * @param networkAddress the device's current 16-bit network address
 * @param nodeDescriptor the ZDO Node Descriptor, never {@code null}
 * @param endpoints the endpoint descriptors from Simple Descriptor queries, never {@code null}, never empty
 * @param manufacturerName the Basic cluster manufacturer name, never {@code null}
 * @param modelIdentifier the Basic cluster model identifier, never {@code null}
 * @param powerSource the ZCL PowerSource enum value from the Basic cluster
 * @param interviewStatus the completion state of the interview, never {@code null}
 * @see CoordinatorProtocol#interview(IEEEAddress)
 * @see ZigbeeDeviceRecord
 */
public record InterviewResult(
        IEEEAddress ieeeAddress,
        int networkAddress,
        NodeDescriptor nodeDescriptor,
        List<EndpointDescriptor> endpoints,
        String manufacturerName,
        String modelIdentifier,
        int powerSource,
        InterviewStatus interviewStatus) {

    /**
     * Creates an interview result with validation and defensive copy.
     *
     * @param ieeeAddress never {@code null}
     * @param networkAddress the 16-bit network address
     * @param nodeDescriptor never {@code null}
     * @param endpoints never {@code null}, must not be empty
     * @param manufacturerName never {@code null}
     * @param modelIdentifier never {@code null}
     * @param powerSource the ZCL PowerSource value
     * @param interviewStatus never {@code null}
     */
    public InterviewResult {
        Objects.requireNonNull(ieeeAddress, "ieeeAddress must not be null");
        Objects.requireNonNull(nodeDescriptor, "nodeDescriptor must not be null");
        Objects.requireNonNull(endpoints, "endpoints must not be null");
        endpoints = List.copyOf(endpoints);
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints must not be empty");
        }
        Objects.requireNonNull(manufacturerName, "manufacturerName must not be null");
        Objects.requireNonNull(modelIdentifier, "modelIdentifier must not be null");
        Objects.requireNonNull(interviewStatus, "interviewStatus must not be null");
    }
}
