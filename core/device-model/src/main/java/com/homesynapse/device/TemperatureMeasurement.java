/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard read-only capability for ambient temperature measurement.
 *
 * <p>Provides a single float attribute ({@code temperature_c}) in degrees Celsius.
 * No commands — this is a read-only measurement capability. Confirmation is
 * {@link ConfirmationMode#DISABLED}.</p>
 *
 * <p>Capability ID: {@code "temperature_measurement"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "temperature_measurement"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable (empty for read-only)
 * @param confirmationPolicy the confirmation policy
 * @see HumidityMeasurement
 * @see EntityType#SENSOR
 * @since 1.0
 */
public record TemperatureMeasurement(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
