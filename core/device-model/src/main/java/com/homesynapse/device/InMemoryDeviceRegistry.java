/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.DeviceId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Minimal in-memory {@link DeviceRegistry} — the MVP substrate the
 * composition root instantiates at app-bootstrap (AB-3, PD-2).
 *
 * <h2>MVP substrate — read this before relying on it</h2>
 *
 * <p>This is the deliberately-minimal production registry that lets the
 * runtime boot zero-configuration (INV-CE-02): it <strong>starts empty</strong>
 * and supports only CRUD plus the hardware-identifier lookup
 * ({@link #findByHardwareIdentifier(String, String)}) used for discovery
 * deduplication. It is NOT the future integration-backed registry. In
 * particular, {@link #removeDevice(DeviceId)} removes <strong>only</strong> the
 * device record: the cross-registry cascade to the device's entities (which the
 * {@link DeviceRegistry} contract requires) is deferred to the
 * integration-backed registry, which shares a transactional store with the
 * entity registry. This in-memory substrate holds devices only and has no
 * handle on the entity registry, so it cannot cascade. Devices are populated
 * later by the M9/M14 device-discovery integrations.</p>
 *
 * <h2>Concurrency (LTD-11)</h2>
 *
 * <p>Reads are lock-free against a {@code volatile} immutable map snapshot;
 * writes copy-on-write under a {@link ReentrantLock} (never {@code synchronized}).</p>
 */
public final class InMemoryDeviceRegistry implements DeviceRegistry {

    private final ReentrantLock writeLock = new ReentrantLock();

    /** Immutable snapshot; replaced wholesale under {@link #writeLock} on every write. */
    private volatile Map<DeviceId, Device> devices = Map.of();

    /** Creates an empty registry (zero-configuration first boot, INV-CE-02). */
    public InMemoryDeviceRegistry() {
        // Starts empty — populated later by device-discovery integrations.
    }

    @Override
    public Device getDevice(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        Device device = devices.get(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("no device with id: " + deviceId);
        }
        return device;
    }

    @Override
    public Optional<Device> findDevice(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        return Optional.ofNullable(devices.get(deviceId));
    }

    @Override
    public List<Device> listAllDevices() {
        return List.copyOf(devices.values());
    }

    @Override
    public Device createDevice(Device device) {
        Objects.requireNonNull(device, "device");
        writeLock.lock();
        try {
            Map<DeviceId, Device> next = new HashMap<>(devices);
            next.put(device.deviceId(), device);
            devices = Map.copyOf(next);
            return device;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Device updateDevice(Device device) {
        Objects.requireNonNull(device, "device");
        writeLock.lock();
        try {
            if (!devices.containsKey(device.deviceId())) {
                throw new IllegalArgumentException(
                        "no device with id: " + device.deviceId());
            }
            Map<DeviceId, Device> next = new HashMap<>(devices);
            next.put(device.deviceId(), device);
            devices = Map.copyOf(next);
            return device;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeDevice(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        // MVP substrate: removes only the device record. The contract's cascade
        // to owned entities is deferred to the integration-backed registry (see
        // class Javadoc) — this substrate has no entity-registry handle.
        writeLock.lock();
        try {
            if (!devices.containsKey(deviceId)) {
                throw new IllegalArgumentException("no device with id: " + deviceId);
            }
            Map<DeviceId, Device> next = new HashMap<>(devices);
            next.remove(deviceId);
            devices = Map.copyOf(next);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<Device> findByHardwareIdentifier(String namespace, String value) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(value, "value");
        return devices.values().stream()
                .filter(device -> device.hardwareIdentifiers().stream()
                        .anyMatch(hardwareId ->
                                hardwareId.namespace().equals(namespace)
                                        && hardwareId.value().equals(value)))
                .findFirst();
    }
}
