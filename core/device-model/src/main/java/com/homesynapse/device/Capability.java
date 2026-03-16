/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * The central abstraction for what a device can do in HomeSynapse.
 *
 * <p>A capability defines a typed behavioral contract: the attributes a device
 * reports, the commands it accepts, and the confirmation policy for verifying
 * command execution. Standard capabilities form a sealed set of 15 records
 * enabling exhaustive {@code switch} expressions for type-safe dispatch.
 * Runtime-registered capabilities are represented by {@link CustomCapability}.</p>
 *
 * <p>All returned maps are unmodifiable.</p>
 *
 * <p>Defined in Doc 02 §3.5, §3.9.</p>
 *
 * @see EntityType
 * @see CustomCapability
 * @see CapabilityInstance
 * @since 1.0
 */
public sealed interface Capability permits
        OnOff, Brightness, ColorTemperature,
        TemperatureMeasurement, HumidityMeasurement, IlluminanceMeasurement, PowerMeasurement,
        BinaryState, Contact, Motion, Occupancy,
        Battery, DeviceHealth,
        EnergyMeter, PowerMeter,
        CustomCapability {

    /**
     * Returns the unique identifier for this capability (e.g., "on_off", "brightness").
     *
     * @return the capability identifier, never {@code null}
     */
    String capabilityId();

    /**
     * Returns the schema version of this capability definition.
     *
     * @return the version number, starting at 1
     */
    int version();

    /**
     * Returns the namespace that owns this capability definition.
     *
     * <p>Standard capabilities use the {@code "core"} namespace. Custom capabilities
     * must use a different namespace.</p>
     *
     * @return the namespace, never {@code null}
     */
    String namespace();

    /**
     * Returns the attribute schemas defined by this capability, keyed by attribute key.
     *
     * @return an unmodifiable map of attribute key to schema, never {@code null}
     */
    Map<String, AttributeSchema> attributeSchemas();

    /**
     * Returns the command definitions supported by this capability, keyed by command type.
     *
     * @return an unmodifiable map of command type to definition, never {@code null}; empty for read-only capabilities
     */
    Map<String, CommandDefinition> commandDefinitions();

    /**
     * Returns the confirmation policy for this capability.
     *
     * @return the confirmation policy, never {@code null}
     */
    ConfirmationPolicy confirmationPolicy();
}
