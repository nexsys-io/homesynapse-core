/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.homesynapse.platform.identity.Ulid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DeviceAdoptedEvent} — emitted when a discovered device is accepted into the system.
 */
@DisplayName("DeviceAdoptedEvent")
class DeviceAdoptedEventTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 1 field accessible after construction")
        void allFieldsAccessible() {
            var entityId = new Ulid(1L, 1L);
            var event = new DeviceAdoptedEvent(entityId);

            assertThat(event.entityId()).isEqualTo(entityId);
        }

        @Test
        @DisplayName("implements DomainEvent")
        void implementsDomainEvent() {
            var event = new DeviceAdoptedEvent(new Ulid(1L, 1L));
            assertThat(event).isInstanceOf(DomainEvent.class);
        }

        @Test
        @DisplayName("record has exactly 1 component")
        void exactlyOneField() {
            assertThat(DeviceAdoptedEvent.class.getRecordComponents()).hasSize(1);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Null validation")
    class NullValidationTests {

        @Test
        @DisplayName("null entityId throws NullPointerException")
        void nullEntityId() {
            assertThatNullPointerException().isThrownBy(() ->
                    new DeviceAdoptedEvent(null))
                    .withMessageContaining("entityId");
        }
    }

    // ── Equals / hashCode ────────────────────────────────────────────────

    @Test
    @DisplayName("identical DeviceAdoptedEvents are equal")
    void identicalEqual() {
        var entityId = new Ulid(1L, 1L);
        var a = new DeviceAdoptedEvent(entityId);
        var b = new DeviceAdoptedEvent(entityId);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("DeviceAdoptedEvents with different fields are not equal")
    void differentNotEqual() {
        var a = new DeviceAdoptedEvent(new Ulid(1L, 1L));
        var b = new DeviceAdoptedEvent(new Ulid(2L, 2L));
        assertThat(a).isNotEqualTo(b);
    }
}
