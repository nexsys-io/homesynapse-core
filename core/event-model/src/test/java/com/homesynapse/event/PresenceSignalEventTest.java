/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PresenceSignalEvent} — event emitted for a raw presence signal
 * from an integration.
 */
@DisplayName("PresenceSignalEvent")
class PresenceSignalEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 3 fields accessible after construction")
        void allFieldsAccessible() {
            var event = new PresenceSignalEvent("wifi_probe", "router_1", "{\"rssi\":-45}");

            assertThat(event.signalType()).isEqualTo("wifi_probe");
            assertThat(event.signalSource()).isEqualTo("router_1");
            assertThat(event.signalData()).isEqualTo("{\"rssi\":-45}");
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new PresenceSignalEvent("ble_beacon", "beacon_1", "{}");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 3 components")
        void exactlyThreeFields() {
            assertThat(PresenceSignalEvent.class.getRecordComponents()).hasSize(3);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null signalType throws NullPointerException")
        void nullSignalType() {
            assertThatNullPointerException().isThrownBy(() ->
                    new PresenceSignalEvent(null, "source", "{}"))
                    .withMessageContaining("signalType");
        }

        @Test
        @DisplayName("null signalSource throws NullPointerException")
        void nullSignalSource() {
            assertThatNullPointerException().isThrownBy(() ->
                    new PresenceSignalEvent("wifi_probe", null, "{}"))
                    .withMessageContaining("signalSource");
        }

        @Test
        @DisplayName("null signalData throws NullPointerException")
        void nullSignalData() {
            assertThatNullPointerException().isThrownBy(() ->
                    new PresenceSignalEvent("wifi_probe", "source", null))
                    .withMessageContaining("signalData");
        }
    }

    // ── Blank validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Blank validation")
    class BlankValidationTests {

        @Test
        @DisplayName("blank signalType throws IllegalArgumentException")
        void blankSignalType() {
            assertThatThrownBy(() -> new PresenceSignalEvent("  ", "source", "{}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical PresenceSignalEvents are equal")
    void identicalEqual() {
        var a = new PresenceSignalEvent("wifi_probe", "router_1", "{}");
        var b = new PresenceSignalEvent("wifi_probe", "router_1", "{}");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("PresenceSignalEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new PresenceSignalEvent("wifi_probe", "router_1", "{}");
        var b = new PresenceSignalEvent("ble_beacon", "router_1", "{}");
        assertThat(a).isNotEqualTo(b);
    }
}
