/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import java.util.Objects;
import java.util.Set;

/**
 * One endpoint's contribution to a {@link Fingerprint}: the ZCL profile id, device
 * type id, and input/output cluster sets — the corpus IR
 * {@code identity.fingerprint[]} fields (profileId / deviceType / endpoints in+out
 * clusters), pinned verbatim from the bench IR schema.
 *
 * <p>Zigbee-scoped: this type's vocabulary is deliberately protocol-specific; it is
 * NOT the generic profile contract (Doc 18 §3.5(d) seam note).
 *
 * <p>Thread-safe: immutable record with defensively copied sets.
 *
 * @param profileId the ZCL profile id (typically 0x0104 Home Automation), non-negative
 * @param deviceType the ZCL device type id from the Simple Descriptor, non-negative
 * @param inClusters the server-side cluster ids, never {@code null}
 * @param outClusters the client-side cluster ids, never {@code null}
 * @see Fingerprint
 */
public record EndpointSignature(
        int profileId,
        int deviceType,
        Set<Integer> inClusters,
        Set<Integer> outClusters) {

    /**
     * Creates an endpoint signature with validation and defensive copies.
     *
     * @param profileId must be non-negative
     * @param deviceType must be non-negative
     * @param inClusters never {@code null}
     * @param outClusters never {@code null}
     */
    public EndpointSignature {
        if (profileId < 0) {
            throw new IllegalArgumentException(
                    "profileId must be non-negative, got " + profileId);
        }
        if (deviceType < 0) {
            throw new IllegalArgumentException(
                    "deviceType must be non-negative, got " + deviceType);
        }
        Objects.requireNonNull(inClusters, "inClusters must not be null");
        Objects.requireNonNull(outClusters, "outClusters must not be null");
        inClusters = Set.copyOf(inClusters);
        outClusters = Set.copyOf(outClusters);
    }
}
