/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;

import java.util.List;
import java.util.Optional;

/**
 * Registry for managing the lifecycle of device entities in HomeSynapse.
 *
 * <p>Provides CRUD operations for entity records, device-scoped queries,
 * capability composition validation, and administrative enable/disable
 * operations. Entities are the primary targets for automation rules,
 * state queries, and command dispatch.</p>
 *
 * <p>Implementations must be safe for concurrent read access. Write operations
 * are serialized.</p>
 *
 * <p>Defined in Doc 02 §8.1.</p>
 *
 * @see Entity
 * @see Device
 * @see DeviceRegistry
 * @see EntityType
 * @since 1.0
 */
public interface EntityRegistry {

    /**
     * Retrieves an entity by its identifier.
     *
     * @param entityId the entity identifier, never {@code null}
     * @return the entity record, never {@code null}
     * @throws IllegalArgumentException if no entity exists with the given identifier
     */
    Entity getEntity(EntityId entityId);

    /**
     * Finds an entity by its identifier, returning empty if not found.
     *
     * @param entityId the entity identifier, never {@code null}
     * @return an {@link Optional} containing the entity if found, or empty
     */
    Optional<Entity> findEntity(EntityId entityId);

    /**
     * Returns all registered entities.
     *
     * @return an unmodifiable list of all entities, never {@code null}
     */
    List<Entity> listAllEntities();

    /**
     * Returns all entities belonging to a specific device.
     *
     * @param deviceId the owning device's identifier, never {@code null}
     * @return an unmodifiable list of entities for the device, never {@code null}; empty if none
     */
    List<Entity> listEntitiesByDevice(DeviceId deviceId);

    /**
     * Creates a new entity record after validating capability composition
     * against the entity type's requirements.
     *
     * @param entity the entity to create, never {@code null}
     * @return the created entity record
     * @throws IllegalArgumentException if capability composition is invalid for the entity type
     */
    Entity createEntity(Entity entity);

    /**
     * Updates an existing entity record.
     *
     * @param entity the entity with updated fields, never {@code null}
     * @return the updated entity record
     * @throws IllegalArgumentException if no entity exists with the given identifier
     */
    Entity updateEntity(Entity entity);

    /**
     * Removes an entity from the registry.
     *
     * @param entityId the identifier of the entity to remove, never {@code null}
     * @throws IllegalArgumentException if no entity exists with the given identifier
     */
    void removeEntity(EntityId entityId);

    /**
     * Administratively enables an entity, allowing it to participate in
     * automation evaluation and command dispatch.
     *
     * @param entityId the identifier of the entity to enable, never {@code null}
     * @throws IllegalArgumentException if no entity exists with the given identifier
     */
    void enableEntity(EntityId entityId);

    /**
     * Administratively disables an entity, preventing it from participating
     * in automation evaluation and command dispatch.
     *
     * @param entityId the identifier of the entity to disable, never {@code null}
     * @throws IllegalArgumentException if no entity exists with the given identifier
     */
    void disableEntity(EntityId entityId);
}
