/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.FloorId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal in-memory {@link AreaRegistry} — the MVP substrate the composition
 * root instantiates at app-bootstrap (AB-3, PD-2).
 *
 * <h2>MVP substrate — read this before relying on it</h2>
 *
 * <p>This is the deliberately-minimal production registry that lets the runtime
 * boot zero-configuration (INV-CE-02). {@link AreaRegistry} is read-only in
 * Stage 1 (no create/update/delete; the area write lifecycle and lifecycle
 * events are deferred to AMD-45), so this implementation is
 * <strong>immutable after construction</strong>: the composition root builds it
 * empty for a first boot, and a seed constructor exists for tests and for the
 * future synthetic-area path. It mints no synthetic "Unassigned" floor
 * (AMD-44 Decision 5) — {@link #getUnassigned()} returns areas whose
 * {@code floorId} is {@code null}.</p>
 *
 * <h2>Concurrency (LTD-11)</h2>
 *
 * <p>Thread-safe by construction-time immutability: the backing map is built
 * once and never mutated, so no lock (and no {@code synchronized}) is needed.
 * When AMD-45 adds write paths, those must use {@link java.util.concurrent.locks.ReentrantLock}.</p>
 */
public final class InMemoryAreaRegistry implements AreaRegistry {

    private final Map<AreaId, Area> areas;

    /** Creates an empty registry (zero-configuration first boot, INV-CE-02). */
    public InMemoryAreaRegistry() {
        this.areas = Map.of();
    }

    /**
     * Creates a registry seeded with a fixed set of areas. Used by tests and by
     * the future synthetic-area seeding path; the registry is immutable
     * thereafter (Stage-1 read-only contract).
     *
     * @param initialAreas the areas to expose; never {@code null}, no null
     *                     elements. Later entries win on duplicate {@code id}.
     */
    public InMemoryAreaRegistry(Collection<Area> initialAreas) {
        Objects.requireNonNull(initialAreas, "initialAreas");
        Map<AreaId, Area> seeded = new LinkedHashMap<>();
        for (Area area : initialAreas) {
            Objects.requireNonNull(area, "area");
            seeded.put(area.id(), area);
        }
        this.areas = Map.copyOf(seeded);
    }

    @Override
    public Optional<Area> get(AreaId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(areas.get(id));
    }

    @Override
    public Collection<Area> getAll() {
        return List.copyOf(areas.values());
    }

    @Override
    public Collection<Area> getByFloor(FloorId floorId) {
        Objects.requireNonNull(floorId, "floorId");
        // Area.floorId() is nullable (unassigned areas); a null floorId never
        // equals the requested id, so unassigned areas are excluded here.
        return areas.values().stream()
                .filter(area -> floorId.equals(area.floorId()))
                .toList();
    }

    @Override
    public Collection<Area> getUnassigned() {
        return areas.values().stream()
                .filter(area -> area.floorId() == null)
                .toList();
    }
}
