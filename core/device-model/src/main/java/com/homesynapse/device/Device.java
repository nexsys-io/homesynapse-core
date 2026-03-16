/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.IntegrationId;

import java.time.Instant;
import java.util.List;

/**
 * Represents a physical device managed by HomeSynapse.
 *
 * <p>A device is a container for one or more {@link Entity} instances — the
 * functional units that expose capabilities. The device record carries
 * hardware metadata (manufacturer, model, firmware), integration ownership,
 * physical location (area), and protocol-level identifiers used for
 * discovery deduplication.</p>
 *
 * <p>Defined in Doc 02 §4.1.</p>
 *
 * @param deviceId the unique identifier for this device, never {@code null}
 * @param deviceSlug a URL-safe human-readable slug, never {@code null}
 * @param displayName the user-facing display name, never {@code null}
 * @param manufacturer the device manufacturer name, never {@code null}
 * @param model the device model identifier, never {@code null}
 * @param serialNumber the device serial number, {@code null} if not provided by the integration
 * @param firmwareVersion the current firmware version string, {@code null} if not reported; mutable via firmware updates
 * @param hardwareVersion the hardware revision string, {@code null} if not provided by the integration
 * @param integrationId the integration that manages this device, never {@code null}
 * @param areaId the area this device is assigned to, {@code null} if unassigned
 * @param viaDeviceId the parent device for router/gateway topologies, {@code null} if directly connected
 * @param labels user-assigned classification labels; unmodifiable
 * @param hardwareIdentifiers protocol-level identifiers for discovery deduplication; unmodifiable
 * @param createdAt the timestamp when this device was adopted, never {@code null}
 * @see Entity
 * @see DeviceRegistry
 * @see HardwareIdentifier
 * @since 1.0
 */
public record Device(
        DeviceId deviceId,
        String deviceSlug,
        String displayName,
        String manufacturer,
        String model,
        String serialNumber,
        String firmwareVersion,
        String hardwareVersion,
        IntegrationId integrationId,
        AreaId areaId,
        DeviceId viaDeviceId,
        List<String> labels,
        List<HardwareIdentifier> hardwareIdentifiers,
        Instant createdAt
) { }
