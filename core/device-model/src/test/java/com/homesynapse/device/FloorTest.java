/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.platform.identity.FloorId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link Floor} — the floor aggregate record (AMD-44 §2.1.2).
 *
 * <p>Time is supplied as a literal {@link Instant} (no {@code Instant.now()} /
 * {@code Clock.systemUTC()}), keeping the suite compliant with the
 * {@code NO_DIRECT_TIME_ACCESS} arch rule.</p>
 */
@DisplayName("Floor")
class FloorTest {

    private static final FloorId FLOOR_ID =
            FloorId.of(new Ulid(0x0192A3B4C5D6E7F0L, 0x0102030405060708L));
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static Floor groundFloor() {
        return new Floor(FLOOR_ID, "Ground Floor", 0, "mdi:home-floor-g",
                List.of("downstairs", "main level"), CREATED_AT);
    }

    @Nested
    @DisplayName("Construction and accessors")
    class ConstructionTests {

        @Test
        @DisplayName("all six components accessible after construction")
        void allFieldsAccessible() {
            Floor f = groundFloor();

            assertThat(f.id()).isEqualTo(FLOOR_ID);
            assertThat(f.name()).isEqualTo("Ground Floor");
            assertThat(f.level()).isZero();
            assertThat(f.icon()).isEqualTo("mdi:home-floor-g");
            assertThat(f.aliases()).containsExactly("downstairs", "main level");
            assertThat(f.createdAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("record has exactly 6 components")
        void exactlySixComponents() {
            assertThat(Floor.class.getRecordComponents()).hasSize(6);
        }

        @Test
        @DisplayName("name of exactly 100 chars is permitted")
        void constructor_name100Chars_permitted() {
            String name = "a".repeat(100);
            Floor f = new Floor(FLOOR_ID, name, 0, null, List.of(), CREATED_AT);
            assertThat(f.name()).hasSize(100);
        }
    }

    @Nested
    @DisplayName("Required-field validation")
    class RequiredFieldTests {

        @Test
        @DisplayName("null id throws NullPointerException")
        void constructor_nullId_throwsNpe() {
            assertThatThrownBy(() ->
                    new Floor(null, "Ground", 0, null, List.of(), CREATED_AT))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Floor id");
        }

        @Test
        @DisplayName("null name throws NullPointerException")
        void constructor_nullName_throwsNpe() {
            assertThatThrownBy(() ->
                    new Floor(FLOOR_ID, null, 0, null, List.of(), CREATED_AT))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Floor name");
        }

        @Test
        @DisplayName("blank name throws IllegalArgumentException")
        void constructor_blankName_throwsIae() {
            assertThatThrownBy(() ->
                    new Floor(FLOOR_ID, "  ", 0, null, List.of(), CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("name over 100 chars throws IllegalArgumentException")
        void constructor_nameOver100Chars_throwsIae() {
            String name = "a".repeat(101);
            assertThatThrownBy(() ->
                    new Floor(FLOOR_ID, name, 0, null, List.of(), CREATED_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("100");
        }

        @Test
        @DisplayName("null createdAt throws NullPointerException")
        void constructor_nullCreatedAt_throwsNpe() {
            assertThatThrownBy(() ->
                    new Floor(FLOOR_ID, "Ground", 0, null, List.of(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("createdAt");
        }

        @Test
        @DisplayName("null aliases throws NullPointerException (List.copyOf)")
        void constructor_nullAliases_throwsNpe() {
            assertThatThrownBy(() ->
                    new Floor(FLOOR_ID, "Ground", 0, null, null, CREATED_AT))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Nullable and signed fields")
    class OptionalFieldTests {

        @Test
        @DisplayName("null icon is permitted")
        void constructor_nullIcon_permitted() {
            Floor f = new Floor(FLOOR_ID, "Ground", 0, null, List.of(), CREATED_AT);
            assertThat(f.icon()).isNull();
        }

        @Test
        @DisplayName("negative level is permitted (basement)")
        void constructor_negativeLevel_permitted() {
            Floor f = new Floor(FLOOR_ID, "Basement", -1, null, List.of(), CREATED_AT);
            assertThat(f.level()).isEqualTo(-1);
        }

        @Test
        @DisplayName("two floors at the same level both construct (split-level, Decision 8)")
        void constructor_duplicateLevelsAcrossFloors_permitted() {
            FloorId otherId =
                    FloorId.of(new Ulid(0x0192A3B4C5D6E7F1L, 0x0102030405060709L));
            Floor a = new Floor(FLOOR_ID, "Split A", 1, null, List.of(), CREATED_AT);
            Floor b = new Floor(otherId, "Split B", 1, null, List.of(), CREATED_AT);
            assertThat(a.level()).isEqualTo(b.level());
        }
    }

    @Nested
    @DisplayName("Aliases defensive copy")
    class AliasesTests {

        @Test
        @DisplayName("returned aliases list is unmodifiable")
        void constructor_aliases_areUnmodifiable() {
            Floor f = groundFloor();
            assertThatThrownBy(() -> f.aliases().add("attic"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("mutating the source list after construction does not affect the floor")
        void constructor_aliases_defensivelyCopied() {
            List<String> source = new ArrayList<>(List.of("upstairs"));
            Floor f = new Floor(FLOOR_ID, "First Floor", 1, null, source, CREATED_AT);

            source.add("loft");

            assertThat(f.aliases()).containsExactly("upstairs");
        }
    }
}
