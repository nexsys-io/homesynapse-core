/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.rest;

import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test fake {@link EntityRegistry} for the CMD-API endpoint tests.
 *
 * <p>Configurable through {@link #put(Entity)} (one entity at a time —
 * the {@link FakeStateQueryService} convention). Only the read methods the
 * command endpoints use are implemented; the mutating surface throws
 * {@link UnsupportedOperationException}.</p>
 *
 * <p>Test-only — not part of the rest-api module's exported API.</p>
 */
final class FakeEntityRegistry implements EntityRegistry {

    private final LinkedHashMap<EntityId, Entity> entities = new LinkedHashMap<>();

    FakeEntityRegistry() {
    }

    FakeEntityRegistry put(Entity entity) {
        entities.put(entity.entityId(), entity);
        return this;
    }

    @Override
    public Entity getEntity(EntityId entityId) {
        Entity entity = entities.get(entityId);
        if (entity == null) {
            throw new IllegalStateException("Entity not seeded: " + entityId);
        }
        return entity;
    }

    @Override
    public Optional<Entity> findEntity(EntityId entityId) {
        return Optional.ofNullable(entities.get(entityId));
    }

    @Override
    public List<Entity> listAllEntities() {
        return List.copyOf(entities.values());
    }

    @Override
    public List<Entity> listEntitiesByDevice(DeviceId deviceId) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public Entity createEntity(Entity entity) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public Entity updateEntity(Entity entity) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public void removeEntity(EntityId entityId) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public void enableEntity(EntityId entityId) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    @Override
    public void disableEntity(EntityId entityId) {
        throw new UnsupportedOperationException("not used by the command endpoints");
    }

    /** Convenience: the seeded map, for assertions. */
    Map<EntityId, Entity> seeded() {
        return Map.copyOf(entities);
    }
}
