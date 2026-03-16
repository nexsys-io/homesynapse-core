/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.AreaId;

import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the device discovery, proposal, and adoption lifecycle.
 *
 * <p>Integration adapters use this pipeline to report newly detected devices.
 * The pipeline handles deduplication (via hardware identifiers), proposal
 * validation, and adoption — creating {@link Device} and {@link Entity}
 * records in the respective registries.</p>
 *
 * <p>Implementations must be safe for concurrent access.</p>
 *
 * <p>Defined in Doc 02 §3.12, §8.1.</p>
 *
 * @see ProposedDevice
 * @see HardwareIdentifier
 * @since 1.0
 */
public interface DiscoveryPipeline {

    /**
     * Creates a device proposal from detected hardware information.
     *
     * @param identifiers the hardware identifiers for deduplication, never {@code null}
     * @param manufacturer the detected manufacturer name, never {@code null}
     * @param model the detected model identifier, never {@code null}
     * @param entities the proposed entities for this device, never {@code null}
     * @return the proposed device, never {@code null}
     */
    ProposedDevice propose(
            List<HardwareIdentifier> identifiers,
            String manufacturer,
            String model,
            List<ProposedEntity> entities);

    /**
     * Adopts a proposed device into the registry, creating device and entity records.
     *
     * @param proposed the proposed device to adopt, never {@code null}
     * @param displayName the user-facing display name for the device, never {@code null}
     * @param areaId the area to assign the device to, {@code null} if unassigned
     * @return the created device record, never {@code null}
     */
    Device adopt(ProposedDevice proposed, String displayName, AreaId areaId);

    /**
     * Checks for an existing device with matching hardware identifiers (deduplication).
     *
     * @param identifiers the hardware identifiers to check, never {@code null}
     * @return an {@link Optional} containing the existing device if found, or empty
     */
    Optional<Device> findExistingDevice(List<HardwareIdentifier> identifiers);
}
