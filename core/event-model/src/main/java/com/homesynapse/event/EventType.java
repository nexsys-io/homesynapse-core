/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link DomainEvent} record with its canonical event type string for
 * registry-based deserialization.
 *
 * <p>HomeSynapse persists events with a plain-text {@code eventType} discriminator
 * column (Doc 01 §4.2). At read time the persistence layer must map that string
 * back to the concrete Java record class so the JSON payload can be deserialized
 * into the correct type. Rather than rely on Jackson's {@code @JsonTypeInfo}
 * (banned by ArchUnit Rule 7 {@code NO_JSON_TYPE_INFO_IN_EVENTS}), HomeSynapse
 * uses this custom annotation together with an {@code EventTypeRegistry} that
 * scans annotated records at startup and builds the {@code String → Class}
 * mapping. See LTD-19 / DECIDE-M2-01 (amended by AMD-33).
 *
 * <p>Every core domain event payload record in this package carries
 * {@code @EventType}, with the sole exception of {@link DegradedEvent}, which
 * is a fallback wrapper for events whose type is unknown or whose payload
 * failed to upcast. A {@code DegradedEvent} is never directly serialized under
 * its own type discriminator — it wraps an already-failed event — so it must
 * not be registered.
 *
 * <p>The annotation {@code value()} must exactly match one of the canonical
 * string constants declared in {@link EventTypes}. Using a literal string is
 * not permitted; the corresponding {@code EventTypes} constant must always be
 * referenced.
 *
 * <p><strong>Example:</strong>
 * <pre>{@code
 * @EventType(EventTypes.COMMAND_ISSUED)
 * public record CommandIssuedEvent(
 *         Ulid targetEntityRef,
 *         String commandType,
 *         // ...
 * ) implements DomainEvent {
 *     // ...
 * }
 * }</pre>
 *
 * <p><strong>Retention:</strong> {@link RetentionPolicy#RUNTIME}. The
 * {@code EventTypeRegistry} reads the annotation via reflection at startup.
 *
 * @see EventTypes
 * @see DomainEvent
 * @see DegradedEvent
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventType {

    /**
     * The canonical event type string for this {@link DomainEvent} implementation,
     * matching the corresponding {@link EventTypes} constant. Used by
     * {@code EventTypeRegistry} to map stored event type strings back to concrete
     * Java classes at deserialization time.
     *
     * @return the event type string (e.g., {@code "command_issued"})
     */
    String value();
}
