/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.platform.identity.DeviceId;

/**
 * Service for checking capability compatibility and transferring entities
 * between devices during a device replacement operation.
 *
 * <p>When a physical device fails and is replaced with a new unit, this
 * service determines whether the new device supports the same capabilities
 * and facilitates entity transfer to preserve automation rules and event history.</p>
 *
 * <p>Implementations must be safe for concurrent read access. Transfer
 * operations are serialized.</p>
 *
 * <p>Defined in Doc 02 §3.14, §8.1.</p>
 *
 * @see CapabilityCompatibilityReport
 * @since 1.0
 */
public interface DeviceReplacementService {

    /**
     * Checks capability compatibility between an old device and its proposed replacement.
     *
     * @param oldDeviceId the identifier of the device being replaced, never {@code null}
     * @param newDeviceId the identifier of the replacement device, never {@code null}
     * @return the compatibility report, never {@code null}
     * @throws IllegalArgumentException if either device does not exist
     */
    CapabilityCompatibilityReport checkCompatibility(DeviceId oldDeviceId, DeviceId newDeviceId);

    /**
     * Transfers entities from the old device to the new device.
     *
     * <p>If the compatibility report indicates capability losses, the
     * {@code userConfirmedLosses} parameter must be {@code true} to proceed.</p>
     *
     * @param oldDeviceId the identifier of the device being replaced, never {@code null}
     * @param newDeviceId the identifier of the replacement device, never {@code null}
     * @param userConfirmedLosses whether the user has confirmed acceptance of capability losses
     * @throws IllegalArgumentException if either device does not exist
     * @throws IllegalStateException if capability losses exist and {@code userConfirmedLosses} is {@code false}
     */
    void transferEntities(DeviceId oldDeviceId, DeviceId newDeviceId, boolean userConfirmedLosses);
}
