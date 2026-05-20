/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventType;

import java.util.List;

/**
 * Canonical roster of the {@link IntegrationLifecycleEvent} subtypes contributed by the
 * {@code com.homesynapse.integration} module — the per-module event-class manifest
 * consumed by the composition root (M3.6c).
 *
 * <p>The composition root aggregates {@link #LIFECYCLE_EVENT_CLASSES} with
 * {@code EventTypes.CORE_PRODUCTION_EVENT_CLASSES} (from {@code com.homesynapse.event})
 * using {@link java.util.stream.Stream#concat} to build the {@code EventTypeRegistry} at
 * startup. Per DECIDE-04, this explicit aggregation is the only sanctioned discovery
 * mechanism — classpath scanning and {@code ServiceLoader} are banned and enforced by
 * ArchUnit Rule 3 ({@code noServiceLoader}).
 *
 * <p>Every entry below is annotated with {@link EventType} and registered with the
 * {@code EventTypeRegistry}. The sealed parent {@link IntegrationLifecycleEvent} is
 * deliberately excluded — only concrete subtypes are serialized through
 * {@code EventPayloadCodec}.
 *
 * <p><strong>Forcing function:</strong> adding a new lifecycle event subtype requires
 * editing this list. {@code EventTypeRegistry} construction will fail loudly at startup
 * if a class in the list lacks {@link EventType}, and the upstream
 * {@code IntegrationEventTypeAnnotationTest.EXPECTED_SUBTYPES} list must be updated in
 * the same change.
 *
 * <p>Closes Q3 of the M3.6 gap-closure research: per-module event-class manifests are
 * the DECIDE-04-compliant alternative to {@code ServiceLoader}.
 *
 * @see IntegrationLifecycleEvent
 * @see EventType
 * @see DomainEvent
 */
public final class IntegrationEvents {

	/**
	 * Canonical, ordered list of the 5 concrete {@link IntegrationLifecycleEvent}
	 * subtypes shipped by this module. {@link java.util.List#of(Object...)} returns an
	 * immutable list (JEP 269); the field is intentionally exposed directly rather than
	 * through a defensive copy.
	 */
	public static final List<Class<? extends DomainEvent>> LIFECYCLE_EVENT_CLASSES =
			List.of(
					IntegrationStarted.class,
					IntegrationStopped.class,
					IntegrationHealthChanged.class,
					IntegrationRestarted.class,
					IntegrationResourceExceeded.class);

	private IntegrationEvents() {
		// Utility class: non-instantiable
	}
}
