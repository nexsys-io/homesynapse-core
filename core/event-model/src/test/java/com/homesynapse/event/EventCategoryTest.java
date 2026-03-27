/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EventCategory} — consent-scope categories for event classification.
 */
@DisplayName("EventCategory")
class EventCategoryTest {

    // ── Enum values ──────────────────────────────────────────────────────

    @Test
    @DisplayName("exactly 8 enum values present")
    void exactlyEightValues() {
        assertThat(EventCategory.values()).hasSize(8);
    }

    @Test
    @DisplayName("all 8 expected values exist")
    void allExpectedValuesExist() {
        assertThat(EventCategory.values()).containsExactly(
                EventCategory.DEVICE_STATE,
                EventCategory.ENERGY,
                EventCategory.PRESENCE,
                EventCategory.ENVIRONMENTAL,
                EventCategory.SECURITY,
                EventCategory.AUTOMATION,
                EventCategory.DEVICE_HEALTH,
                EventCategory.SYSTEM
        );
    }

    // ── wireValue ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("wireValue()")
    class WireValueTests {

        @Test
        @DisplayName("each value has a non-null wireValue")
        void wireValuesNonNull() {
            for (EventCategory category : EventCategory.values()) {
                assertThat(category.wireValue())
                        .as("wireValue for %s", category)
                        .isNotNull()
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("DEVICE_STATE wireValue is device_state")
        void deviceStateWireValue() {
            assertThat(EventCategory.DEVICE_STATE.wireValue()).isEqualTo("device_state");
        }

        @Test
        @DisplayName("ENERGY wireValue is energy")
        void energyWireValue() {
            assertThat(EventCategory.ENERGY.wireValue()).isEqualTo("energy");
        }

        @Test
        @DisplayName("SYSTEM wireValue is system")
        void systemWireValue() {
            assertThat(EventCategory.SYSTEM.wireValue()).isEqualTo("system");
        }
    }

    // ── fromWireValue ────────────────────────────────────────────────────

    @Nested
    @DisplayName("fromWireValue()")
    class FromWireValueTests {

        @Test
        @DisplayName("round-trip: wireValue → fromWireValue returns same enum value")
        void roundTrip() {
            for (EventCategory category : EventCategory.values()) {
                assertThat(EventCategory.fromWireValue(category.wireValue()))
                        .isEqualTo(category);
            }
        }

        @Test
        @DisplayName("unknown wire value throws IllegalArgumentException")
        void unknownWireValueThrows() {
            assertThatThrownBy(() -> EventCategory.fromWireValue("nonexistent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nonexistent");
        }
    }

    // ── List construction semantics (used in EventEnvelope) ──────────────

    @Nested
    @DisplayName("List construction semantics")
    class ListConstructionTests {

        @Test
        @DisplayName("duplicate categories are removed while preserving order via LinkedHashSet")
        void deduplication() {
            var withDuplicates = List.of(
                    EventCategory.DEVICE_STATE,
                    EventCategory.ENERGY,
                    EventCategory.DEVICE_STATE,
                    EventCategory.ENERGY);

            var deduped = List.copyOf(new LinkedHashSet<>(withDuplicates));

            assertThat(deduped).containsExactly(
                    EventCategory.DEVICE_STATE,
                    EventCategory.ENERGY);
        }

        @Test
        @DisplayName("List.copyOf produces immutable list")
        void immutableList() {
            var categories = List.copyOf(List.of(EventCategory.DEVICE_STATE));

            assertThatThrownBy(() -> categories.add(EventCategory.ENERGY))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
