/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link EventType} annotation and its application to the core
 * {@link DomainEvent} payload records in this module.
 *
 * <p>The set of expected annotated records is declared explicitly via
 * {@link #EXPECTED_EVENT_RECORDS} rather than discovered by classpath scanning.
 * Classpath scanning is unreliable in a JPMS environment and an explicit list
 * documents the exact expected set — if a record is renamed, removed, or added,
 * these tests break loudly.
 */
@DisplayName("EventType annotation")
class EventTypeAnnotationTest {

    /**
     * The authoritative list of core {@link DomainEvent} payload record classes
     * that must carry {@link EventType}. Must contain exactly 22 entries.
     * {@link DegradedEvent} is deliberately absent — it is the fallback wrapper
     * and is never serialized under its own type discriminator.
     */
    private static final List<Class<? extends DomainEvent>> EXPECTED_EVENT_RECORDS = List.of(
            CommandIssuedEvent.class,
            CommandDispatchedEvent.class,
            CommandResultEvent.class,
            CommandConfirmationTimedOutEvent.class,
            StateReportedEvent.class,
            StateReportRejectedEvent.class,
            StateChangedEvent.class,
            StateConfirmedEvent.class,
            DeviceDiscoveredEvent.class,
            DeviceAdoptedEvent.class,
            DeviceRemovedEvent.class,
            AvailabilityChangedEvent.class,
            AutomationTriggeredEvent.class,
            AutomationCompletedEvent.class,
            PresenceSignalEvent.class,
            PresenceChangedEvent.class,
            SystemStartedEvent.class,
            SystemStoppedEvent.class,
            StoragePressureChangedEvent.class,
            ConfigChangedEvent.class,
            ConfigErrorEvent.class,
            TelemetrySummaryEvent.class);

    @Test
    @DisplayName("every DomainEvent record (except DegradedEvent) has @EventType")
    void allDomainEventImplementations_haveEventTypeAnnotation() {
        var missing = new ArrayList<String>();
        for (Class<? extends DomainEvent> cls : EXPECTED_EVENT_RECORDS) {
            if (cls.getAnnotation(EventType.class) == null) {
                missing.add(cls.getSimpleName());
            }
        }

        assertThat(missing)
                .as("records missing @EventType annotation: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("DegradedEvent does not have @EventType")
    void degradedEvent_doesNotHaveEventTypeAnnotation() {
        assertThat(DegradedEvent.class.getAnnotation(EventType.class)).isNull();
    }

    @Test
    @DisplayName("no two @EventType annotations share the same value")
    void noEventTypeAnnotation_hasDuplicateValues() {
        var valueToClass = new HashMap<String, String>();
        var duplicates = new ArrayList<String>();

        for (Class<? extends DomainEvent> cls : EXPECTED_EVENT_RECORDS) {
            EventType ann = cls.getAnnotation(EventType.class);
            if (ann == null) {
                continue; // covered by allDomainEventImplementations_haveEventTypeAnnotation
            }
            String value = ann.value();
            String previous = valueToClass.put(value, cls.getSimpleName());
            if (previous != null) {
                duplicates.add(
                        "value '" + value + "' on both " + previous + " and " + cls.getSimpleName());
            }
        }

        assertThat(duplicates)
                .as("duplicate @EventType values: %s", duplicates)
                .isEmpty();
    }

    @Test
    @DisplayName("every @EventType value matches an EventTypes constant")
    void allEventTypeValues_matchEventTypesConstants() throws IllegalAccessException {
        Set<String> validValues = new HashSet<>();
        for (Field f : EventTypes.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (Modifier.isPublic(mods)
                    && Modifier.isStatic(mods)
                    && Modifier.isFinal(mods)
                    && f.getType() == String.class) {
                validValues.add((String) f.get(null));
            }
        }

        var mismatches = new ArrayList<String>();
        for (Class<? extends DomainEvent> cls : EXPECTED_EVENT_RECORDS) {
            EventType ann = cls.getAnnotation(EventType.class);
            if (ann == null) {
                continue;
            }
            if (!validValues.contains(ann.value())) {
                mismatches.add(cls.getSimpleName() + " -> '" + ann.value() + "'");
            }
        }

        assertThat(mismatches)
                .as("@EventType values not found in EventTypes constants: %s", mismatches)
                .isEmpty();
    }

    @Test
    @DisplayName("exactly 22 core event records carry @EventType")
    void exactlyTwentyTwoAnnotatedRecords() {
        assertThat(EXPECTED_EVENT_RECORDS).hasSize(22);

        long annotatedCount = EXPECTED_EVENT_RECORDS.stream()
                .filter(cls -> cls.isRecord())
                .filter(cls -> DomainEvent.class.isAssignableFrom(cls))
                .filter(cls -> cls.getAnnotation(EventType.class) != null)
                .count();

        assertThat(annotatedCount).isEqualTo(22L);
    }

    @Test
    @DisplayName("@EventType annotation has RUNTIME retention")
    void eventTypeAnnotation_isRuntimeRetained() {
        Retention retention = EventType.class.getAnnotation(Retention.class);

        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    @DisplayName("@EventType annotation targets TYPE only")
    void eventTypeAnnotation_targetsTypeOnly() {
        Target target = EventType.class.getAnnotation(Target.class);

        assertThat(target).isNotNull();
        assertThat(Arrays.asList(target.value())).containsExactly(ElementType.TYPE);
    }
}
