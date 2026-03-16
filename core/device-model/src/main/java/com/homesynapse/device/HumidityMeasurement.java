/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard read-only capability for relative humidity measurement.
 *
 * <p>Provides a single float attribute ({@code humidity_pct}, range 0–100) as
 * a percentage. No commands. Confirmation is {@link ConfirmationMode#DISABLED}.</p>
 *
 * <p>Capability ID: {@code "humidity_measurement"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "humidity_measurement"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable (empty for read-only)
 * @param confirmationPolicy the confirmation policy
 * @see TemperatureMeasurement
 * @see EntityType#SENSOR
 * @since 1.0
 */
public record HumidityMeasurement(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
