// HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link Entity} — atomic functional unit record.
 */
@DisplayName("Entity")
class EntityTest {

    private static final EntityId ENTITY_ID =
            EntityId.of(new Ulid(0x0192A3B4C5D6E7F0L, 0x0102030405060708L));
    private static final DeviceId DEVICE_ID =
            DeviceId.of(new Ulid(0x0192A3B4C5D6E7F1L, 0x0102030405060709L));
    private static final AreaId AREA_ID =
            AreaId.of(new Ulid(0x0192A3B4C5D6E7F2L, 0x010203040506070AL));
    private static final Instant CREATED_AT = Instant.parse("2026-01-15T12:00:00Z");

    private static final CapabilityInstance SAMPLE_CAPABILITY = new CapabilityInstance(
            "on_off", 1, "core", 0, Map.of(), Map.of(),
            new ConfirmationPolicy(ConfirmationMode.EXACT_MATCH, List.of("on"), null, 5000L));

    private static Entity fullEntity() {
        return new Entity(
                ENTITY_ID,
                "kitchen-bulb-light",
                EntityType.LIGHT,
                "Kitchen Bulb Light",
                DEVICE_ID,
                1,
                AREA_ID,
                true,
                List.of("lighting"),
                List.of(SAMPLE_CAPABILITY),
                CREATED_AT);
    }

    // -- Construction ---------------------------------------------------------

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("all 11 fields accessible after construction")
        void allFieldsAccessible() {
            Entity e = fullEntity();

            assertThat(e.entityId()).isEqualTo(ENTITY_ID);
            assertThat(e.entitySlug()).isEqualTo("kitchen-bulb-light");
            assertThat(e.entityType()).isEqualTo(EntityType.LIGHT);
            assertThat(e.displayName()).isEqualTo("Kitchen Bulb Light");
            assertThat(e.deviceId()).isEqualTo(DEVICE_ID);
            assertThat(e.endpointIndex()).isEqualTo(1);
            assertThat(e.areaId()).isEqualTo(AREA_ID);
            assertThat(e.enabled()).isTrue();
            assertThat(e.labels()).containsExactly("lighting");
            assertThat(e.capabilities()).hasSize(1);
            assertThat(e.createdAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("record has exactly 11 components")
        void exactlyElevenFields() {
            assertThat(Entity.class.getRecordComponents()).hasSize(11);
        }
    }

    // -- Nullable fields ------------------------------------------------------

    @Nested
    @DisplayName("Nullable fields")
    class NullableFieldTests {

        @Test
        @DisplayName("deviceId null for helper entities (virtual sensors, computed entities)")
        void nullDeviceIdForHelperEntities() {
            Entity helper = new Entity(
                    ENTITY_ID, "virtual-avg-temp", EntityType.SENSOR,
                    "Average Temperature", null, 0, null, true,
                    List.of(), List.of(), CREATED_AT);

            assertThat(helper.deviceId()).isNull();
        }

        @Test
        @DisplayName("areaId null to inherit from device")
        void nullAreaIdInheritsFromDevice() {
            Entity e = new Entity(
                    ENTITY_ID, "slug", EntityType.SWITCH,
                    "Name", DEVICE_ID, 0, null, true,
                    List.of(), List.of(), CREATED_AT);

            assertThat(e.areaId()).isNull();
        }

        @Test
        @DisplayName("both deviceId and areaId null simultaneously")
        void bothNullable() {
            Entity e = new Entity(
                    ENTITY_ID, "slug", EntityType.SENSOR,
                    "Name", null, 0, null, true,
                    List.of(), List.of(), CREATED_AT);

            assertThat(e.deviceId()).isNull();
            assertThat(e.areaId()).isNull();
        }
    }

    // -- Collections ----------------------------------------------------------

    @Nested
    @DisplayName("Collection fields")
    class CollectionTests {

        @Test
        @DisplayName("capabilities list is preserved")
        void capabilitiesPreserved() {
            Entity e = fullEntity();
            assertThat(e.capabilities()).hasSize(1);
            assertThat(e.capabilities().get(0).capabilityId()).isEqualTo("on_off");
        }

        @Test
        @DisplayName("labels list is preserved")
        void labelsPreserved() {
            Entity e = fullEntity();
            assertThat(e.labels()).containsExactly("lighting");
        }

        @Test
        @DisplayName("empty collections are accepted")
        void emptyCollections() {
            Entity e = new Entity(ENTITY_ID, "slug", EntityType.SWITCH,
                    "Name", DEVICE_ID, 0, null, true,
                    List.of(), List.of(), CREATED_AT);
            assertThat(e.labels()).isEmpty();
            assertThat(e.capabilities()).isEmpty();
        }
    }

    // -- Enabled flag ---------------------------------------------------------

    @Nested
    @DisplayName("Enabled flag")
    class EnabledTests {

        @Test
        @DisplayName("enabled true is accessible")
        void enabledTrue() {
            assertThat(fullEntity().enabled()).isTrue();
        }

        @Test
        @DisplayName("enabled false is accessible")
        void enabledFalse() {
            Entity e = new Entity(ENTITY_ID, "slug", EntityType.SWITCH,
                    "Name", DEVICE_ID, 0, null, false,
                    List.of(), List.of(), CREATED_AT);
            assertThat(e.enabled()).isFalse();
        }
    }

    // -- Equals / hashCode ----------------------------------------------------

    @Nested
    @DisplayName("Equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("identical Entities are equal")
        void identicalEqual() {
            Entity a = fullEntity();
            Entity b = fullEntity();
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Entities with different entityId are not equal")
        void differentEntityIdNotEqual() {
            Entity a = fullEntity();
            EntityId otherId = EntityId.of(new Ulid(0x0192A3B4C5D6E7FFL, 0x0102030405060708L));
            Entity b = new Entity(otherId, "kitchen-bulb-light", EntityType.LIGHT,
                    "Kitchen Bulb Light", DEVICE_ID, 1, AREA_ID, true,
                    List.of("lighting"), List.of(SAMPLE_CAPABILITY), CREATED_AT);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Entity is not equal to null")
        void notEqualToNull() {
            assertThat(fullEntity()).isNotEqualTo(null);
        }
    }
}
