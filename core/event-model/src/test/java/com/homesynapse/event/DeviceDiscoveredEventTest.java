/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.Ulid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DeviceDiscoveredEvent} — emitted when a new device is discovered on the protocol network.
 */
@DisplayName("DeviceDiscoveredEvent")
class DeviceDiscoveredEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 4 fields accessible after construction")
        void allFieldsAccessible() {
            var integrationId = new Ulid(1L, 1L);
            var event = new DeviceDiscoveredEvent(
                    integrationId, "192.168.1.100", "Philips", "Hue");

            assertThat(event.integrationId()).isEqualTo(integrationId);
            assertThat(event.protocolAddress()).isEqualTo("192.168.1.100");
            assertThat(event.manufacturer()).isEqualTo("Philips");
            assertThat(event.model()).isEqualTo("Hue");
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new DeviceDiscoveredEvent(
                    new Ulid(1L, 1L), "192.168.1.100", "Philips", "Hue");
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 4 components")
        void exactlyFourFields() {
            assertThat(DeviceDiscoveredEvent.class.getRecordComponents()).hasSize(4);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null integrationId throws NullPointerException")
        void nullIntegrationId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DeviceDiscoveredEvent(null, "192.168.1.100", "Philips", "Hue"))
                    .withMessageContaining("integrationId");
        }

        @Test
        @DisplayName("null protocolAddress throws NullPointerException")
        void nullProtocolAddress() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DeviceDiscoveredEvent(new Ulid(1L, 1L), null, "Philips", "Hue"))
                    .withMessageContaining("protocolAddress");
        }

        @Test
        @DisplayName("null manufacturer throws NullPointerException")
        void nullManufacturer() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DeviceDiscoveredEvent(new Ulid(1L, 1L), "192.168.1.100", null, "Hue"))
                    .withMessageContaining("manufacturer");
        }

        @Test
        @DisplayName("null model throws NullPointerException")
        void nullModel() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DeviceDiscoveredEvent(new Ulid(1L, 1L), "192.168.1.100", "Philips", null))
                    .withMessageContaining("model");
        }
    }

    // ── Range validation ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("blank protocolAddress throws IllegalArgumentException")
        void blankProtocolAddress() {
            assertThatThrownBy(() -> new DeviceDiscoveredEvent(
                    new Ulid(1L, 1L), "  ", "Philips", "Hue"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("blank manufacturer throws IllegalArgumentException")
        void blankManufacturer() {
            assertThatThrownBy(() -> new DeviceDiscoveredEvent(
                    new Ulid(1L, 1L), "192.168.1.100", "  ", "Hue"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("blank model throws IllegalArgumentException")
        void blankModel() {
            assertThatThrownBy(() -> new DeviceDiscoveredEvent(
                    new Ulid(1L, 1L), "192.168.1.100", "Philips", "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical DeviceDiscoveredEvents are equal")
    void identicalEqual() {
        var integrationId = new Ulid(1L, 1L);
        var a = new DeviceDiscoveredEvent(integrationId, "192.168.1.100", "Philips", "Hue");
        var b = new DeviceDiscoveredEvent(integrationId, "192.168.1.100", "Philips", "Hue");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("DeviceDiscoveredEvents with different fields are not equal")
    void differentNotEqual() {
        var integrationId = new Ulid(1L, 1L);
        var a = new DeviceDiscoveredEvent(integrationId, "192.168.1.100", "Philips", "Hue");
        var b = new DeviceDiscoveredEvent(integrationId, "192.168.1.101", "Philips", "Hue");
        assertThat(a).isNotEqualTo(b);
    }
}
