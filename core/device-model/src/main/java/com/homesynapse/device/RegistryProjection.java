/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.event.DeviceRegisteredEvent;
import com.homesynapse.event.DeviceRemovedEvent;
import com.homesynapse.event.EntityRegisteredEvent;
import com.homesynapse.platform.identity.DeviceId;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The single registry-projection apply path — REG-INV-1's enforcement subject
 * (Architecture Invariants §53, AMD-99 §4).
 *
 * <p><em>"The device and entity registries are projections of the event log.
 * Every registry mutation flows through a single projection-apply function
 * whose only inputs are the registration/removal event types; at boot the
 * registries are reconstructed by replaying those events; no other code path
 * mutates registry state."</em> The
 * {@code REGISTRY_MUTATION_ONLY_VIA_PROJECTION} ArchUnit rule enforces this:
 * no production class other than this one may call the registries' mutating
 * methods.</p>
 *
 * <p><strong>Apply semantics (DP-8 — upsert in log order).</strong> Identity
 * absent &rArr; create; identity present with equal state &rArr; no-op (this is
 * what makes the live bus self-delivery of an adoption-time event a no-op);
 * identity present with different state &rArr; replace (this is what makes the
 * F1 {@code entity_registered} re-emit an update). Log order is truth — last
 * write wins. {@code device_removed} then a re-{@code device_registered}
 * resurrects cleanly by construction.</p>
 *
 * <p><strong>Write-ahead precondition.</strong> Callers publish the event
 * FIRST (durable at {@code publishRoot} return — INV-ES-04) and apply
 * afterwards; the apply consumes ONLY the event payload, never an in-scope
 * domain object, so live-apply &equiv; replay-apply by construction. The
 * projection derives nothing and publishes nothing (INV-ES-09).</p>
 *
 * <p><strong>Deliberately silent.</strong> device-model carries no logging
 * edge; the boot-rebuild positive-evidence INFO and all projection
 * observability live in the lifecycle-side subscriber (AMD-99 §5), never here.</p>
 *
 * <p><strong>Thread safety.</strong> One {@link ReentrantLock} (LTD-11)
 * serializes applies — the adoption path applies on the zigbee ingestion
 * thread while boot replay/live delivery applies on the registry subscriber's
 * virtual thread. Lock ordering: callers never invoke the projection while
 * holding their own state locks (the zigbee slice applies OUTSIDE its slice
 * lock — the F-8 discipline), and this lock never wraps a callback out of this
 * class, so no lock-order cycle can form.</p>
 *
 * @see RegistryEventMapper
 */
public final class RegistryProjection {

    private final DeviceRegistry deviceRegistry;
    private final EntityRegistry entityRegistry;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Creates the projection over the two registries it exclusively mutates.
     *
     * @param deviceRegistry the device registry, never {@code null}
     * @param entityRegistry the entity registry, never {@code null}
     */
    public RegistryProjection(DeviceRegistry deviceRegistry,
            EntityRegistry entityRegistry) {
        this.deviceRegistry = Objects.requireNonNull(deviceRegistry, "deviceRegistry");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
    }

    /**
     * Upserts the device carried by a {@code device_registered} payload
     * (DP-8: absent &rArr; create, equal &rArr; no-op, different &rArr; replace).
     *
     * @param event the registration payload, never {@code null}
     * @throws IllegalArgumentException if the payload carries an enum name
     *         unknown to this build (fail loudly — the bus's DLQ path handles it)
     */
    public void applyDeviceRegistered(DeviceRegisteredEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Device incoming = RegistryEventMapper.toDevice(event);
        lock.lock();
        try {
            Optional<Device> existing = deviceRegistry.findDevice(incoming.deviceId());
            if (existing.isEmpty()) {
                deviceRegistry.createDevice(incoming);
            } else if (!existing.get().equals(incoming)) {
                deviceRegistry.updateDevice(incoming);
            }
            // Equal state: no-op — the live self-delivery of an adoption-time
            // event, or a replay of an already-applied position.
        } finally {
            lock.unlock();
        }
    }

    /**
     * Upserts the entity carried by an {@code entity_registered} payload
     * (DP-8; the F1 re-emit with refreshed capability mirrors lands here as a
     * replace).
     *
     * @param event the registration payload, never {@code null}
     * @throws IllegalArgumentException if the payload carries an enum name
     *         unknown to this build (fail loudly — the bus's DLQ path handles it)
     */
    public void applyEntityRegistered(EntityRegisteredEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Entity incoming = RegistryEventMapper.toEntity(event);
        lock.lock();
        try {
            Optional<Entity> existing = entityRegistry.findEntity(incoming.entityId());
            if (existing.isEmpty()) {
                entityRegistry.createEntity(incoming);
            } else if (!existing.get().equals(incoming)) {
                entityRegistry.updateEntity(incoming);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies a {@code device_removed} tombstone (DP-7): removes the device and
     * ALL its entities. Removing an absent device is a no-op — replay may
     * deliver a tombstone for a device a later wipe or an earlier replay pass
     * already dropped.
     *
     * <p>The device identity rides the envelope's {@code SubjectRef.device(...)}
     * (G-DUR7), not the payload — the caller extracts it.</p>
     *
     * @param deviceId the device to remove, never {@code null}
     * @param event the tombstone payload (carries the removal reason), never {@code null}
     */
    public void applyDeviceRemoved(DeviceId deviceId, DeviceRemovedEvent event) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(event, "event must not be null");
        lock.lock();
        try {
            if (deviceRegistry.findDevice(deviceId).isEmpty()) {
                return;
            }
            for (Entity entity : entityRegistry.listEntitiesByDevice(deviceId)) {
                entityRegistry.removeEntity(entity.entityId());
            }
            deviceRegistry.removeDevice(deviceId);
        } finally {
            lock.unlock();
        }
    }

    /** @return the number of registered devices (the subscriber's positive-evidence INFO input). */
    public int deviceCount() {
        return deviceRegistry.listAllDevices().size();
    }

    /** @return the number of registered entities (the subscriber's positive-evidence INFO input). */
    public int entityCount() {
        return entityRegistry.listAllEntities().size();
    }
}
