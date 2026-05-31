/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.AreaId;
import com.homesynapse.platform.identity.FloorId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * Tests for {@link Area} — the minimal area aggregate (AMD-44 §2.2).
 *
 * <p>Time is supplied as a literal {@link Instant} to comply with the
 * {@code NO_DIRECT_TIME_ACCESS} arch rule.</p>
 */
@DisplayName("Area")
class AreaTest {

    private static final AreaId AREA_ID =
            AreaId.of(new Ulid(0x0192A3B4C5D6E7F0L, 0x0102030405060708L));
    private static final FloorId FLOOR_ID =
            FloorId.of(new Ulid(0x0192A3B4C5D6E7F1L, 0x0102030405060709L));
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Nested
    @DisplayName("Construction and accessors")
    class ConstructionTests {

        @Test
        @DisplayName("all four components accessible after construction")
        void allFieldsAccessible() {
            Area a = new Area(AREA_ID, "Kitchen", FLOOR_ID, CREATED_AT);

            assertThat(a.id()).isEqualTo(AREA_ID);
            assertThat(a.name()).isEqualTo("Kitchen");
            assertThat(a.floorId()).isEqualTo(FLOOR_ID);
            assertThat(a.createdAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("record has exactly 4 components")
        void exactlyFourComponents() {
            assertThat(Area.class.getRecordComponents()).hasSize(4);
        }

        @Test
        @DisplayName("name of exactly 100 chars is permitted")
        void constructor_name100Chars_permitted() {
            String name = "a".repeat(100);
            Area a = new Area(AREA_ID, name, FLOOR_ID, CREATED_AT);
            assertThat(a.name()).hasSize(100);
        }
    }

    @Nested
    @DisplayName("Required-field validation")
    class RequiredFieldTests {

        @Test
        @DisplayName("null id throws NullPointerException")
        void constructor_nullId_throwsNpe() {
            assertThatThrownBy(() -> new Area(null, "Kitchen", FLOOR_ID, CREATED_AT))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Area id");
        }

        @Test
        @DisplayName("null name throws NullPointerException")
        void constructor_nullName_throwsNpe() {
            assertThatThrownBy(() -> new Area(AREA_ID, null, FLOOR_ID, CREATED_AT))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Area name");
        }

        @Test
        @DisplayName("blank name throws IllegalArgumentException")
        void constructor_blankName_throwsIae() {
            assertThatThrownBy(() -> new Area(AREA_ID, "   ", FLOOR_ID, CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("name over 100 chars throws IllegalArgumentException")
        void constructor_nameOver100Chars_throwsIae() {
            String name = "a".repeat(101);
            assertThatThrownBy(() -> new Area(AREA_ID, name, FLOOR_ID, CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("100");
        }

        @Test
        @DisplayName("null createdAt throws NullPointerException")
        void constructor_nullCreatedAt_throwsNpe() {
            assertThatThrownBy(() -> new Area(AREA_ID, "Kitchen", FLOOR_ID, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("createdAt");
        }
    }

    @Nested
    @DisplayName("Nullable floorId")
    class FloorAssignmentTests {

        @Test
        @DisplayName("null floorId is permitted (area unassigned to any floor)")
        void constructor_nullFloorId_permitted() {
            Area a = new Area(AREA_ID, "Hallway", null, CREATED_AT);
            assertThat(a.floorId()).isNull();
        }
    }
}
