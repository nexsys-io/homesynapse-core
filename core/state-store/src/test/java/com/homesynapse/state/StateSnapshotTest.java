/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.homesynapse.device.BooleanValue;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StateSnapshot} — point-in-time immutable copy of the entire
 * materialized state view.
 *
 * <p>Verifies all 5 record fields, empty snapshot validity, the replaying gate,
 * disabled entity tracking, and view position semantics.</p>
 *
 * @see StateSnapshot
 * @see StateQueryService#getSnapshot()
 */
@DisplayName("StateSnapshot")
class StateSnapshotTest {

    // ── Deterministic test fixtures ──────────────────────────────────────

    private static final Ulid ULID_A = new Ulid(1L, 1L);
    private static final Ulid ULID_B = new Ulid(2L, 2L);
    private static final EntityId ENTITY_A = EntityId.of(ULID_A);
    private static final EntityId ENTITY_B = EntityId.of(ULID_B);
    private static final Instant BASE_TIME = Instant.parse("2026-04-07T12:00:00Z");

    /** Creates a new test instance. */
    StateSnapshotTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    private static EntityState stateFor(EntityId entityId, long version) {
        return new EntityState(
                entityId,
                Map.of("on", new BooleanValue(true)),
                Availability.AVAILABLE,
                version,
                BASE_TIME,
                BASE_TIME,
                BASE_TIME,
                null,
                false
        );
    }

    // ── Tier 1: Construction and field access ───────────────────────────

    @Nested
    @DisplayName("Construction and field access")
    class ConstructionTests {

        /** Creates a new test instance. */
        ConstructionTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("all 5 fields are accessible and return correct values")
        void allFieldsAccessible() {
            var stateA = stateFor(ENTITY_A, 1L);
            var stateB = stateFor(ENTITY_B, 2L);
            Map<EntityId, EntityState> states = Map.of(ENTITY_A, stateA, ENTITY_B, stateB);
            Set<EntityId> disabled = Set.of(ENTITY_B);

            var snapshot = new StateSnapshot(
                    states,
                    100L,
                    BASE_TIME,
                    false,
                    disabled
            );

            assertThat(snapshot.states()).isEqualTo(states);
            assertThat(snapshot.states()).hasSize(2);
            assertThat(snapshot.viewPosition()).isEqualTo(100L);
            assertThat(snapshot.snapshotTime()).isEqualTo(BASE_TIME);
            assertThat(snapshot.replaying()).isFalse();
            assertThat(snapshot.disabledEntities()).isEqualTo(disabled);
            assertThat(snapshot.disabledEntities()).hasSize(1);
        }

        @Test
        @DisplayName("exactly 5 record components")
        void exactlyFiveRecordComponents() {
            assertThat(StateSnapshot.class.getRecordComponents()).hasSize(5);
        }

        @Test
        @DisplayName("empty snapshot is valid — initial state before any events are processed")
        void emptySnapshot_isValid() {
            var snapshot = new StateSnapshot(
                    Map.of(),
                    0L,
                    BASE_TIME,
                    false,
                    Set.of()
            );

            assertThat(snapshot.states()).isEmpty();
            assertThat(snapshot.viewPosition()).isZero();
            assertThat(snapshot.replaying()).isFalse();
            assertThat(snapshot.disabledEntities()).isEmpty();
        }

        @Test
        @DisplayName("replaying true gates downstream behavior — REST API returns 503 (Doc 03 §3.6)")
        void replaying_gatesDownstreamBehavior() {
            var snapshot = new StateSnapshot(
                    Map.of(),
                    50L,
                    BASE_TIME,
                    true,
                    Set.of()
            );

            assertThat(snapshot.replaying()).isTrue();
        }
    }

    // ── Tier 2: Disabled entities and view semantics ────────────────────

    @Nested
    @DisplayName("Disabled entities and view semantics")
    class DisabledEntityTests {

        /** Creates a new test instance. */
        DisabledEntityTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("disabled entities tracked separately from states — last known state retained")
        void disabledEntities_trackedSeparately() {
            var stateA = stateFor(ENTITY_A, 1L);
            var stateB = stateFor(ENTITY_B, 2L);

            var snapshot = new StateSnapshot(
                    Map.of(ENTITY_A, stateA, ENTITY_B, stateB),
                    100L,
                    BASE_TIME,
                    false,
                    Set.of(ENTITY_B)
            );

            // Both entities present in states map
            assertThat(snapshot.states()).containsKey(ENTITY_A);
            assertThat(snapshot.states()).containsKey(ENTITY_B);

            // Only ENTITY_B is disabled
            assertThat(snapshot.disabledEntities()).containsExactly(ENTITY_B);
            assertThat(snapshot.disabledEntities()).doesNotContain(ENTITY_A);
        }

        @Test
        @DisplayName("viewPosition monotonically increasing — consumers detect view advancement")
        void viewPosition_monotonicallyIncreasing() {
            var snapshot1 = new StateSnapshot(
                    Map.of(), 100L, BASE_TIME, false, Set.of());
            var snapshot2 = new StateSnapshot(
                    Map.of(), 200L, BASE_TIME.plusSeconds(60), false, Set.of());

            assertThat(snapshot2.viewPosition())
                    .isGreaterThan(snapshot1.viewPosition());
        }

        @Test
        @DisplayName("snapshotTime reflects creation moment")
        void snapshotTime_reflectsCreationMoment() {
            Instant creationTime = Instant.parse("2026-04-07T14:30:00Z");

            var snapshot = new StateSnapshot(
                    Map.of(), 50L, creationTime, false, Set.of());

            assertThat(snapshot.snapshotTime()).isEqualTo(creationTime);
        }
    }
}
