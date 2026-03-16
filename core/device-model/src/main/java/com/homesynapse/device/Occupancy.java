/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard read-only capability for room or zone occupancy detection.
 *
 * <p>Provides a single boolean attribute ({@code occupied}): {@code true} when
 * the zone is occupied, {@code false} when vacant. Distinguished from
 * {@link Motion} in that occupancy may use multiple sensing strategies
 * (PIR, mmWave, acoustic) and represents sustained presence rather than
 * transient movement.</p>
 *
 * <p>No commands. Confirmation is {@link ConfirmationMode#DISABLED}.</p>
 *
 * <p>Capability ID: {@code "occupancy"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "occupancy"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable (empty for read-only)
 * @param confirmationPolicy the confirmation policy
 * @see Motion
 * @see EntityType#BINARY_SENSOR
 * @since 1.0
 */
public record Occupancy(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
