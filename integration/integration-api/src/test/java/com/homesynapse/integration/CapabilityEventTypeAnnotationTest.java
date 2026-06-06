/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homesynapse.event.EventType;
import com.homesynapse.event.EventTypes;

/**
 * Tests for {@link EventType} application to the sealed {@link CapabilityEvent}
 * subtype records (AMD-59). Mirrors {@code IntegrationEventTypeAnnotationTest},
 * but pins the {@code capability.} dot-namespace.
 *
 * <p>The authoritative registrable set is declared explicitly via
 * {@link #EXPECTED_SUBTYPES} rather than discovered from the sealed
 * {@code permits} clause — adding, removing, or renaming a subtype breaks these
 * tests loudly, mirroring {@code IntegrationEvents.CAPABILITY_EVENT_CLASSES}.</p>
 */
@DisplayName("EventType annotation on CapabilityEvent subtypes")
class CapabilityEventTypeAnnotationTest {

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    CapabilityEventTypeAnnotationTest() {
        // Defaults are sufficient.
    }

    /**
     * The authoritative list of {@link CapabilityEvent} subtype record classes
     * that must carry {@link EventType}. Must contain exactly 2 entries, matching
     * the sealed {@code permits} clause and {@code CAPABILITY_EVENT_CLASSES}.
     */
    private static final List<Class<? extends CapabilityEvent>> EXPECTED_SUBTYPES = List.of(
            CapabilityAdded.class,
            CapabilityRemoved.class);

    @Test
    @DisplayName("every CapabilityEvent subtype has @EventType")
    void allSubtypes_haveEventTypeAnnotation() {
        var missing = new ArrayList<String>();
        for (Class<? extends CapabilityEvent> cls : EXPECTED_SUBTYPES) {
            if (cls.getAnnotation(EventType.class) == null) {
                missing.add(cls.getSimpleName());
            }
        }

        assertThat(missing)
                .as("subtypes missing @EventType annotation: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("sealed parent CapabilityEvent does not carry @EventType")
    void sealedParent_doesNotHaveAnnotation() {
        assertThat(CapabilityEvent.class.getAnnotation(EventType.class))
                .as("sealed parent interface must not carry @EventType — only concrete subtypes are serialized")
                .isNull();
    }

    @Test
    @DisplayName("no two subtype @EventType annotations share the same value")
    void annotationValues_areUnique() {
        var valueToClass = new HashMap<String, String>();
        var duplicates = new ArrayList<String>();

        for (Class<? extends CapabilityEvent> cls : EXPECTED_SUBTYPES) {
            EventType ann = cls.getAnnotation(EventType.class);
            if (ann == null) {
                continue;
            }
            String value = ann.value();
            String previous = valueToClass.put(value, cls.getSimpleName());
            if (previous != null) {
                duplicates.add(
                        "value '" + value + "' on both " + previous + " and " + cls.getSimpleName());
            }
        }

        assertThat(duplicates)
                .as("duplicate @EventType values across capability subtypes: %s", duplicates)
                .isEmpty();
    }

    @Test
    @DisplayName("every subtype @EventType value matches an EventTypes constant")
    void annotationValues_matchEventTypesConstants() throws IllegalAccessException {
        Set<String> validValues = collectEventTypesConstants();

        var mismatches = new ArrayList<String>();
        for (Class<? extends CapabilityEvent> cls : EXPECTED_SUBTYPES) {
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
    @DisplayName("subtype @EventType values use the capability. dot-namespace and do not collide with core")
    void annotationValues_useCapabilityPrefix() {
        var nonPrefixed = new ArrayList<String>();
        for (Class<? extends CapabilityEvent> cls : EXPECTED_SUBTYPES) {
            EventType ann = cls.getAnnotation(EventType.class);
            if (ann == null) {
                continue;
            }
            if (!ann.value().startsWith("capability.")) {
                nonPrefixed.add(cls.getSimpleName() + " -> '" + ann.value() + "'");
            }
        }

        assertThat(nonPrefixed)
                .as("capability subtype @EventType values must use the 'capability.' prefix to avoid collisions with core event types: %s",
                        nonPrefixed)
                .isEmpty();
    }

    @Test
    @DisplayName("exactly 2 CapabilityEvent subtypes carry @EventType")
    void exactSubtypeCount() {
        assertThat(EXPECTED_SUBTYPES).hasSize(2);

        long annotatedCount = EXPECTED_SUBTYPES.stream()
                .filter(Class::isRecord)
                .filter(CapabilityEvent.class::isAssignableFrom)
                .filter(cls -> cls.getAnnotation(EventType.class) != null)
                .count();

        assertThat(annotatedCount).isEqualTo(2L);
    }

    private static Set<String> collectEventTypesConstants() throws IllegalAccessException {
        Set<String> values = new HashSet<>();
        for (Field f : EventTypes.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (Modifier.isPublic(mods)
                    && Modifier.isStatic(mods)
                    && Modifier.isFinal(mods)
                    && f.getType() == String.class) {
                values.add((String) f.get(null));
            }
        }
        return values;
    }
}
