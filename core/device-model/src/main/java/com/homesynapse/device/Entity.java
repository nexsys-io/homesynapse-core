/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;

import java.time.Instant;
import java.util.List;

/**
 * Represents the atomic functional unit of a device in HomeSynapse.
 *
 * <p>An entity is the primary target for automation rules, state queries,
 * and commands. Each entity belongs to a single {@link Device} and has an
 * {@link EntityType} that determines its required and optional capabilities.
 * The {@code endpointIndex} maps to Zigbee endpoints for multi-endpoint devices.</p>
 *
 * <p>The {@code enabled} property is an administrative flag set during adoption
 * and toggled via {@code entity_enabled}/{@code entity_disabled} events. Disabled
 * entities do not participate in automation evaluation or command dispatch.</p>
 *
 * <p>The {@code entityRole} property (AMD-44 §2.5) is the UX-role axis, orthogonal to
 * {@link EntityType}: it records whether the entity is what the user cares about
 * ({@link EntityRole#PRIMARY}), reports device health ({@link EntityRole#DIAGNOSTIC}),
 * or controls configuration ({@link EntityRole#CONFIG}). The role must be legal for the
 * entity type ({@link EntityType#allows(EntityRole)}) — an illegal pair is rejected at
 * construction, so it is unrepresentable. The role is mutable post-adoption via the
 * {@code entity_profile_changed} event; reclassification preserves the {@link EntityId}
 * (INV-CS-02).</p>
 *
 * <p>Defined in Doc 02 §4.2.</p>
 *
 * @param entityId the unique identifier for this entity, never {@code null}
 * @param entitySlug a URL-safe human-readable slug, never {@code null}
 * @param entityType the functional classification of this entity, never {@code null}
 * @param displayName the user-facing display name, never {@code null}
 * @param deviceId the owning device's identifier, {@code null} for helper entities
 * @param endpointIndex the device endpoint index (maps to Zigbee endpoints for multi-endpoint devices)
 * @param areaId the area override for this entity, {@code null} to inherit from the device
 * @param enabled whether this entity is administratively enabled
 * @param labels user-assigned classification labels; unmodifiable
 * @param capabilities the capability instances bound to this entity; unmodifiable
 * @param entityRole the UX-role classification, never {@code null} after construction
 *                   ({@code null} coerces to {@link EntityRole#PRIMARY}); must satisfy
 *                   {@code entityType.allows(entityRole)}
 * @param createdAt the timestamp when this entity was created, never {@code null}
 * @see Device
 * @see EntityRegistry
 * @see EntityType
 * @see EntityRole
 * @since 1.0
 */
public record Entity(
        EntityId entityId,
        String entitySlug,
        EntityType entityType,
        String displayName,
        DeviceId deviceId,
        int endpointIndex,
        AreaId areaId,
        boolean enabled,
        List<String> labels,
        List<CapabilityInstance> capabilities,
        EntityRole entityRole,
        Instant createdAt
) {
    /**
     * Coerces a {@code null} {@code entityRole} to {@link EntityRole#PRIMARY}, enforces
     * the AMD-44 §2.5.1 legality matrix, and defensively copies the collection
     * components. Guard order is coercion → matrix guard → copies, so a {@code null}
     * role on a type that permits only PRIMARY (e.g. PLUG) coerces and passes.
     */
    public Entity {
        if (entityRole == null) {
            entityRole = EntityRole.PRIMARY;
        }
        // entityType was never null-guarded on this record before AMD-44, and is not
        // guarded here (DP-4 keeps the diff minimal): a null entityType NPEs via the
        // allows(...) call below. That is acceptable and intentional, not a defect.
        if (!entityType.allows(entityRole)) {
            throw new IllegalArgumentException(
                    "entityRole " + entityRole + " is not legal for entityType " + entityType);
        }
        labels = List.copyOf(labels);
        capabilities = List.copyOf(capabilities);
    }

    /**
     * Convenience constructor preserving the pre-AMD-44 11-argument signature;
     * {@code entityRole} defaults to {@link EntityRole#PRIMARY}.
     *
     * @param entityId the unique identifier for this entity, never {@code null}
     * @param entitySlug a URL-safe human-readable slug, never {@code null}
     * @param entityType the functional classification of this entity, never {@code null}
     * @param displayName the user-facing display name, never {@code null}
     * @param deviceId the owning device's identifier, {@code null} for helper entities
     * @param endpointIndex the device endpoint index
     * @param areaId the area override for this entity, {@code null} to inherit from the device
     * @param enabled whether this entity is administratively enabled
     * @param labels user-assigned classification labels; unmodifiable
     * @param capabilities the capability instances bound to this entity; unmodifiable
     * @param createdAt the timestamp when this entity was created, never {@code null}
     */
    public Entity(EntityId entityId, String entitySlug, EntityType entityType, String displayName,
                  DeviceId deviceId, int endpointIndex, AreaId areaId, boolean enabled,
                  List<String> labels, List<CapabilityInstance> capabilities, Instant createdAt) {
        this(entityId, entitySlug, entityType, displayName, deviceId, endpointIndex, areaId,
                enabled, labels, capabilities, EntityRole.PRIMARY, createdAt);
    }
}
