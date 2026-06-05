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
 * <p>Adapters that expose diagnostic or configuration entities must explicitly declare
 * the {@code entityRole}; the legality matrix is validated at construction — an illegal
 * combination is rejected (AMD-44 §2.5.3). Adapters that do not classify use the 3-arg
 * convenience constructor, which defaults the role to {@link EntityRole#PRIMARY}.</p>
 *
 * @param endpointIndex the device endpoint index this entity maps to
 * @param proposedEntityType the proposed entity type classification, never {@code null}
 * @param proposedCapabilities the capability IDs proposed for this entity; unmodifiable
 * @param entityRole the UX-role classification, never {@code null} after construction
 *                   ({@code null} coerces to {@link EntityRole#PRIMARY}); must satisfy
 *                   {@code proposedEntityType.allows(entityRole)}
 * @see ProposedDevice
 * @see DiscoveryPipeline
 * @see EntityRole
 * @since 1.0
 */
public record ProposedEntity(
        int endpointIndex,
        EntityType proposedEntityType,
        List<String> proposedCapabilities,
        EntityRole entityRole
) {
    /**
     * Coerces a {@code null} {@code entityRole} to {@link EntityRole#PRIMARY}, enforces
     * the AMD-44 §2.5.1 legality matrix, and defensively copies
     * {@code proposedCapabilities}.
     */
    public ProposedEntity {
        if (entityRole == null) {
            entityRole = EntityRole.PRIMARY;
        }
        if (!proposedEntityType.allows(entityRole)) {
            throw new IllegalArgumentException(
                    "entityRole " + entityRole + " is not legal for entityType " + proposedEntityType);
        }
        proposedCapabilities = List.copyOf(proposedCapabilities);
    }

    /**
     * Convenience constructor for adapters that do not classify; {@code entityRole}
     * defaults to {@link EntityRole#PRIMARY}.
     *
     * @param endpointIndex the device endpoint index this entity maps to
     * @param proposedEntityType the proposed entity type classification, never {@code null}
     * @param proposedCapabilities the capability IDs proposed for this entity; unmodifiable
     */
    public ProposedEntity(int endpointIndex, EntityType proposedEntityType, List<String> proposedCapabilities) {
        this(endpointIndex, proposedEntityType, proposedCapabilities, EntityRole.PRIMARY);
    }
}
