/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventType;

import java.util.List;

/**
 * Canonical roster of the event classes contributed by the
 * {@code com.homesynapse.integration} module — the per-module event-class manifests
 * consumed by the composition root (M3.6c).
 *
 * <p>The composition root aggregates {@link #LIFECYCLE_EVENT_CLASSES} and
 * {@link #CAPABILITY_EVENT_CLASSES} with
 * {@code EventTypes.CORE_PRODUCTION_EVENT_CLASSES} (from {@code com.homesynapse.event})
 * using {@link java.util.stream.Stream#concat} to build the {@code EventTypeRegistry} at
 * startup. Per DECIDE-04, this explicit aggregation is the only sanctioned discovery
 * mechanism — classpath scanning and {@code ServiceLoader} are banned and enforced by
 * ArchUnit Rule 3 ({@code noServiceLoader}).
 *
 * <p>Every entry below is annotated with {@link EventType} and registered with the
 * {@code EventTypeRegistry}. The sealed parents {@link IntegrationLifecycleEvent} and
 * {@link CapabilityEvent} are deliberately excluded — only concrete subtypes are
 * serialized through {@code EventPayloadCodec}.
 *
 * <p><strong>Forcing function:</strong> adding a new lifecycle or capability event subtype
 * requires editing the corresponding list here. {@code EventTypeRegistry} construction will
 * fail loudly at startup if a class in a list lacks {@link EventType}, and the upstream
 * annotation tests ({@code IntegrationEventTypeAnnotationTest.EXPECTED_SUBTYPES},
 * {@code CapabilityEventTypeAnnotationTest.EXPECTED_SUBTYPES}) must be updated in the same
 * change.
 *
 * <p>Closes Q3 of the M3.6 gap-closure research: per-module event-class manifests are
 * the DECIDE-04-compliant alternative to {@code ServiceLoader}.
 *
 * @see IntegrationLifecycleEvent
 * @see CapabilityEvent
 * @see EventType
 * @see DomainEvent
 */
public final class IntegrationEvents {

	/**
	 * Canonical, ordered list of the 10 concrete {@link IntegrationLifecycleEvent}
	 * subtypes shipped by this module (the original five plus the five added by AMD-58).
	 * {@link java.util.List#of(Object...)} returns an immutable list (JEP 269); the field
	 * is intentionally exposed directly rather than through a defensive copy.
	 */
	public static final List<Class<? extends DomainEvent>> LIFECYCLE_EVENT_CLASSES =
			List.of(
					IntegrationStarted.class,
					IntegrationStopped.class,
					IntegrationHealthChanged.class,
					IntegrationRestarted.class,
					IntegrationResourceExceeded.class,
					IntegrationConfigUpdated.class,
					IntegrationOptionsUpdated.class,
					IntegrationReauthRequired.class,
					IntegrationReauthCompleted.class,
					IntegrationMigrationCompleted.class);

	/**
	 * Canonical, ordered list of the 2 concrete {@link CapabilityEvent} subtypes shipped
	 * by this module (AMD-59). These are a separate manifest from the lifecycle list — the
	 * lifecycle hierarchy is semantically distinct from capability changes — but both are
	 * aggregated identically by the composition root. {@link java.util.List#of(Object...)}
	 * returns an immutable list (JEP 269); the field is intentionally exposed directly
	 * rather than through a defensive copy.
	 */
	public static final List<Class<? extends DomainEvent>> CAPABILITY_EVENT_CLASSES =
			List.of(
					CapabilityAdded.class,
					CapabilityRemoved.class);

	private IntegrationEvents() {
		// Utility class: non-instantiable
	}
}
