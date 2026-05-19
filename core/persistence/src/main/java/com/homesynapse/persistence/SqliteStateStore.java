/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.state.CheckpointRecord;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateStore;
import com.homesynapse.state.ViewCheckpointStore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link StateStore} backed by a {@link ConcurrentHashMap} plus
 * checkpoint-based durability (Doc 03 §3.6, DEC-M3-04, AMD-41 §3.2.3).
 *
 * <p>This store is functionally equivalent to the testFixtures
 * {@code InMemoryStateStore}: reads and writes hit the in-memory map
 * directly with no SQLite I/O on the hot path. Durability is provided by
 * the {@link com.homesynapse.state.StateProjection}'s checkpoint cadence
 * — at every {@code FixedCheckpointPolicy} firing, the projection captures
 * the full state map via {@link #serialize()} and writes it through the
 * {@link ViewCheckpointStore}. On startup, the store rehydrates its map
 * from the latest checkpoint (Jackson-deserialized via
 * {@link CheckpointSerializer}).</p>
 *
 * <h2>Why no entity_state SQLite table</h2>
 *
 * <p>The 100&micro;s {@code getState} target (Doc 03 §8) and 50,000 events/sec
 * replay rate cannot be met with per-mutation SQLite writes — the single
 * platform write thread would saturate. The checkpoint mechanism amortises
 * durability cost over hundreds of events (200 per AMD-38's
 * {@code FixedCheckpointPolicy.HOME_DEFAULT}). Recovery is by checkpoint
 * load + bounded forward replay from the checkpoint position — the same
 * model used by mature event-sourced projections (EventStoreDB, Axon,
 * Marten).</p>
 *
 * <h2>Initialization</h2>
 *
 * <p>Construction calls
 * {@link ViewCheckpointStore#readLatestCheckpoint(String)} once. If a
 * checkpoint is present and carries a non-empty {@code data} payload, the
 * payload is Jackson-deserialized and the map is populated from the
 * deserialized {@link CheckpointData#stateMap()}. If no checkpoint exists,
 * or the payload is empty (the M3.5a stub's {@code byte[0]}), the store
 * starts empty — the bus's REPLAY mechanism re-derives state from position
 * 0 in that case.</p>
 *
 * <p>Deserialization failures are logged at WARN and the store starts
 * empty — the projection treats this the same as "no checkpoint" and falls
 * back to a full replay.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Backed by {@link ConcurrentHashMap}: reads are lock-free,
 * virtual-thread-safe, and never block. Writes are atomic per-key. The
 * projection's subscriber virtual thread is the only writer in production.
 * {@link #serialize()} captures a snapshot via {@link #getAll()} which
 * itself returns a defensive unmodifiable copy.</p>
 *
 * <p>Package-private — composition wiring constructs the store and exposes
 * it through the public {@link StateStore} interface only.</p>
 *
 * @see CheckpointSerializer
 * @see ViewCheckpointStore
 * @see com.homesynapse.state.StateProjection
 */
final class SqliteStateStore implements StateStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteStateStore.class);

    private final String viewName;
    private final CheckpointSerializer serializer;
    private final ConcurrentHashMap<EntityId, EntityState> backing = new ConcurrentHashMap<>();

    /**
     * Most recently loaded or written {@code projectionVersion}. Captured
     * during init from the deserialized checkpoint; {@code 0} when there is
     * no prior checkpoint. Exposed via {@link #loadedProjectionVersion()}
     * so the projection's reconciliation check (AMD-41 §3.2.4) can read the
     * embedded version from the data blob rather than the
     * {@link CheckpointRecord#projectionVersion()} sentinel (which the
     * persistence stores hardcode to {@code 1}; see
     * {@link SqliteViewCheckpointStore}).
     */
    private volatile int loadedProjectionVersion;

    /**
     * Constructs a SQLite-backed (checkpoint-durable) state store and
     * eagerly initializes from the supplied {@link ViewCheckpointStore}.
     *
     * @param viewCheckpointStore  durable checkpoint storage; never
     *                             {@code null}
     * @param checkpointSerializer Jackson serializer; never {@code null}
     * @param viewName             stable view identifier used as the
     *                             checkpoint key; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    SqliteStateStore(
            ViewCheckpointStore viewCheckpointStore,
            CheckpointSerializer checkpointSerializer,
            String viewName) {
        Objects.requireNonNull(viewCheckpointStore,
                "viewCheckpointStore must not be null");
        this.serializer = Objects.requireNonNull(checkpointSerializer,
                "checkpointSerializer must not be null");
        this.viewName = Objects.requireNonNull(viewName,
                "viewName must not be null");

        loadFromCheckpoint(viewCheckpointStore);
    }

    // ──────────────────────────────────────────────────────────────────
    // StateStore
    // ──────────────────────────────────────────────────────────────────

    @Override
    public Optional<EntityState> get(EntityId entityId) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        return Optional.ofNullable(backing.get(entityId));
    }

    @Override
    public void put(EntityId entityId, EntityState state) {
        Objects.requireNonNull(entityId, "entityId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        backing.put(entityId, state);
    }

    @Override
    public Map<EntityId, EntityState> getAll() {
        // LinkedHashMap snapshot — preserves iteration order and survives
        // null-attribute-value defensive copies (Map.copyOf would throw on
        // null map values, which EntityState.attributes() may contain per
        // the Doc 03 contract).
        return Collections.unmodifiableMap(new LinkedHashMap<>(backing));
    }

    @Override
    public void clear() {
        backing.clear();
        loadedProjectionVersion = 0;
    }

    // ──────────────────────────────────────────────────────────────────
    // Checkpoint integration
    // ──────────────────────────────────────────────────────────────────

    /**
     * Serializes the current in-memory state to a {@code byte[]} suitable
     * for {@link ViewCheckpointStore#writeCheckpoint(String, long, byte[])}.
     *
     * <p>The {@link com.homesynapse.state.StateProjection} owns the
     * checkpoint cadence (AMD-38). On each checkpoint firing, the
     * projection calls {@code stateStore.serialize()} to capture a
     * snapshot, then writes the resulting bytes via the checkpoint store
     * at the current cursor position.</p>
     *
     * @param projectionVersion the running projection's code version,
     *                          embedded in the checkpoint payload for the
     *                          AMD-41 §3.2.4 reconciliation check
     * @return the serialized bytes; never {@code null}
     */
    byte[] serialize(int projectionVersion) {
        Map<EntityId, EntityState> snapshot = new LinkedHashMap<>(backing);
        return serializer.serialize(snapshot, projectionVersion, null, null, null);
    }

    /**
     * Returns the {@code projectionVersion} carried by the most recently
     * loaded (or written) checkpoint payload. {@code 0} when no prior
     * checkpoint exists or the checkpoint payload was empty.
     *
     * <p>This is the real projection version per AMD-41 §3.2.4 — the
     * {@link ViewCheckpointStore} implementations hardcode
     * {@link CheckpointRecord#projectionVersion()} to {@code 1}; the
     * authoritative value lives inside the data blob.</p>
     *
     * @return the loaded projection version
     */
    int loadedProjectionVersion() {
        return loadedProjectionVersion;
    }

    /**
     * Returns the view name passed at construction.
     *
     * @return the view name; never {@code null}
     */
    String viewName() {
        return viewName;
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    private void loadFromCheckpoint(ViewCheckpointStore checkpointStore) {
        Optional<CheckpointRecord> latest = checkpointStore.readLatestCheckpoint(viewName);
        if (latest.isEmpty()) {
            LOG.debug("No prior checkpoint for view {} — starting empty", viewName);
            return;
        }
        CheckpointRecord record = latest.get();
        byte[] data = record.data();
        if (data == null || data.length == 0) {
            LOG.debug("Empty checkpoint payload for view {} — starting empty", viewName);
            return;
        }
        CheckpointData parsed;
        try {
            parsed = serializer.deserialize(data);
        } catch (IllegalStateException ise) {
            LOG.warn("Checkpoint deserialization failed for view {} — starting empty: {}",
                    viewName, ise.getMessage());
            return;
        }
        backing.putAll(parsed.stateMap());
        loadedProjectionVersion = parsed.projectionVersion();
        LOG.info("Loaded {} entities from checkpoint for view {} at position {}; "
                        + "projectionVersion={}",
                parsed.stateMap().size(), viewName, record.position(),
                parsed.projectionVersion());
    }
}
