/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard capability for cumulative energy metering.
 *
 * <p>Provides three attributes: {@code energy_wh} (float, cumulative energy in
 * watt-hours), {@code direction} (enum via {@link EnergyDirection} — import,
 * export, or bidirectional), and {@code cumulative} (boolean, indicating whether
 * this meter reports cumulative totals versus instantaneous snapshots). Supports
 * a {@code reset_meter} command to zero the cumulative counter where supported.
 * Confirmation uses {@link ConfirmationMode#EXACT_MATCH}.</p>
 *
 * <p>Required by entity type {@link EntityType#ENERGY_METER}.</p>
 *
 * <p>Capability ID: {@code "energy_meter"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "energy_meter"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable
 * @param confirmationPolicy the confirmation policy
 * @see PowerMeter
 * @see EnergyDirection
 * @see EntityType#ENERGY_METER
 * @since 1.0
 */
public record EnergyMeter(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }
