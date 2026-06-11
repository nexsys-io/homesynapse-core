/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.util.Objects;

/**
 * Event emitted per configuration section actually changed by a reload
 * (AMD-70, M6.4).
 * <p>
 * Observability-only: no state projection consumes this event; the
 * configuration file remains the sole source of truth (AMD-70-INV-01,
 * INV-CE-01). One event is published per CHANGED section, after the AMD-66
 * listener classification completes, on the same virtual thread that
 * completes the reload (AMD-70 §4).
 * <p>
 * The payload is flattened to {@code java.base} types per the AMD-70
 * type-residency rule (E70-1): {@code appliedClassification} is the
 * configuration module's {@code ReloadClassification.name()} string
 * ({@code "HOT"}, {@code "INTEGRATION_RESTART"}, {@code "PROCESS_RESTART"})
 * carried as a plain string, and the {@code ReloadResult} is consumed to
 * derive {@code changeCount}/{@code issueCount} — no
 * {@code com.homesynapse.config} type is referenced from this module (the
 * reverse {@code event -> config} JPMS edge would be a cycle). Per Doc 06
 * §12.4 the payload carries section names, counts, and the classification —
 * never configuration values.
 * <p>
 * Priority: DIAGNOSTIC
 * Doc 06 §3.3, AMD-70 §2.1
 */
@EventType(EventTypes.CONFIG_SECTION_RELOADED)
public record ConfigSectionReloadedEvent(
		String sectionPath,
		int changeCount,
		int issueCount,
		String appliedClassification
) implements DomainEvent {

	/**
	 * Constructs a ConfigSectionReloadedEvent with validation.
	 *
	 * @param sectionPath           the dotted path of the changed section,
	 *                              not null or blank
	 * @param changeCount           the number of key-level changes in this
	 *                              section, {@code >= 1} (only changed
	 *                              sections publish)
	 * @param issueCount            the number of warning-level issues the
	 *                              reload pass reported, {@code >= 0}
	 * @param appliedClassification the section's applied reload
	 *                              classification name ({@code "HOT"},
	 *                              {@code "INTEGRATION_RESTART"},
	 *                              {@code "PROCESS_RESTART"}), not null or
	 *                              blank
	 */
	public ConfigSectionReloadedEvent {
		Objects.requireNonNull(sectionPath, "sectionPath cannot be null");
		if (sectionPath.isBlank()) {
			throw new IllegalArgumentException("sectionPath cannot be blank");
		}
		if (changeCount < 1) {
			throw new IllegalArgumentException(
					"changeCount must be >= 1: " + changeCount);
		}
		if (issueCount < 0) {
			throw new IllegalArgumentException(
					"issueCount must be >= 0: " + issueCount);
		}
		Objects.requireNonNull(appliedClassification,
				"appliedClassification cannot be null");
		if (appliedClassification.isBlank()) {
			throw new IllegalArgumentException(
					"appliedClassification cannot be blank");
		}
	}
}
