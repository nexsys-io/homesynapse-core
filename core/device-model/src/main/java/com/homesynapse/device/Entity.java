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
 * @param createdAt the timestamp when this entity was created, never {@code null}
 * @see Device
 * @see EntityRegistry
 * @see EntityType
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
        Instant createdAt
) { }
