/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard capability for binary on/off control.
 *
 * <p>The most fundamental controllable capability. Provides a single boolean
 * attribute ({@code on}) and three commands ({@code turn_on}, {@code turn_off},
 * {@code toggle}). Confirmation uses {@link ConfirmationMode#EXACT_MATCH}
 * against the {@code on} attribute.</p>
 *
 * <p>Required by entity types: {@link EntityType#LIGHT}, {@link EntityType#SWITCH},
 * {@link EntityType#PLUG}.</p>
 *
 * <p>Capability ID: {@code "on_off"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "on_off"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable
 * @param confirmationPolicy the confirmation policy
 * @see Brightness
 * @see EntityType#LIGHT
 * @since 1.0
 */
public record OnOff(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
