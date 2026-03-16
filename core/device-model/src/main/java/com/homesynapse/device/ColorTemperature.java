/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard capability for color temperature control (warm white to cool white).
 *
 * <p>Provides an integer attribute ({@code color_temp_kelvin}) and a
 * {@code set_color_temperature} command. Confirmation uses
 * {@link ConfirmationMode#TOLERANCE} with ±50K tolerance.</p>
 *
 * <p>Optional for entity type {@link EntityType#LIGHT}.</p>
 *
 * <p>Capability ID: {@code "color_temperature"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "color_temperature"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable
 * @param confirmationPolicy the confirmation policy
 * @see Brightness
 * @see OnOff
 * @since 1.0
 */
public record ColorTemperature(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
