/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.DegradedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventType;
import com.homesynapse.event.EventTypes;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventTypeRegistry}.
 */
@DisplayName("EventTypeRegistry")
class EventTypeRegistryTest {

    @Test
    @DisplayName("constructor registers all 22 core event classes")
    void construct_withValidClasses_registersAll() {
        EventTypeRegistry registry = new EventTypeRegistry(AllEventClasses.CORE_EVENTS);

        assertThat(registry.size()).isEqualTo(22);
        for (Class<? extends DomainEvent> cls : AllEventClasses.CORE_EVENTS) {
            assertThat(registry.typeFor(cls)).isPresent();
            String typeString = registry.typeFor(cls).orElseThrow();
            assertThat(registry.classFor(typeString)).contains(cls);
        }
    }

    @Test
    @DisplayName("constructor registers all 27 core + integration classes")
    void construct_withCoreAndIntegration_registersAll() {
        EventTypeRegistry registry = new EventTypeRegistry(AllEventClasses.ALL_EVENTS);

        assertThat(registry.size()).isEqualTo(27);
    }

    @Test
    @DisplayName("classFor returns the concrete class for a known event type")
    void classFor_withKnownType_returnsClass() {
        EventTypeRegistry registry = new EventTypeRegistry(AllEventClasses.CORE_EVENTS);

        assertThat(registry.classFor(EventTypes.COMMAND_ISSUED))
                .contains(CommandIssuedEvent.class);
    }

    @Test
    @DisplayName("classFor returns empty for an unknown event type")
    void classFor_withUnknownType_returnsEmpty() {
        EventTypeRegistry registry = new EventTypeRegistry(AllEventClasses.CORE_EVENTS);

        assertThat(registry.classFor("nonexistent_event")).isEmpty();
    }

    @Test
    @DisplayName("typeFor returns the string for a registered class")
    void typeFor_withRegisteredClass_returnsType() {
        EventTypeRegistry registry = new EventTypeRegistry(AllEventClasses.CORE_EVENTS);

        assertThat(registry.typeFor(CommandIssuedEvent.class))
                .contains(EventTypes.COMMAND_ISSUED);
    }

    @Test
    @DisplayName("typeFor returns empty for an unregistered class (DegradedEvent)")
    void typeFor_withUnregisteredClass_returnsEmpty() {
        EventTypeRegistry registry = new EventTypeRegistry(AllEventClasses.CORE_EVENTS);

        assertThat(registry.typeFor(DegradedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("constructor throws when a class lacks @EventType")
    void construct_withMissingAnnotation_throwsIllegalArgument() {
        List<Class<? extends DomainEvent>> classes = List.of(
                CommandIssuedEvent.class, DegradedEvent.class);

        assertThatThrownBy(() -> new EventTypeRegistry(classes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no @EventType annotation")
                .hasMessageContaining(DegradedEvent.class.getName());
    }

    @Test
    @DisplayName("constructor throws when two classes share the same type string")
    void construct_withDuplicateType_throwsIllegalArgument() {
        List<Class<? extends DomainEvent>> classes = List.of(
                DuplicateTypeEventA.class, DuplicateTypeEventB.class);

        assertThatThrownBy(() -> new EventTypeRegistry(classes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate event type string")
                .hasMessageContaining(DuplicateTypeEventA.class.getName())
                .hasMessageContaining(DuplicateTypeEventB.class.getName());
    }

    @Test
    @DisplayName("constructor throws on empty class list")
    void construct_withEmptyList_throwsIllegalArgument() {
        assertThatThrownBy(() -> new EventTypeRegistry(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or empty");
    }

    @Test
    @DisplayName("constructor throws NullPointerException on null class list")
    void construct_withNullList_throwsNullPointerException() {
        assertThatThrownBy(() -> new EventTypeRegistry(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Event class list must not be null");
    }

    @Test
    @DisplayName("registeredTypes() returns an unmodifiable set")
    void registeredTypes_returnsUnmodifiableSet() {
        EventTypeRegistry registry = new EventTypeRegistry(AllEventClasses.CORE_EVENTS);

        Set<String> types = registry.registeredTypes();

        assertThat(types).hasSize(22);
        assertThatThrownBy(() -> types.add("new_type"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // Test-only event records for duplicate-type detection.

    @EventType("duplicate_test_type")
    record DuplicateTypeEventA(String field) implements DomainEvent {
    }

    @EventType("duplicate_test_type")
    record DuplicateTypeEventB(String field) implements DomainEvent {
    }
}
