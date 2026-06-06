/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.device.Capability;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link CapabilityEvent} permit records (AMD-59): the
 * {@link CapabilityEvent} accessor contract, the derived
 * {@code CapabilityAdded.capabilityId()}, and compact-constructor null guards on
 * every component (including {@code CapabilityRemoved.reason}).
 */
@DisplayName("CapabilityEvent permits")
class CapabilityEventTest {

    private static final IntegrationId INTEGRATION_ID =
            IntegrationId.of(Ulid.parse("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
    private static final DeviceId DEVICE_ID =
            DeviceId.of(Ulid.parse("01BX5ZZKBKACTAV9WEVGEMMVRZ"));
    private static final EntityId ENTITY_ID =
            EntityId.of(Ulid.parse("01F8MECHZX3TBDSZ7XRADM79XE"));

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    CapabilityEventTest() {
        // Defaults are sufficient.
    }

    /** A command-less occupancy-derived instance (capabilityId == "occupancy"). */
    private static CapabilityInstance occupancyInstance() {
        Capability occupancy = StandardCapabilities.occupancy();
        return new CapabilityInstance(
                occupancy.capabilityId(),
                occupancy.version(),
                occupancy.namespace(),
                0,
                occupancy.attributeSchemas(),
                occupancy.commandDefinitions(),
                occupancy.confirmationPolicy());
    }

    @Nested
    @DisplayName("CapabilityAdded")
    class CapabilityAddedTests {

        /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
        CapabilityAddedTests() {
            // Defaults are sufficient.
        }

        @Test
        @DisplayName("exposes the CapabilityEvent accessors")
        void accessors() {
            CapabilityInstance instance = occupancyInstance();
            var event = new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, ENTITY_ID, instance);

            assertThat(event.integrationId()).isEqualTo(INTEGRATION_ID);
            assertThat(event.deviceId()).isEqualTo(DEVICE_ID);
            assertThat(event.entityId()).isEqualTo(ENTITY_ID);
            assertThat(event.instance()).isSameAs(instance);
        }

        @Test
        @DisplayName("capabilityId() derives from the carried instance")
        void capabilityIdDerivesFromInstance() {
            CapabilityInstance instance = occupancyInstance();
            var event = new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, ENTITY_ID, instance);

            assertThat(event.capabilityId()).isEqualTo(instance.capabilityId());
            assertThat(event.capabilityId()).isEqualTo("occupancy");
        }

        @Test
        @DisplayName("rejects null components")
        void nullComponents_throw() {
            CapabilityInstance instance = occupancyInstance();

            assertThatThrownBy(() -> new CapabilityAdded(null, DEVICE_ID, ENTITY_ID, instance))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("integrationId");
            assertThatThrownBy(() -> new CapabilityAdded(INTEGRATION_ID, null, ENTITY_ID, instance))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("deviceId");
            assertThatThrownBy(() -> new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, null, instance))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("entityId");
            assertThatThrownBy(() -> new CapabilityAdded(INTEGRATION_ID, DEVICE_ID, ENTITY_ID, null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("instance");
        }
    }

    @Nested
    @DisplayName("CapabilityRemoved")
    class CapabilityRemovedTests {

        /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
        CapabilityRemovedTests() {
            // Defaults are sufficient.
        }

        @Test
        @DisplayName("exposes the CapabilityEvent accessors and the reason")
        void accessors() {
            var event = new CapabilityRemoved(
                    INTEGRATION_ID, DEVICE_ID, ENTITY_ID, "occupancy",
                    CapabilityRemovalReason.TRANSIENT_LOSS);

            assertThat(event.integrationId()).isEqualTo(INTEGRATION_ID);
            assertThat(event.deviceId()).isEqualTo(DEVICE_ID);
            assertThat(event.entityId()).isEqualTo(ENTITY_ID);
            assertThat(event.capabilityId()).isEqualTo("occupancy");
            assertThat(event.reason()).isEqualTo(CapabilityRemovalReason.TRANSIENT_LOSS);
        }

        @Test
        @DisplayName("rejects null components, including reason")
        void nullComponents_throw() {
            assertThatThrownBy(() -> new CapabilityRemoved(
                    null, DEVICE_ID, ENTITY_ID, "occupancy", CapabilityRemovalReason.UNREGISTERED))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("integrationId");
            assertThatThrownBy(() -> new CapabilityRemoved(
                    INTEGRATION_ID, null, ENTITY_ID, "occupancy", CapabilityRemovalReason.UNREGISTERED))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("deviceId");
            assertThatThrownBy(() -> new CapabilityRemoved(
                    INTEGRATION_ID, DEVICE_ID, null, "occupancy", CapabilityRemovalReason.UNREGISTERED))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("entityId");
            assertThatThrownBy(() -> new CapabilityRemoved(
                    INTEGRATION_ID, DEVICE_ID, ENTITY_ID, null, CapabilityRemovalReason.UNREGISTERED))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("capabilityId");
            assertThatThrownBy(() -> new CapabilityRemoved(
                    INTEGRATION_ID, DEVICE_ID, ENTITY_ID, "occupancy", null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("reason");
        }
    }
}
