/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard read-only capability for instantaneous power measurement.
 *
 * <p>Provides a single float attribute ({@code power_w}) in watts. This is
 * a read-only instantaneous power reading, distinct from {@link PowerMeter}
 * which adds voltage and current attributes.</p>
 *
 * <p>No commands. Confirmation is {@link ConfirmationMode#DISABLED}.</p>
 *
 * <p>Capability ID: {@code "power_measurement"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "power_measurement"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable (empty for read-only)
 * @param confirmationPolicy the confirmation policy
 * @see PowerMeter
 * @see EnergyMeter
 * @see EntityType#SENSOR
 * @since 1.0
 */
public record PowerMeasurement(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
