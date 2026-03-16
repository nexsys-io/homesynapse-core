/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard capability for brightness level control.
 *
 * <p>Provides an integer attribute ({@code brightness}, range 0–100) and a
 * {@code set_brightness} command. Confirmation uses {@link ConfirmationMode#TOLERANCE}
 * with ±2 tolerance to account for device rounding.</p>
 *
 * <p>Optional for entity type {@link EntityType#LIGHT}. Typically paired with
 * {@link OnOff}.</p>
 *
 * <p>Capability ID: {@code "brightness"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "brightness"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable
 * @param confirmationPolicy the confirmation policy
 * @see OnOff
 * @see ColorTemperature
 * @since 1.0
 */
public record Brightness(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
