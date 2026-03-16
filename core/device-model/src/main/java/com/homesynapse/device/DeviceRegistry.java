/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.DeviceId;

import java.util.List;
import java.util.Optional;

/**
 * Registry for managing the lifecycle of physical devices in HomeSynapse.
 *
 * <p>Provides CRUD operations for device records, hardware identifier lookups
 * for discovery deduplication, and device removal with cascading entity cleanup.
 * All queries return immutable snapshots of device state.</p>
 *
 * <p>Implementations must be safe for concurrent read access. Write operations
 * are serialized.</p>
 *
 * <p>Defined in Doc 02 §8.1.</p>
 *
 * @see Device
 * @see Entity
 * @see EntityRegistry
 * @since 1.0
 */
public interface DeviceRegistry {

    /**
     * Retrieves a device by its identifier.
     *
     * @param deviceId the device identifier, never {@code null}
     * @return the device record, never {@code null}
     * @throws IllegalArgumentException if no device exists with the given identifier
     */
    Device getDevice(DeviceId deviceId);

    /**
     * Finds a device by its identifier, returning empty if not found.
     *
     * @param deviceId the device identifier, never {@code null}
     * @return an {@link Optional} containing the device if found, or empty
     */
    Optional<Device> findDevice(DeviceId deviceId);

    /**
     * Returns all registered devices.
     *
     * @return an unmodifiable list of all devices, never {@code null}
     */
    List<Device> listAllDevices();

    /**
     * Creates a new device record in the registry.
     *
     * @param device the device to create, never {@code null}
     * @return the created device record
     */
    Device createDevice(Device device);

    /**
     * Updates an existing device record.
     *
     * @param device the device with updated fields, never {@code null}
     * @return the updated device record
     * @throws IllegalArgumentException if no device exists with the given identifier
     */
    Device updateDevice(Device device);

    /**
     * Removes a device and all its associated entities from the registry.
     *
     * @param deviceId the identifier of the device to remove, never {@code null}
     * @throws IllegalArgumentException if no device exists with the given identifier
     */
    void removeDevice(DeviceId deviceId);

    /**
     * Finds a device by a hardware identifier, used for discovery deduplication.
     *
     * @param namespace the hardware identifier namespace, never {@code null}
     * @param value the hardware identifier value, never {@code null}
     * @return an {@link Optional} containing the matching device, or empty if no match
     */
    Optional<Device> findByHardwareIdentifier(String namespace, String value);
}
