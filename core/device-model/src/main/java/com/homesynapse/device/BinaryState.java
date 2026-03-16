/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard read-only capability for a generic binary state indicator.
 *
 * <p>Provides a single boolean attribute ({@code active}). Used for binary
 * sensors that don't fit the more specific {@link Contact}, {@link Motion},
 * or {@link Occupancy} capabilities.</p>
 *
 * <p>No commands. Confirmation is {@link ConfirmationMode#DISABLED}.</p>
 *
 * <p>Capability ID: {@code "binary_state"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "binary_state"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable (empty for read-only)
 * @param confirmationPolicy the confirmation policy
 * @see Contact
 * @see Motion
 * @see EntityType#BINARY_SENSOR
 * @since 1.0
 */
public record BinaryState(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
