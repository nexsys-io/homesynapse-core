/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import com.homesynapse.platform.identity.Ulid;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Event emitted when a device record is registered into the device registry —
 * the full-fidelity registration fact (AMD-99 §3, ratified field contract).
 *
 * <p>The device and entity registries are projections of the event log
 * (REG-INV-1): this payload carries the COMPLETE device record, so replay
 * alone rebuilds the device registry and every derived map. The registry
 * projection derives the IEEE&rarr;deviceId binding from
 * {@link #hardwareIdentifiers()} — no separate binding field exists.
 * Registration updates ride an idempotent re-emit of this same type (AMD-99 F1).</p>
 *
 * <p>Emission contract (AMD-99 §3): {@code adopt()} publishes this event
 * FIRST (durable at return), then one {@code entity_registered} per created
 * entity, then the existing {@code device_adopted} — causal order device
 * before its entities. Subject ref: {@code SubjectRef.device(deviceId)}.</p>
 *
 * <p>Priority: NORMAL.</p>
 *
 * @param deviceId the minted device identity (raw ULID; the typed
 *        {@code DeviceId} wrapper is reconstructed at apply — LTD-04), never {@code null}
 * @param deviceSlug the URL-safe device slug, never {@code null}
 * @param displayName the user-facing display name, never {@code null}
 * @param manufacturer the device manufacturer name, never {@code null}
 * @param model the device model identifier, never {@code null}
 * @param serialNumber the serial number, {@code null} if not provided
 * @param firmwareVersion the firmware version, {@code null} if not reported
 * @param hardwareVersion the hardware revision, {@code null} if not provided
 * @param integrationId the owning integration's identity as its Crockford
 *        Base32 ULID string (AMD-99 §3 pins this component as {@code String}),
 *        never {@code null}
 * @param areaId the assigned area, {@code null} if unassigned
 * @param viaDeviceId the parent device for router topologies, {@code null} if direct
 * @param labels user-assigned labels, never {@code null}; order-preserving
 *        unmodifiable copy
 * @param hardwareIdentifiers the protocol-level identifier mirrors, never
 *        {@code null}; sorted by {@code (namespace, value)} — deterministic
 *        serialization (the domain {@code Set} flattens here; the apply side
 *        reconstructs the {@code Set})
 * @param createdAt the adoption timestamp, never {@code null}
 * @see EntityRegisteredEvent
 * @see EventTypes#DEVICE_REGISTERED
 */
@EventType(EventTypes.DEVICE_REGISTERED)
public record DeviceRegisteredEvent(
        Ulid deviceId,
        String deviceSlug,
        String displayName,
        String manufacturer,
        String model,
        String serialNumber,
        String firmwareVersion,
        String hardwareVersion,
        String integrationId,
        Ulid areaId,
        Ulid viaDeviceId,
        List<String> labels,
        List<HardwareIdentifierRef> hardwareIdentifiers,
        Instant createdAt
) implements DomainEvent {

    /**
     * Validates required components, defensively copies {@code labels}
     * (order-preserving), and sorts {@code hardwareIdentifiers} by
     * {@code (namespace, value)} so serialization is deterministic and replay
     * equality never depends on source-set iteration order.
     *
     * @throws NullPointerException if a required component is {@code null}
     */
    public DeviceRegisteredEvent {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(deviceSlug, "deviceSlug must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(manufacturer, "manufacturer must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(integrationId, "integrationId must not be null");
        Objects.requireNonNull(labels, "labels must not be null");
        Objects.requireNonNull(hardwareIdentifiers, "hardwareIdentifiers must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        labels = List.copyOf(labels);
        List<HardwareIdentifierRef> sorted = new ArrayList<>(hardwareIdentifiers);
        sorted.sort(Comparator.comparing(HardwareIdentifierRef::namespace)
                .thenComparing(HardwareIdentifierRef::value));
        hardwareIdentifiers = List.copyOf(sorted);
    }
}
