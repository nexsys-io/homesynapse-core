/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventTypes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link EventCategoryMapping}.
 *
 * <p>Verifies the Doc 01 §4.4 classification table: every entry maps to the
 * documented category list, the fallback path returns {@code [SYSTEM]} for
 * unknown and integration-namespaced types, and the mapping count matches
 * the Glossary's enumerated production types (sanity-check against silent
 * entry loss in a future refactor).</p>
 */
@DisplayName("EventCategoryMapping")
final class EventCategoryMappingTest {

    /** Creates a new test instance. */
    EventCategoryMappingTest() {
        // Explicit no-arg constructor for -Xlint:all -Werror builds.
    }

    @Test
    @DisplayName("command_issued is classified as [DEVICE_STATE, AUTOMATION]")
    void commandIssued_isDeviceStateAndAutomation() {
        List<EventCategory> categories =
                EventCategoryMapping.categoriesFor(EventTypes.COMMAND_ISSUED);

        assertThat(categories)
                .as("primary category should be DEVICE_STATE per Doc 01 §4.4")
                .containsExactly(EventCategory.DEVICE_STATE, EventCategory.AUTOMATION);
    }

    @Test
    @DisplayName("state_changed is classified as [DEVICE_STATE]")
    void stateChanged_isDeviceStateOnly() {
        List<EventCategory> categories =
                EventCategoryMapping.categoriesFor(EventTypes.STATE_CHANGED);

        assertThat(categories).containsExactly(EventCategory.DEVICE_STATE);
    }

    @Test
    @DisplayName("presence_changed is classified as [PRESENCE]")
    void presenceChanged_isPresence() {
        List<EventCategory> categories =
                EventCategoryMapping.categoriesFor(EventTypes.PRESENCE_CHANGED);

        assertThat(categories).containsExactly(EventCategory.PRESENCE);
    }

    @Test
    @DisplayName("integration_health_changed is classified as [SYSTEM, DEVICE_HEALTH]")
    void integrationHealthChanged_isSystemAndDeviceHealth() {
        List<EventCategory> categories =
                EventCategoryMapping.categoriesFor(EventTypes.INTEGRATION_HEALTH_CHANGED);

        assertThat(categories)
                .containsExactly(EventCategory.SYSTEM, EventCategory.DEVICE_HEALTH);
    }

    @Test
    @DisplayName("unknown eventType falls back to [SYSTEM]")
    void unknownEventType_fallsBackToSystem() {
        // Intentionally not present in Doc 01 §4.4.
        List<EventCategory> categories =
                EventCategoryMapping.categoriesFor("totally_unknown_event_type");

        assertThat(categories)
                .as("unknown types must fall back to the most conservative "
                        + "(non-privacy-sensitive) category per INV-PD-07")
                .containsExactly(EventCategory.SYSTEM);
    }

    @Test
    @DisplayName("integration-namespaced eventType (dotted) falls back to [SYSTEM]")
    void integrationNamespacedType_fallsBackToSystem() {
        // Runtime-registered integration types use the {integration}.{type}
        // namespace convention and are deliberately absent from the compile-time
        // table — the fallback guarantees they can be published safely.
        List<EventCategory> categories =
                EventCategoryMapping.categoriesFor("zigbee.device_announce");

        assertThat(categories).containsExactly(EventCategory.SYSTEM);
    }

    @Test
    @DisplayName("null eventType throws NullPointerException with a helpful message")
    void nullEventType_throwsNpe() {
        assertThatNullPointerException()
                .isThrownBy(() -> EventCategoryMapping.categoriesFor(null))
                .withMessageContaining("eventType");
    }

    @Test
    @DisplayName("returned list is unmodifiable")
    void returnedList_isUnmodifiable() {
        List<EventCategory> categories =
                EventCategoryMapping.categoriesFor(EventTypes.COMMAND_ISSUED);

        assertThat(categories).isUnmodifiable();
    }

    @Test
    @DisplayName("explicit mapping count matches the Doc 01 §4.4 enumeration")
    void explicitMappingCount_matchesDocEnumeration() {
        // Doc 01 §4.4 enumerates 27 production event types (22 core + 5
        // integration lifecycle), matching the size of
        // AllEventClasses.ALL_EVENTS. Holding this as a hard assertion makes
        // silent entry removal from the table a test failure rather than a
        // runtime fallback surprise.
        assertThat(EventCategoryMapping.explicitMappingCount())
                .as("explicit mapping count — update Doc 01 §4.4 and this "
                        + "assertion together if a new production event type is added")
                .isEqualTo(27);
    }
}
