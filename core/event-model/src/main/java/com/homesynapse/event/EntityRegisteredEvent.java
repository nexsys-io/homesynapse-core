/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.platform.identity.Ulid;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Event emitted when an entity record is registered into the entity registry —
 * the full-fidelity registration fact, capabilities and installed DP-a
 * confirmation tuning included (AMD-99 §3, ratified field contract).
 *
 * <p>The registries are projections of the event log (REG-INV-1): this payload
 * carries the COMPLETE entity record so replay alone rebuilds the entity
 * registry. Registration UPDATES (e.g. posture routing overlaying measured
 * reporting facts, or a future re-interview) ride an idempotent re-emit of
 * this same type with refreshed capability mirrors — there is NO third event
 * type (AMD-99 F1); the projection's upsert-by-identity makes the re-emit an
 * update and a live self-delivery a no-op (DP-8).</p>
 *
 * <p>Subject ref: {@code SubjectRef.entity(entityId)}. Priority: NORMAL.</p>
 *
 * @param entityId the minted entity identity (raw ULID; the typed
 *        {@code EntityId} wrapper is reconstructed at apply — LTD-04), never {@code null}
 * @param entitySlug the URL-safe entity slug, never {@code null}
 * @param entityType the {@code EntityType} enum name (e.g. {@code "LIGHT"}),
 *        never {@code null}
 * @param displayName the user-facing display name, never {@code null}
 * @param deviceId the owning device's identity (raw ULID), never {@code null} —
 *        registration events describe device-backed entities
 * @param endpointIndex the device endpoint index
 * @param areaId the area override, {@code null} to inherit from the device
 * @param enabled whether the entity is administratively enabled
 * @param labels user-assigned labels, never {@code null}; order-preserving
 *        unmodifiable copy
 * @param entityRole the RESOLVED {@code EntityRole} enum name — never
 *        {@code null}; the domain constructor coerces a null role to PRIMARY
 *        before this mirror is built, so the payload always carries the
 *        resolved role
 * @param createdAt the creation timestamp, never {@code null}
 * @param capabilities the capability-instance mirrors (attributes + commands +
 *        the installed confirmation tuning), never {@code null};
 *        order-preserving unmodifiable copy
 * @see DeviceRegisteredEvent
 * @see EventTypes#ENTITY_REGISTERED
 */
@EventType(EventTypes.ENTITY_REGISTERED)
public record EntityRegisteredEvent(
        Ulid entityId,
        String entitySlug,
        String entityType,
        String displayName,
        Ulid deviceId,
        int endpointIndex,
        Ulid areaId,
        boolean enabled,
        List<String> labels,
        String entityRole,
        Instant createdAt,
        List<CapabilityInstanceRef> capabilities
) implements DomainEvent {

    /**
     * Validates required components and defensively copies the list components
     * (order-preserving — capability order is the domain's list order).
     *
     * @throws NullPointerException if a required component is {@code null}
     */
    public EntityRegisteredEvent {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(entitySlug, "entitySlug must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(labels, "labels must not be null");
        Objects.requireNonNull(entityRole, "entityRole must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        labels = List.copyOf(labels);
        capabilities = List.copyOf(capabilities);
    }
}
