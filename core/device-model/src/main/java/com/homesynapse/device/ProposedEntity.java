/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.List;

/**
 * Represents a proposed entity mapping from a detected device endpoint
 * during the discovery pipeline.
 *
 * <p>Part of the device adoption flow: an integration adapter detects a device
 * and proposes one or more entities with their endpoint indices, entity types,
 * and capability sets. These proposals are wrapped in a {@link ProposedDevice}
 * and submitted to the {@link DiscoveryPipeline} for adoption.</p>
 *
 * @param endpointIndex the device endpoint index this entity maps to
 * @param proposedEntityType the proposed entity type classification, never {@code null}
 * @param proposedCapabilities the capability IDs proposed for this entity; unmodifiable
 * @see ProposedDevice
 * @see DiscoveryPipeline
 * @since 1.0
 */
public record ProposedEntity(
        int endpointIndex,
        EntityType proposedEntityType,
        List<String> proposedCapabilities
) { }
