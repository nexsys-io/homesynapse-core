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
 * Tests for {@link EventType} annotation application to the sealed
 * {@link IntegrationLifecycleEvent} subtype records (M2.i).
 *
 * <p>The set of expected annotated records is declared explicitly via
 * {@link #EXPECTED_SUBTYPES} rather than discovered by reflection on the
 * sealed {@code permits} clause. An explicit list documents the exact
 * expected set — if a subtype is renamed, removed, or added, these tests
 * break loudly.</p>
 *
 * <p>This mirrors the approach used by {@code EventTypeAnnotationTest} in
 * {@code core/event-model}. The annotation mechanism is shared across JPMS
 * modules because {@link IntegrationLifecycleEvent} extends
 * {@code DomainEvent} from a different module (see AMD-33).</p>
 */
@DisplayName("EventType annotation on IntegrationLifecycleEvent subtypes")
class IntegrationEventTypeAnnotationTest {

    /**
     * Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}.
     */
    IntegrationEventTypeAnnotationTest() {
        // Defaults are sufficient.
    }

    /**
     * The authoritative list of {@link IntegrationLifecycleEvent} subtype
     * record classes that must carry {@link EventType}. Must contain exactly
     * 5 entries, matching the sealed {@code permits} clause.
     */
    private static final List<Class<? extends IntegrationLifecycleEvent>> EXPECTED_SUBTYPES = List.of(
            IntegrationStarted.class,
            IntegrationStopped.class,
            IntegrationHealthChanged.class,
            IntegrationRestarted.class,
            IntegrationResourceExceeded.class);

    @Test
    @DisplayName("every IntegrationLifecycleEvent subtype has @EventType")
    void allSubtypes_haveEventTypeAnnotation() {
        var missing = new ArrayList<String>();
        for (Class<? extends IntegrationLifecycleEvent> cls : EXPECTED_SUBTYPES) {
            if (cls.getAnnotation(EventType.class) == null) {
                missing.add(cls.getSimpleName());
            }
        }

        assertThat(missing)
                .as("subtypes missing @EventType annotation: %s", missing)
                .isEmpty();
    }

    @Test
    @DisplayName("sealed parent IntegrationLifecycleEvent does not carry @EventType")
    void sealedParent_doesNotHaveAnnotation() {
        assertThat(IntegrationLifecycleEvent.class.getAnnotation(EventType.class))
                .as("sealed parent interface must not carry @EventType — only concrete subtypes are serialized")
                .isNull();
    }

    @Test
    @DisplayName("no two subtype @EventType annotations share the same value")
    void annotationValues_areUnique() {
        var valueToClass = new HashMap<String, String>();
        var duplicates = new ArrayList<String>();

        for (Class<? extends IntegrationLifecycleEvent> cls : EXPECTED_SUBTYPES) {
            EventType ann = cls.getAnnotation(EventType.class);
            if (ann == null) {
                continue; // covered by allSubtypes_haveEventTypeAnnotation
            }
            String value = ann.value();
            String previous = valueToClass.put(value, cls.getSimpleName());
            if (previous != null) {
                duplicates.add(
                        "value '" + value + "' on both " + previous + " and " + cls.getSimpleName());
            }
        }

        assertThat(duplicates)
                .as("duplicate @EventType values across integration subtypes: %s", duplicates)
                .isEmpty();
    }

    @Test
    @DisplayName("every subtype @EventType value matches an EventTypes constant")
    void annotationValues_matchEventTypesConstants() throws IllegalAccessException {
        Set<String> validValues = collectEventTypesConstants();

        var mismatches = new ArrayList<String>();
        for (Class<? extends IntegrationLifecycleEvent> cls : EXPECTED_SUBTYPES) {
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
    @DisplayName("subtype @EventType values use the integration_ prefix")
    void annotationValues_doNotCollideWithCoreEvents() {
        var nonPrefixed = new ArrayList<String>();
        for (Class<? extends IntegrationLifecycleEvent> cls : EXPECTED_SUBTYPES) {
            EventType ann = cls.getAnnotation(EventType.class);
            if (ann == null) {
                continue;
            }
            if (!ann.value().startsWith("integration_")) {
                nonPrefixed.add(cls.getSimpleName() + " -> '" + ann.value() + "'");
            }
        }

        assertThat(nonPrefixed)
                .as("integration subtype @EventType values must use the 'integration_' prefix to avoid collisions with core event types: %s",
                        nonPrefixed)
                .isEmpty();
    }

    @Test
    @DisplayName("exactly 5 IntegrationLifecycleEvent subtypes carry @EventType")
    void exactSubtypeCount() {
        assertThat(EXPECTED_SUBTYPES).hasSize(5);

        long annotatedCount = EXPECTED_SUBTYPES.stream()
                .filter(Class::isRecord)
                .filter(IntegrationLifecycleEvent.class::isAssignableFrom)
                .filter(cls -> cls.getAnnotation(EventType.class) != null)
                .count();

        assertThat(annotatedCount).isEqualTo(5L);
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
