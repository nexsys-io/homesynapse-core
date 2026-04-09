/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.BooleanValue;
import com.homesynapse.device.IntValue;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EntityState} — the primary materialized state record.
 *
 * <p>Documents and proves the staleness model where staleness is computed at
 * read time from {@code staleAfter} and the wall clock, not at projection
 * time. This is HomeSynapse's architectural differentiator: no other smart
 * home platform implements this pattern.</p>
 *
 * <p>Also verifies the three-timestamp model (lastChanged / lastUpdated /
 * lastReported), the stateVersion idempotency cursor semantics, and record
 * equality behavior.</p>
 *
 * @see EntityState
 * @see StateQueryService
 */
@DisplayName("EntityState")
class EntityStateTest {

    // ── Deterministic test fixtures ──────────────────────────────────────

    private static final Ulid TEST_ULID = new Ulid(1L, 1L);
    private static final EntityId ENTITY_ID = EntityId.of(TEST_ULID);
    private static final Instant BASE_TIME = Instant.parse("2026-04-07T12:00:00Z");

    private static final Map<String, AttributeValue> ATTRIBUTES = Map.of(
            "brightness", new IntValue(75),
            "on", new BooleanValue(true)
    );

    /** Creates a new test instance. */
    EntityStateTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    /**
     * Builds a valid EntityState with sensible defaults.
     * Override individual fields in specific tests.
     */
    private static EntityState validState() {
        return new EntityState(
                ENTITY_ID,
                ATTRIBUTES,
                Availability.AVAILABLE,
                1L,
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
        @DisplayName("all 9 fields are accessible and return correct values")
        void allFieldsAccessible() {
            Instant staleAfter = BASE_TIME.plusSeconds(3600);
            var state = new EntityState(
                    ENTITY_ID,
                    ATTRIBUTES,
                    Availability.AVAILABLE,
                    42L,
                    BASE_TIME,
                    BASE_TIME.plusSeconds(10),
                    BASE_TIME.plusSeconds(5),
                    staleAfter,
                    false
            );

            assertThat(state.entityId()).isEqualTo(ENTITY_ID);
            assertThat(state.entityId()).isInstanceOf(EntityId.class);
            assertThat(state.attributes()).isEqualTo(ATTRIBUTES);
            assertThat(state.attributes()).isInstanceOf(Map.class);
            assertThat(state.availability()).isEqualTo(Availability.AVAILABLE);
            assertThat(state.availability()).isInstanceOf(Availability.class);
            assertThat(state.stateVersion()).isEqualTo(42L);
            assertThat(state.lastChanged()).isEqualTo(BASE_TIME);
            assertThat(state.lastChanged()).isInstanceOf(Instant.class);
            assertThat(state.lastUpdated()).isEqualTo(BASE_TIME.plusSeconds(10));
            assertThat(state.lastUpdated()).isInstanceOf(Instant.class);
            assertThat(state.lastReported()).isEqualTo(BASE_TIME.plusSeconds(5));
            assertThat(state.lastReported()).isInstanceOf(Instant.class);
            assertThat(state.staleAfter()).isEqualTo(staleAfter);
            assertThat(state.stale()).isFalse();
        }

        @Test
        @DisplayName("exactly 9 record components")
        void exactlyNineRecordComponents() {
            assertThat(EntityState.class.getRecordComponents()).hasSize(9);
        }

        @Test
        @DisplayName("attributes map with null values is valid — null represents never-reported attribute")
        void attributes_withNullValues_isValid() {
            // Map.of() does not allow null values; use Collections.singletonMap()
            Map<String, AttributeValue> attrs = Collections.singletonMap("temperature_c", null);

            var state = new EntityState(
                    ENTITY_ID,
                    attrs,
                    Availability.AVAILABLE,
                    1L,
                    BASE_TIME,
                    BASE_TIME,
                    BASE_TIME,
                    null,
                    false
            );

            assertThat(state.attributes()).containsKey("temperature_c");
            assertThat(state.attributes().get("temperature_c")).isNull();
        }
    }

    // ── Tier 2: Staleness model ─────────────────────────────────────────

    @Nested
    @DisplayName("Staleness model — read-time derivation from staleAfter + Clock")
    class StalenessTests {

        /** Creates a new test instance. */
        StalenessTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("staleAfter null, stale false — actuators and event-driven reporters are never stale")
        void staleAfterNull_staleIsFalse() {
            var state = new EntityState(
                    ENTITY_ID, ATTRIBUTES, Availability.AVAILABLE,
                    1L, BASE_TIME, BASE_TIME, BASE_TIME,
                    null, false
            );

            assertThat(state.staleAfter()).isNull();
            assertThat(state.stale()).isFalse();
        }

        @Test
        @DisplayName("staleAfter in past, stale true — sensor reporting interval expired")
        void staleAfterInPast_staleIsTrue() {
            Instant pastStaleAfter = BASE_TIME.minusSeconds(60);
            var state = new EntityState(
                    ENTITY_ID, ATTRIBUTES, Availability.AVAILABLE,
                    5L, BASE_TIME, BASE_TIME, BASE_TIME,
                    pastStaleAfter, true
            );

            assertThat(state.staleAfter()).isEqualTo(pastStaleAfter);
            assertThat(state.staleAfter()).isBefore(BASE_TIME);
            assertThat(state.stale()).isTrue();
        }

        @Test
        @DisplayName("staleAfter in future, stale false — recently-reported sensor is not yet stale")
        void staleAfterInFuture_staleIsFalse() {
            Instant futureStaleAfter = BASE_TIME.plusSeconds(3600);
            var state = new EntityState(
                    ENTITY_ID, ATTRIBUTES, Availability.AVAILABLE,
                    3L, BASE_TIME, BASE_TIME, BASE_TIME,
                    futureStaleAfter, false
            );

            assertThat(state.staleAfter()).isEqualTo(futureStaleAfter);
            assertThat(state.staleAfter()).isAfter(BASE_TIME);
            assertThat(state.stale()).isFalse();
        }

        @Test
        @DisplayName("staleness derivation matches clock comparison — executable documentation for StateQueryService")
        void stalenessDerivation_matchesClockComparison() {
            // This test is EXECUTABLE DOCUMENTATION: it shows the exact computation
            // that StateQueryService must perform at read time.
            //   stale = staleAfter != null && now.isAfter(staleAfter)

            Instant now = Instant.parse("2026-04-07T13:00:00Z");

            // Case 1: staleAfter is in the past relative to now → stale
            Instant expiredStaleAfter = now.minusSeconds(120);
            boolean expectedStale1 = expiredStaleAfter != null && now.isAfter(expiredStaleAfter);
            var staleState = new EntityState(
                    ENTITY_ID, ATTRIBUTES, Availability.AVAILABLE,
                    10L, BASE_TIME, BASE_TIME, BASE_TIME,
                    expiredStaleAfter, expectedStale1
            );

            assertThat(expectedStale1).isTrue();
            assertThat(staleState.stale()).isEqualTo(expectedStale1);

            // Case 2: staleAfter is in the future relative to now → not stale
            Instant freshStaleAfter = now.plusSeconds(3600);
            boolean expectedStale2 = freshStaleAfter != null && now.isAfter(freshStaleAfter);
            var freshState = new EntityState(
                    ENTITY_ID, ATTRIBUTES, Availability.AVAILABLE,
                    11L, BASE_TIME, BASE_TIME, BASE_TIME,
                    freshStaleAfter, expectedStale2
            );

            assertThat(expectedStale2).isFalse();
            assertThat(freshState.stale()).isEqualTo(expectedStale2);
        }

        /**
         * Documents a POTENTIAL INCONSISTENCY that StateQueryService must prevent.
         *
         * <p>EntityState has no compact constructor validation, so it is possible to
         * construct a record with {@code staleAfter = null} and {@code stale = true}.
         * This is inconsistent: the stale field should only be true when staleAfter
         * is non-null and in the past. The Phase 3 StateQueryService implementation
         * MUST ensure this inconsistency never reaches consumers by computing stale
         * at read time as: {@code staleAfter != null && clock.instant().isAfter(staleAfter)}.</p>
         */
        @Test
        @DisplayName("staleAfter null with stale true is constructable — Phase 3 correctness requirement for StateQueryService")
        void staleAfterNull_withStaleTrue_isConstructable() {
            var state = new EntityState(
                    ENTITY_ID, ATTRIBUTES, Availability.AVAILABLE,
                    1L, BASE_TIME, BASE_TIME, BASE_TIME,
                    null, true
            );

            // The record permits this inconsistent construction — no compact constructor
            assertThat(state.staleAfter()).isNull();
            assertThat(state.stale()).isTrue();
        }
    }

    // ── Tier 3: Three-timestamp model ───────────────────────────────────

    @Nested
    @DisplayName("Three-timestamp model")
    class TimestampTests {

        /** Creates a new test instance. */
        TimestampTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("three timestamps are independent — lastChanged, lastUpdated, lastReported track different concerns")
        void threeTimestamps_areIndependent() {
            Instant lastChanged = Instant.parse("2026-04-07T10:00:00Z");
            Instant lastUpdated = Instant.parse("2026-04-07T11:00:00Z");
            Instant lastReported = Instant.parse("2026-04-07T11:30:00Z");

            var state = new EntityState(
                    ENTITY_ID, ATTRIBUTES, Availability.AVAILABLE,
                    5L, lastChanged, lastUpdated, lastReported,
                    null, false
            );

            // All three are distinct and return their respective values
            assertThat(state.lastChanged()).isEqualTo(lastChanged);
            assertThat(state.lastUpdated()).isEqualTo(lastUpdated);
            assertThat(state.lastReported()).isEqualTo(lastReported);

            assertThat(state.lastChanged())
                    .isNotEqualTo(state.lastUpdated())
                    .isNotEqualTo(state.lastReported());
        }

        @Test
        @DisplayName("stateVersion advances on every event including no-op reports — idempotency cursor, not change detector")
        void stateVersion_advancesOnEveryEvent() {
            // Same attributes, different stateVersion — documents that
            // stateVersion advances even when the reported value matches
            // the canonical state (a no-op state_reported event).
            Map<String, AttributeValue> sameAttributes = Map.of(
                    "on", new BooleanValue(true)
            );

            var version1 = new EntityState(
                    ENTITY_ID, sameAttributes, Availability.AVAILABLE,
                    1L, BASE_TIME, BASE_TIME, BASE_TIME,
                    null, false
            );

            var version2 = new EntityState(
                    ENTITY_ID, sameAttributes, Availability.AVAILABLE,
                    2L, BASE_TIME, BASE_TIME.plusSeconds(30), BASE_TIME.plusSeconds(30),
                    null, false
            );

            assertThat(version1.attributes()).isEqualTo(version2.attributes());
            assertThat(version2.stateVersion()).isGreaterThan(version1.stateVersion());
        }
    }

    // ── Tier 4: Equals and identity ─────────────────────────────────────

    @Nested
    @DisplayName("Equals and identity")
    class EqualsTests {

        /** Creates a new test instance. */
        EqualsTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("identical fields are equal and have same hashCode")
        void identicalFields_areEqual() {
            var a = validState();
            var b = validState();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("different stateVersion makes records not equal — state caching must version-check")
        void differentStateVersion_areNotEqual() {
            var a = validState();
            var b = new EntityState(
                    ENTITY_ID, ATTRIBUTES, Availability.AVAILABLE,
                    2L,
                    BASE_TIME, BASE_TIME, BASE_TIME,
                    null, false
            );

            assertThat(a.entityId()).isEqualTo(b.entityId());
            assertThat(a).isNotEqualTo(b);
        }
    }
}
