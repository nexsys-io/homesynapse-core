/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.List;

/**
 * Result of checking capability compatibility between two devices
 * during a device replacement operation.
 *
 * <p>Produced by {@link DeviceReplacementService#checkCompatibility} to
 * indicate whether entities from an old device can be transferred to a
 * new device without capability loss. When capability losses exist,
 * {@code requiresUserConfirmation} is {@code true} and the transfer
 * must not proceed without explicit user approval.</p>
 *
 * <p>Defined in Doc 02 §3.14.</p>
 *
 * @param compatible whether the new device supports all capabilities of the old device
 * @param capabilityAdditions capability IDs gained by the replacement; unmodifiable
 * @param capabilityLosses capability IDs lost by the replacement; unmodifiable
 * @param requiresUserConfirmation whether user confirmation is required (true when capability losses exist)
 * @see DeviceReplacementService
 * @since 1.0
 */
public record CapabilityCompatibilityReport(
        boolean compatible,
        List<String> capabilityAdditions,
        List<String> capabilityLosses,
        boolean requiresUserConfirmation
) { }
