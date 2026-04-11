/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bidirectional registry mapping canonical {@code eventType} strings to concrete
 * {@link DomainEvent} record classes and back (LTD-19 / DECIDE-M2-01).
 *
 * <p>HomeSynapse persists events with a plain-text {@code eventType} discriminator
 * column alongside a JSON payload BLOB (Doc 01 §4.2, DECIDE-M2-06). At deserialization
 * time the persistence layer must map that string back to the concrete Java record
 * class so the JSON can be bound into the correct type. Rather than rely on
 * Jackson's {@code @JsonTypeInfo} (banned by ArchUnit Rule 7, see AMD-33), HomeSynapse
 * uses the custom {@link EventType} annotation and this registry.</p>
 *
 * <p>The registry is built at startup from an explicit list of annotated record
 * classes. Classpath scanning is avoided because it is unreliable under JPMS and
 * because an explicit manifest is a deliberate forcing function for code review —
 * adding a new event type requires editing the caller's class list. Fail-fast
 * validation catches missing annotations and duplicate type strings at construction
 * time rather than at first use.</p>
 *
 * <p><strong>Thread-safety:</strong> Immutable after construction. The internal
 * maps are wrapped in {@link Collections#unmodifiableMap(Map)} and no mutation
 * path exists. Safe for concurrent reads from any number of virtual threads
 * without synchronization.</p>
 *
 * <p><strong>Visibility:</strong> Package-private — internal infrastructure for
 * the persistence module. External consumers interact with the event store via
 * public interfaces that hide this type entirely.</p>
 *
 * @see EventType
 * @see EventPayloadCodec
 */
final class EventTypeRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(EventTypeRegistry.class);

    private final Map<String, Class<? extends DomainEvent>> stringToClass;
    private final Map<Class<? extends DomainEvent>, String> classToString;

    /**
     * Builds the bidirectional registry from an explicit list of event classes.
     *
     * <p>For each class in the list: reads the {@link EventType} annotation,
     * extracts its {@code value()}, and stores the mapping in both directions.
     * Validation is fail-fast — any error throws immediately with a descriptive
     * message identifying the offending class.</p>
     *
     * @param eventClasses the event classes to register; must be non-null and non-empty
     * @throws NullPointerException     if {@code eventClasses} is {@code null}
     * @throws IllegalArgumentException if {@code eventClasses} is empty, if any class
     *                                  lacks {@link EventType}, or if two classes
     *                                  share the same type string
     */
    EventTypeRegistry(List<Class<? extends DomainEvent>> eventClasses) {
        Objects.requireNonNull(eventClasses, "Event class list must not be null");
        if (eventClasses.isEmpty()) {
            throw new IllegalArgumentException("Event class list must not be null or empty");
        }

        Map<String, Class<? extends DomainEvent>> byString = new LinkedHashMap<>();
        Map<Class<? extends DomainEvent>, String> byClass = new HashMap<>();

        for (Class<? extends DomainEvent> clazz : eventClasses) {
            EventType annotation = clazz.getAnnotation(EventType.class);
            if (annotation == null) {
                throw new IllegalArgumentException(
                        "Event class has no @EventType annotation: " + clazz.getName());
            }
            String typeString = annotation.value();
            Class<? extends DomainEvent> existing = byString.get(typeString);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate event type string '" + typeString + "': "
                                + existing.getName() + " and " + clazz.getName());
            }
            byString.put(typeString, clazz);
            byClass.put(clazz, typeString);
            LOG.debug("Registered event type '{}' -> {}", typeString, clazz.getName());
        }

        this.stringToClass = Collections.unmodifiableMap(byString);
        this.classToString = Collections.unmodifiableMap(byClass);
        LOG.info("EventTypeRegistry initialized with {} event types", stringToClass.size());
    }

    /**
     * Returns the concrete event class for the given canonical type string.
     *
     * @param eventType the canonical event type string (e.g. {@code "command_issued"})
     * @return the concrete class, or {@link Optional#empty()} if the type is not registered
     */
    Optional<Class<? extends DomainEvent>> classFor(String eventType) {
        return Optional.ofNullable(stringToClass.get(eventType));
    }

    /**
     * Returns the canonical type string for the given event class.
     *
     * @param eventClass the event record class
     * @return the type string, or {@link Optional#empty()} if the class is not registered
     */
    Optional<String> typeFor(Class<? extends DomainEvent> eventClass) {
        return Optional.ofNullable(classToString.get(eventClass));
    }

    /**
     * Returns an unmodifiable set of all registered event type strings.
     *
     * @return the set of registered type strings, never {@code null}
     */
    Set<String> registeredTypes() {
        return stringToClass.keySet();
    }

    /**
     * Returns an unmodifiable set of all registered event classes.
     *
     * @return the set of registered event classes, never {@code null}
     */
    Set<Class<? extends DomainEvent>> registeredClasses() {
        return classToString.keySet();
    }

    /**
     * Returns the number of registered event types.
     *
     * @return the registration count
     */
    int size() {
        return stringToClass.size();
    }
}
