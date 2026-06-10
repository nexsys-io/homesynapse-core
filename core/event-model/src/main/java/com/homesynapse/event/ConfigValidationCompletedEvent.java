/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Map;
import java.util.Objects;

/**
 * Event emitted after a configuration load/reload validation pass completes
 * (AMD-70).
 * <p>
 * Observability-only: no state projection consumes this event; the
 * configuration file remains the sole source of truth (AMD-70-INV-01,
 * INV-CE-01).
 * <p>
 * The payload is flattened to event-module-resident types per the AMD-70
 * type-residency rule: {@code severityCounts} keys are the configuration
 * module's {@code Severity.name()} strings ({@code "FATAL"}, {@code "ERROR"},
 * {@code "WARNING"}) carried as plain strings, so no
 * {@code com.homesynapse.config} type is referenced from this module (the
 * reverse {@code event -> config} JPMS edge would be a cycle).
 * {@code (configSchemaMajor, configSchemaMinor)} is the AMD-67 system
 * config-document schema pair.
 * <p>
 * Priority: DIAGNOSTIC
 * Doc 06 §3.6, AMD-70 §2.1
 */
@EventType(EventTypes.CONFIG_VALIDATION_COMPLETED)
public record ConfigValidationCompletedEvent(
		int configSchemaMajor,
		int configSchemaMinor,
		int issueCount,
		Map<String, Integer> severityCounts
) implements DomainEvent {

	/**
	 * Constructs a ConfigValidationCompletedEvent with validation.
	 *
	 * @param configSchemaMajor the config-document schema major version,
	 *                          {@code >= 1} (AMD-67)
	 * @param configSchemaMinor the config-document schema minor version,
	 *                          {@code >= 0} (AMD-67)
	 * @param issueCount        the total number of validation issues found,
	 *                          {@code >= 0}; equals the sum of
	 *                          {@code severityCounts} values
	 * @param severityCounts    issue counts keyed by severity name
	 *                          ({@code "FATAL"}, {@code "ERROR"},
	 *                          {@code "WARNING"}), not null; severities with
	 *                          zero issues may be omitted
	 */
	public ConfigValidationCompletedEvent {
		if (configSchemaMajor < 1) {
			throw new IllegalArgumentException(
					"configSchemaMajor must be >= 1: " + configSchemaMajor);
		}
		if (configSchemaMinor < 0) {
			throw new IllegalArgumentException(
					"configSchemaMinor must be >= 0: " + configSchemaMinor);
		}
		if (issueCount < 0) {
			throw new IllegalArgumentException(
					"issueCount must be >= 0: " + issueCount);
		}
		Objects.requireNonNull(severityCounts, "severityCounts cannot be null");
		severityCounts = Map.copyOf(severityCounts);
		int severitySum = severityCounts.values().stream()
				.mapToInt(Integer::intValue)
				.sum();
		if (severitySum != issueCount) {
			throw new IllegalArgumentException(
					"issueCount must equal the sum of severityCounts values: "
							+ "issueCount=" + issueCount + ", sum=" + severitySum);
		}
	}
}
