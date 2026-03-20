package com.homesynapse.integration.zigbee;

import java.util.List;
import java.util.Objects;

/**
 * ZCL Simple Descriptor for a single application endpoint.
 *
 * <p>Each Zigbee device exposes one or more application endpoints (1–240), each with
 * a profile ID, device type ID, and lists of input (server) and output (client) clusters.
 * Input clusters are server-side — the device implements them and responds to commands.
 * Output clusters are client-side — the device sends commands to them (e.g., binding-based
 * reporting).
 *
 * <p>Doc 08 §3.4 step 4 — Simple Descriptor per endpoint.
 *
 * <p>Thread-safe: immutable record with defensively copied lists.
 *
 * @param endpointId the application endpoint number (1–240)
 * @param profileId the ZCL profile ID (typically 0x0104 for Home Automation)
 * @param deviceTypeId the ZCL device type identifier
 * @param inputClusters the server-side cluster IDs implemented by this endpoint, never {@code null}
 * @param outputClusters the client-side cluster IDs this endpoint sends commands to, never {@code null}
 * @see InterviewResult
 * @see ZigbeeDeviceRecord
 */
public record EndpointDescriptor(
        int endpointId,
        int profileId,
        int deviceTypeId,
        List<Integer> inputClusters,
        List<Integer> outputClusters) {

    /**
     * Creates an endpoint descriptor with validation and defensive copies.
     *
     * @param endpointId the endpoint number, must be 1–240
     * @param profileId the profile ID, must be non-negative
     * @param deviceTypeId the device type ID, must be non-negative
     * @param inputClusters the input cluster list, never {@code null}
     * @param outputClusters the output cluster list, never {@code null}
     */
    public EndpointDescriptor {
        if (endpointId < 1 || endpointId > 240) {
            throw new IllegalArgumentException(
                    "endpointId must be 1–240, got " + endpointId);
        }
        if (profileId < 0) {
            throw new IllegalArgumentException(
                    "profileId must be non-negative, got " + profileId);
        }
        if (deviceTypeId < 0) {
            throw new IllegalArgumentException(
                    "deviceTypeId must be non-negative, got " + deviceTypeId);
        }
        Objects.requireNonNull(inputClusters, "inputClusters must not be null");
        Objects.requireNonNull(outputClusters, "outputClusters must not be null");
        inputClusters = List.copyOf(inputClusters);
        outputClusters = List.copyOf(outputClusters);
    }
}
