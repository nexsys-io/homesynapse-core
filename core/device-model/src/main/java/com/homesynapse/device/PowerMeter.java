/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Standard read-only capability for detailed power monitoring with voltage and current.
 *
 * <p>Provides three float attributes: {@code power_w} (instantaneous power in watts),
 * {@code voltage_v} (voltage in volts, nullable), and {@code current_a} (current in
 * amperes, nullable). Distinguished from {@link PowerMeasurement} by the addition
 * of voltage and current attributes for more detailed electrical monitoring.</p>
 *
 * <p>No commands. Confirmation is {@link ConfirmationMode#DISABLED}.</p>
 *
 * <p>Capability ID: {@code "power_meter"}. Defined in Doc 02 §3.6.</p>
 *
 * @param capabilityId the capability identifier, always {@code "power_meter"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable (empty for read-only)
 * @param confirmationPolicy the confirmation policy
 * @see EnergyMeter
 * @see PowerMeasurement
 * @since 1.0
 */
public record PowerMeter(
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
     * {@code ReportingConfigurator.METERING_ROWS} configures {@code ActivePower} (0x0B04)
     * at 5–600 s — so 2 × 600 s = 1200 s. A device honoring exactly what the core asked
     * for is silent for up to the 600-s maximum under a steady load, so no default may be
     * shorter than it; the margin 2 is the design number, and an observed cadence (the
     * voltage and current chatter a firmware sends unasked) never sets it.
     */
    static final Duration EXPECTED_REPORT_INTERVAL = Duration.ofSeconds(1200);

    /**
     * {@inheritDoc}
     *
     * @return {@link #EXPECTED_REPORT_INTERVAL}, 1200 s
     */
    @Override
    public Optional<Duration> expectedReportInterval() {
        return Optional.of(EXPECTED_REPORT_INTERVAL);
    }
}
