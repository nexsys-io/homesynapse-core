/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.StringValue;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.Availability;
import com.homesynapse.state.CheckpointRecord;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.ViewCheckpointStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SqliteStateStore}.
 *
 * <p>Uses an in-test {@link ViewCheckpointStore} that mirrors the persistence
 * module's {@code SqliteViewCheckpointStore} behavior at the contract level
 * (per-view byte[] storage with an opaque payload) but avoids the SQLite
 * setup overhead — the focus here is the {@link SqliteStateStore}-specific
 * checkpoint-rehydrate path and crash-recovery semantics, not the SQLite
 * implementation of {@link ViewCheckpointStore} (which is covered by
 * {@code SqliteViewCheckpointStoreTest}).</p>
 */
@DisplayName("SqliteStateStore")
final class SqliteStateStoreTest {

    private static final String VIEW_NAME = "entity_state";

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T00:01:00Z");
    private static final Instant T2 = Instant.parse("2026-01-01T00:02:00Z");

    private static final EntityId ENT_A = new EntityId(
            new Ulid(0x01__00_00_00_00_00_00_00L, 0x00_00_00_00_00_00_00_01L));
    private static final EntityId ENT_B = new EntityId(
            new Ulid(0x02__00_00_00_00_00_00_00L, 0x00_00_00_00_00_00_00_02L));
    private static final EntityId ENT_C = new EntityId(
            new Ulid(0x03__00_00_00_00_00_00_00L, 0x00_00_00_00_00_00_00_03L));

    private CheckpointSerializer serializer;
    private InMemoryViewCheckpointStoreLocal checkpointStore;

    /** Required no-arg constructor for {@code -Xlint:all -Werror} builds. */
    SqliteStateStoreTest() {
        // No-op.
    }

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.ALWAYS)
                .build();
        serializer = new CheckpointSerializer(mapper);
        checkpointStore = new InMemoryViewCheckpointStoreLocal();
    }

    // ──────────────────────────────────────────────────────────────────
    // Basic StateStore contract
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("get returns empty for unknown entity")
    void getReturnsEmptyForUnknownEntity() {
        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);

        assertThat(store.get(ENT_A)).isEmpty();
    }

    @Test
    @DisplayName("put then get returns the stored state")
    void putAndGetRoundTrip() {
        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);
        EntityState s = entityWithAttrs(ENT_A,
                Map.of("on", new StringValue("true")), Availability.AVAILABLE,
                1L, T0, T0, T0, null, false);

        store.put(ENT_A, s);

        assertThat(store.get(ENT_A)).contains(s);
    }

    @Test
    @DisplayName("getAll returns all stored entities")
    void getAllReturnsAllEntities() {
        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);
        store.put(ENT_A, entityWithAttrs(ENT_A, Map.of(), Availability.AVAILABLE,
                1L, T0, T0, T0, null, false));
        store.put(ENT_B, entityWithAttrs(ENT_B, Map.of(), Availability.UNAVAILABLE,
                1L, T1, T1, T1, null, false));

        Map<EntityId, EntityState> all = store.getAll();

        assertThat(all).hasSize(2).containsKeys(ENT_A, ENT_B);
    }

    @Test
    @DisplayName("clear removes all state")
    void clearRemovesAllState() {
        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);
        store.put(ENT_A, entityWithAttrs(ENT_A, Map.of(), Availability.AVAILABLE,
                1L, T0, T0, T0, null, false));

        store.clear();

        assertThat(store.getAll()).isEmpty();
        assertThat(store.loadedProjectionVersion()).isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // Checkpoint initialization
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("initializes from a non-empty checkpoint payload")
    void initializesFromCheckpoint() {
        // Stage 1: build a state map, serialize, and stash in the
        // checkpoint store as if a previous projection had persisted it.
        Map<EntityId, EntityState> initial = new LinkedHashMap<>();
        initial.put(ENT_A, entityWithAttrs(ENT_A,
                Map.of("on", new StringValue("true")), Availability.AVAILABLE,
                5L, T0, T1, T2, null, false));
        initial.put(ENT_B, entityWithAttrs(ENT_B,
                Map.of("brightness", new StringValue("80")), Availability.UNAVAILABLE,
                3L, T0, T1, T2, null, false));
        byte[] bytes = serializer.serialize(initial, 2, null, null, null);
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, bytes);

        // Stage 2: a fresh store reads the checkpoint on construction.
        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);

        assertThat(store.getAll()).hasSize(2).containsKeys(ENT_A, ENT_B);
        assertThat(store.get(ENT_A)).isPresent();
        assertThat(((StringValue) store.get(ENT_A).get().attributes().get("on")).value())
                .isEqualTo("true");
        assertThat(store.loadedProjectionVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("initializes empty when no checkpoint exists")
    void initializesEmptyFromMissingCheckpoint() {
        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);

        assertThat(store.getAll()).isEmpty();
        assertThat(store.loadedProjectionVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("initializes empty when checkpoint payload is byte[0] (M3.5a stub case)")
    void initializesEmptyFromByteZeroCheckpoint() {
        checkpointStore.writeCheckpoint(VIEW_NAME, 50L, new byte[0]);

        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);

        assertThat(store.getAll()).isEmpty();
        assertThat(store.loadedProjectionVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("initializes empty when checkpoint payload is corrupt")
    void initializesEmptyFromCorruptCheckpoint() {
        // Garbage bytes — Jackson cannot parse. The store catches and starts empty.
        checkpointStore.writeCheckpoint(VIEW_NAME, 50L, new byte[]{'?', '?', '?'});

        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);

        assertThat(store.getAll()).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────
    // Checkpoint serialization
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("serialize produces a checkpoint that deserializes to the same state")
    void serializeProducesValidCheckpointData() {
        SqliteStateStore store = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);
        store.put(ENT_A, entityWithAttrs(ENT_A,
                Map.of("on", new StringValue("true")), Availability.AVAILABLE,
                1L, T0, T0, T0, null, false));
        store.put(ENT_B, entityWithAttrs(ENT_B,
                Map.of("temperature_c", new StringValue("21.5")), Availability.UNAVAILABLE,
                2L, T0, T1, T2, null, false));

        byte[] bytes = store.serializeCheckpoint(3);
        CheckpointData parsed = serializer.deserialize(bytes);

        assertThat(parsed.stateMap()).hasSize(2).containsKeys(ENT_A, ENT_B);
        assertThat(parsed.projectionVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("crash recovery: serialize -> write checkpoint -> reload restores state")
    void crashRecoveryEndToEnd() {
        // Phase 1: live store, three entities, checkpoint flushed.
        SqliteStateStore live = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);
        live.put(ENT_A, entityWithAttrs(ENT_A,
                Map.of("on", new StringValue("true")), Availability.AVAILABLE,
                1L, T0, T0, T0, null, false));
        live.put(ENT_B, entityWithAttrs(ENT_B,
                Map.of(), Availability.UNAVAILABLE, 1L, T0, T0, T0, null, false));
        live.put(ENT_C, entityWithAttrs(ENT_C,
                Map.of("brightness", new StringValue("128")), Availability.AVAILABLE,
                7L, T0, T1, T2, null, false));
        byte[] checkpoint = live.serializeCheckpoint(1);
        checkpointStore.writeCheckpoint(VIEW_NAME, 250L, checkpoint);

        // Phase 2: simulate crash — discard the live store; a fresh store
        // initializes from the persisted checkpoint.
        SqliteStateStore recovered = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);

        assertThat(recovered.getAll()).hasSize(3)
                .containsKeys(ENT_A, ENT_B, ENT_C);
        assertThat(((StringValue) recovered.get(ENT_C).get().attributes().get("brightness")).value())
                .isEqualTo("128");
        assertThat(recovered.get(ENT_C).get().stateVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("null attribute values survive the serialize → reload round trip")
    void nullAttributesSurviveRoundTrip() {
        SqliteStateStore live = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);
        Map<String, AttributeValue> attrs = new HashMap<>();
        attrs.put("brightness", null);
        attrs.put("on", new StringValue("true"));
        live.put(ENT_A, entityWithAttrs(ENT_A, attrs, Availability.AVAILABLE,
                1L, T0, T0, T0, null, false));

        byte[] checkpoint = live.serializeCheckpoint(1);
        checkpointStore.writeCheckpoint(VIEW_NAME, 1L, checkpoint);

        SqliteStateStore recovered = new SqliteStateStore(checkpointStore, serializer, VIEW_NAME);
        Map<String, AttributeValue> recoveredAttrs =
                recovered.get(ENT_A).orElseThrow().attributes();

        assertThat(recoveredAttrs).containsKeys("on", "brightness");
        assertThat(recoveredAttrs.get("brightness")).isNull();
        assertThat(recoveredAttrs.get("on")).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private static EntityState entityWithAttrs(
            EntityId id,
            Map<String, AttributeValue> attrs,
            Availability availability,
            long stateVersion,
            Instant lastChanged,
            Instant lastUpdated,
            Instant lastReported,
            Instant staleAfter,
            boolean stale) {
        return new EntityState(
                id, attrs, availability, stateVersion,
                lastChanged, lastUpdated, lastReported,
                staleAfter, stale);
    }

    /**
     * Minimal in-memory {@link ViewCheckpointStore} used by these tests.
     * The persistence module's testFixtures source set does not export the
     * state-store {@code InMemoryViewCheckpointStore} fixture, and pulling
     * it in adds an unnecessary cross-module test-fixture dependency for
     * tests whose focus is the {@link SqliteStateStore} integration with
     * {@link CheckpointSerializer} — not the {@link ViewCheckpointStore}
     * implementation itself (which has its own dedicated contract test).
     */
    private static final class InMemoryViewCheckpointStoreLocal implements ViewCheckpointStore {

        private final ConcurrentHashMap<String, CheckpointRecord> records = new ConcurrentHashMap<>();

        @Override
        public void writeCheckpoint(String viewName, long position, byte[] data) {
            byte[] copy = (data == null) ? null : data.clone();
            records.put(viewName, new CheckpointRecord(
                    viewName, position, copy, T0, 1));
        }

        @Override
        public Optional<CheckpointRecord> readLatestCheckpoint(String viewName) {
            CheckpointRecord r = records.get(viewName);
            if (r == null) {
                return Optional.empty();
            }
            byte[] copy = (r.data() == null) ? null : r.data().clone();
            return Optional.of(new CheckpointRecord(
                    r.viewName(), r.position(), copy, r.writtenAt(), r.projectionVersion()));
        }
    }
}
