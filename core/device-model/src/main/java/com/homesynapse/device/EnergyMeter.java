/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

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
) implements Capability {

    /**
     * The declared expected report interval (Doc 03 §3.8's capability-based default,
     * IR-61), by THE DERIVATION RULE: a margin of 2 × the maximum interval the core's own
     * reporting contract configures for the governing attribute —
     * {@code ReportingConfigurator.METERING_ROWS} configures
     * {@code CurrentSummationDelivered} (0x0702) at 5–3600 s — so 2 × 3600 s = 7200 s.
     * A device honoring exactly what the core asked for is silent for up to the 3600-s
     * maximum while the register holds, so no default may be shorter than it; the margin 2
     * is the design number, never an observed cadence. Under the resolver's smallest-
     * interval rule it governs energy-only entities; a plug that also meters power reads
     * {@link PowerMeter}'s 1200 s.
     */
    static final Duration EXPECTED_REPORT_INTERVAL = Duration.ofSeconds(7200);

    /**
     * {@inheritDoc}
     *
     * @return {@link #EXPECTED_REPORT_INTERVAL}, 7200 s
     */
    @Override
    public Optional<Duration> expectedReportInterval() {
        return Optional.of(EXPECTED_REPORT_INTERVAL);
    }
}
