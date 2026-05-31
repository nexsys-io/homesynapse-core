/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.List;
import java.util.Set;

/**
 * Represents a device detected by the discovery pipeline and proposed for adoption.
 *
 * <p>Produced by integration adapters when a new device is detected on the network.
 * Contains the device's hardware identifiers (for deduplication), proposed manufacturer
 * and model metadata, and a list of proposed entities with their capabilities.
 * Submitted to {@link DiscoveryPipeline#adopt(ProposedDevice, String, com.homesynapse.platform.identity.AreaId)}
 * for adoption into the device registry.</p>
 *
 * <p>Defined in Doc 02 §3.12.</p>
 *
 * @param hardwareIdentifiers protocol-level identifiers for deduplication, a set
 *        (unique, unordered) since duplicate {@code (namespace, value)} tuples are meaningless;
 *        defensively copied and unmodifiable
 * @param proposedManufacturer the detected manufacturer name, never {@code null}
 * @param proposedModel the detected model identifier, never {@code null}
 * @param proposedEntities the proposed entities for this device; defensively copied and unmodifiable
 * @see DiscoveryPipeline
 * @see HardwareIdentifier
 * @see ProposedEntity
 * @since 1.0
 */
public record ProposedDevice(
        Set<HardwareIdentifier> hardwareIdentifiers,
        String proposedManufacturer,
        String proposedModel,
        List<ProposedEntity> proposedEntities
) {

    /**
     * Defensively copies the collection fields into unmodifiable views.
     *
     * @throws NullPointerException if {@code hardwareIdentifiers} or {@code proposedEntities}
     *                              (or any element thereof) is {@code null}
     */
    public ProposedDevice {
        hardwareIdentifiers = Set.copyOf(hardwareIdentifiers);
        proposedEntities = List.copyOf(proposedEntities);
    }
}
