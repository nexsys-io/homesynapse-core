/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Minimal in-memory {@link EntityRegistry} — the MVP substrate the
 * composition root instantiates at app-bootstrap (AB-3, PD-2).
 *
 * <h2>MVP substrate — read this before relying on it</h2>
 *
 * <p>This is the deliberately-minimal production registry that lets the
 * runtime boot zero-configuration (INV-CE-02): it <strong>starts empty</strong>
 * and supports only CRUD plus the small query set the automation engine needs
 * ({@link #listEntitiesByDevice(DeviceId)}). It is NOT the future
 * integration-backed registry: it does <strong>not</strong> perform AMD-44
 * capability-composition validation on {@link #createEntity(Entity)}, mints no
 * derivation, and emits no entity lifecycle events. Entities are populated
 * later by the M9/M14 device-discovery integrations (which will supply the
 * SQLite-backed registry living in {@code core:persistence}). Treat this as the
 * boot-time placeholder, not the production data path.</p>
 *
 * <h2>Concurrency (LTD-11)</h2>
 *
 * <p>Reads are lock-free against a {@code volatile} immutable map snapshot;
 * writes copy-on-write under a {@link ReentrantLock} (never {@code synchronized},
 * to avoid virtual-thread carrier pinning). This satisfies the
 * {@link EntityRegistry} contract: "safe for concurrent read access. Write
 * operations are serialized."</p>
 */
public final class InMemoryEntityRegistry implements EntityRegistry {

    private final ReentrantLock writeLock = new ReentrantLock();

    /** Immutable snapshot; replaced wholesale under {@link #writeLock} on every write. */
    private volatile Map<EntityId, Entity> entities = Map.of();

    /** Creates an empty registry (zero-configuration first boot, INV-CE-02). */
    public InMemoryEntityRegistry() {
        // Starts empty — populated later by device-discovery integrations.
    }

    @Override
    public Entity getEntity(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId");
        Entity entity = entities.get(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("no entity with id: " + entityId);
        }
        return entity;
    }

    @Override
    public Optional<Entity> findEntity(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId");
        return Optional.ofNullable(entities.get(entityId));
    }

    @Override
    public List<Entity> listAllEntities() {
        return List.copyOf(entities.values());
    }

    @Override
    public List<Entity> listEntitiesByDevice(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        // Entity.deviceId() is nullable (helper entities own no device); a null
        // deviceId never equals the requested id, so helpers are excluded.
        return entities.values().stream()
                .filter(entity -> deviceId.equals(entity.deviceId()))
                .toList();
    }

    @Override
    public Entity createEntity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        // MVP substrate: stores the entity as given (last-write-wins). The
        // AMD-44 capability-composition validation the interface documents is
        // deferred to the integration-backed registry.
        writeLock.lock();
        try {
            Map<EntityId, Entity> next = new HashMap<>(entities);
            next.put(entity.entityId(), entity);
            entities = Map.copyOf(next);
            return entity;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Entity updateEntity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        writeLock.lock();
        try {
            if (!entities.containsKey(entity.entityId())) {
                throw new IllegalArgumentException(
                        "no entity with id: " + entity.entityId());
            }
            Map<EntityId, Entity> next = new HashMap<>(entities);
            next.put(entity.entityId(), entity);
            entities = Map.copyOf(next);
            return entity;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeEntity(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId");
        writeLock.lock();
        try {
            if (!entities.containsKey(entityId)) {
                throw new IllegalArgumentException("no entity with id: " + entityId);
            }
            Map<EntityId, Entity> next = new HashMap<>(entities);
            next.remove(entityId);
            entities = Map.copyOf(next);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void enableEntity(EntityId entityId) {
        setEnabled(entityId, true);
    }

    @Override
    public void disableEntity(EntityId entityId) {
        setEnabled(entityId, false);
    }

    private void setEnabled(EntityId entityId, boolean enabled) {
        Objects.requireNonNull(entityId, "entityId");
        writeLock.lock();
        try {
            Entity current = entities.get(entityId);
            if (current == null) {
                throw new IllegalArgumentException("no entity with id: " + entityId);
            }
            if (current.enabled() == enabled) {
                return;
            }
            Map<EntityId, Entity> next = new HashMap<>(entities);
            next.put(entityId, withEnabled(current, enabled));
            entities = Map.copyOf(next);
        } finally {
            writeLock.unlock();
        }
    }

    /** Returns a copy of {@code entity} with the {@code enabled} flag set (records are immutable). */
    private static Entity withEnabled(Entity entity, boolean enabled) {
        return new Entity(
                entity.entityId(),
                entity.entitySlug(),
                entity.entityType(),
                entity.displayName(),
                entity.deviceId(),
                entity.endpointIndex(),
                entity.areaId(),
                enabled,
                entity.labels(),
                entity.capabilities(),
                entity.entityRole(),
                entity.createdAt());
    }
}
