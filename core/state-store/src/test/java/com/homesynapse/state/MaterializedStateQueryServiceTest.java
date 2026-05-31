/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MaterializedStateQueryService} — production
 * {@link StateQueryService} backed by {@link StateStore} + {@link ReadinessSource}.
 *
 * <p>Covers the seven required scenarios from the M3.6e.1 coding instruction
 * plus three additional staleness-recomputation tests that exercise the
 * read-time stale derivation contract (Doc 03 §3.8, AMD-11 — HomeSynapse's
 * #1 architectural differentiator).</p>
 *
 * <p>Uses the {@code testFixtures} {@link InMemoryStateStore} as the backing
 * store, an {@link AtomicReference}-based {@link ReadinessSource} stub for
 * mode control, and a {@link Clock#fixed} for deterministic staleness
 * assertions (DEC-M3-09 / {@code NO_DIRECT_TIME_ACCESS} compliance).</p>
 */
@DisplayName("MaterializedStateQueryService")
final class MaterializedStateQueryServiceTest {

    // ── Deterministic fixtures ───────────────────────────────────────────

    private static final Instant CLOCK_INSTANT =
            Instant.parse("2026-05-22T12:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);

    private static final EntityId ENTITY_A =
            new EntityId(new Ulid(0x1111L, 0xAAAAL));
    private static final EntityId ENTITY_B =
            new EntityId(new Ulid(0x2222L, 0xBBBBL));
    private static final EntityId ENTITY_C =
            new EntityId(new Ulid(0x3333L, 0xCCCCL));
    private static final EntityId UNKNOWN =
            new EntityId(new Ulid(0x9999L, 0xFFFFL));

    private InMemoryStateStore stateStore;
    private AtomicReference<SubscriberMode> mode;
    private AtomicReference<Long> viewPosition;
    private MaterializedStateQueryService service;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    MaterializedStateQueryServiceTest() {
    }

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryStateStore();
        mode = new AtomicReference<>(SubscriberMode.LIVE);
        viewPosition = new AtomicReference<>(0L);
        service = new MaterializedStateQueryService(
                stateStore,
                () -> mode.get(),
                () -> viewPosition.get(),
                FIXED_CLOCK);
    }

    // ──────────────────────────────────────────────────────────────────────
    // getState
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getState")
    final class GetState {

        /** Explicit no-arg constructor. */
        GetState() {
        }

        @Test
        @DisplayName("returns entity when present in the store")
        void getStateReturnsEntityWhenPresent() {
            EntityState stored = entity(ENTITY_A, 7L, null);
            stateStore.put(ENTITY_A, stored);

            Optional<EntityState> result = service.getState(ENTITY_A);

            assertThat(result).isPresent();
            assertThat(result.get().entityId()).isEqualTo(ENTITY_A);
            assertThat(result.get().stateVersion()).isEqualTo(7L);
        }

        @Test
        @DisplayName("returns empty for an unknown entity")
        void getStateReturnsEmptyForUnknownEntity() {
            assertThat(service.getState(UNKNOWN)).isEmpty();
        }

        @Test
        @DisplayName("recomputes stale=true when clock is past staleAfter")
        void getStateRecomputesStalenessFromClock() {
            Instant staleAfter = CLOCK_INSTANT.minusSeconds(1);
            // Projection writes stale=false unconditionally; we expect the
            // query service to flip it to true at read time.
            stateStore.put(ENTITY_A, entity(ENTITY_A, 1L, staleAfter, false));

            EntityState result = service.getState(ENTITY_A).orElseThrow();

            assertThat(result.stale()).isTrue();
            assertThat(result.staleAfter()).isEqualTo(staleAfter);
        }

        @Test
        @DisplayName("recomputes stale=false when clock is before staleAfter")
        void getStateNotStaleBeforeThreshold() {
            Instant staleAfter = CLOCK_INSTANT.plusSeconds(60);
            // Projection might erroneously write stale=true; the query
            // service must correct this from the wall clock.
            stateStore.put(ENTITY_A, entity(ENTITY_A, 1L, staleAfter, true));

            EntityState result = service.getState(ENTITY_A).orElseThrow();

            assertThat(result.stale()).isFalse();
            assertThat(result.staleAfter()).isEqualTo(staleAfter);
        }

        @Test
        @DisplayName("never stale when staleAfter is null regardless of clock")
        void getStateWithNoStaleAfterIsNeverStale() {
            // Even if the projection erroneously wrote stale=true, an
            // entity without a staleAfter contract is never stale.
            stateStore.put(ENTITY_A, entity(ENTITY_A, 1L, null, true));

            EntityState result = service.getState(ENTITY_A).orElseThrow();

            assertThat(result.stale()).isFalse();
            assertThat(result.staleAfter()).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // getStates
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getStates")
    final class GetStates {

        /** Explicit no-arg constructor. */
        GetStates() {
        }

        @Test
        @DisplayName("omits unknown entity ids silently")
        void getStatesOmitsUnknownEntityIds() {
            stateStore.put(ENTITY_A, entity(ENTITY_A, 1L, null));
            stateStore.put(ENTITY_B, entity(ENTITY_B, 1L, null));

            Map<EntityId, EntityState> result =
                    service.getStates(Set.of(ENTITY_A, ENTITY_B, UNKNOWN));

            assertThat(result).hasSize(2);
            assertThat(result).containsOnlyKeys(ENTITY_A, ENTITY_B);
            assertThat(result.values()).doesNotContainNull();
        }

        @Test
        @DisplayName("returns an unmodifiable map")
        void getStatesReturnsUnmodifiableMap() {
            stateStore.put(ENTITY_A, entity(ENTITY_A, 1L, null));

            Map<EntityId, EntityState> result = service.getStates(Set.of(ENTITY_A));

            assertThatThrownBy(() -> result.put(ENTITY_B, entity(ENTITY_B, 1L, null)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("each entry has its stale flag recomputed at read time")
        void getStatesRecomputesStaleness() {
            // A is stale (staleAfter in the past, projection erroneously stale=false);
            // B has no staleAfter so is never stale.
            stateStore.put(ENTITY_A,
                    entity(ENTITY_A, 1L, CLOCK_INSTANT.minusSeconds(5), false));
            stateStore.put(ENTITY_B,
                    entity(ENTITY_B, 1L, null, false));

            Map<EntityId, EntityState> result =
                    service.getStates(Set.of(ENTITY_A, ENTITY_B));

            assertThat(result.get(ENTITY_A).stale()).isTrue();
            assertThat(result.get(ENTITY_B).stale()).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // getSnapshot
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSnapshot")
    final class GetSnapshot {

        /** Explicit no-arg constructor. */
        GetSnapshot() {
        }

        @Test
        @DisplayName("returns all entities at the same viewPosition")
        void getSnapshotReturnsAllEntitiesAtSameViewPosition() {
            stateStore.put(ENTITY_A, entity(ENTITY_A, 1L, null));
            stateStore.put(ENTITY_B, entity(ENTITY_B, 1L, null));
            stateStore.put(ENTITY_C, entity(ENTITY_C, 1L, null));
            viewPosition.set(42L);

            StateSnapshot snapshot = service.getSnapshot();

            assertThat(snapshot.states()).hasSize(3);
            assertThat(snapshot.viewPosition()).isEqualTo(42L);
            assertThat(snapshot.snapshotTime()).isEqualTo(CLOCK_INSTANT);
        }

        @Test
        @DisplayName("snapshot.replaying reflects readiness mode != LIVE")
        void getSnapshotReplayingFlag() {
            stateStore.put(ENTITY_A, entity(ENTITY_A, 1L, null));

            mode.set(SubscriberMode.LIVE);
            assertThat(service.getSnapshot().replaying()).isFalse();

            mode.set(SubscriberMode.REPLAY);
            assertThat(service.getSnapshot().replaying()).isTrue();

            mode.set(SubscriberMode.COLD);
            assertThat(service.getSnapshot().replaying()).isTrue();
        }

        @Test
        @DisplayName("snapshot.disabledEntities defaults to an empty set")
        void getSnapshotDisabledEntitiesEmpty() {
            stateStore.put(ENTITY_A, entity(ENTITY_A, 1L, null));

            StateSnapshot snapshot = service.getSnapshot();

            assertThat(snapshot.disabledEntities()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // getViewPosition
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getViewPosition reflects the last processed event position")
    void getViewPositionReflectsLastProcessedEvent() {
        viewPosition.set(99L);
        assertThat(service.getViewPosition()).isEqualTo(99L);

        viewPosition.set(100L);
        assertThat(service.getViewPosition()).isEqualTo(100L);
    }

    // ──────────────────────────────────────────────────────────────────────
    // isReady
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isReady delegates to readinessSource — true iff LIVE")
    void isReadyDelegatesToReadinessSource() {
        mode.set(SubscriberMode.LIVE);
        assertThat(service.isReady()).isTrue();

        mode.set(SubscriberMode.REPLAY);
        assertThat(service.isReady()).isFalse();

        mode.set(SubscriberMode.COLD);
        assertThat(service.isReady()).isFalse();

        mode.set(SubscriberMode.TRANSITION);
        assertThat(service.isReady()).isFalse();

        mode.set(SubscriberMode.SUSPENDED);
        assertThat(service.isReady()).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static EntityState entity(EntityId id, long version, Instant staleAfter) {
        return entity(id, version, staleAfter, false);
    }

    private static EntityState entity(
            EntityId id, long version, Instant staleAfter, boolean storedStale) {
        Map<String, AttributeValue> attrs = new HashMap<>();
        attrs.put("brightness", new IntValue(50));
        return new EntityState(
                id,
                Map.copyOf(attrs),
                Availability.AVAILABLE,
                version,
                CLOCK_INSTANT.minus(Duration.ofMinutes(1)),
                CLOCK_INSTANT.minus(Duration.ofMinutes(1)),
                CLOCK_INSTANT.minus(Duration.ofMinutes(1)),
                staleAfter,
                storedStale);
    }
}
