/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.state.CheckpointRecord;
import com.homesynapse.state.ViewCheckpointStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed implementation of {@link ViewCheckpointStore} against the
 * V001 {@code view_checkpoints} table in {@code homesynapse-events.db}
 * (Doc 04 §3.12). Provides durable storage for materialized view checkpoint
 * data so that the State Store (and future projections) can recover from a
 * crash by loading the last checkpoint and replaying only events published
 * after the checkpoint position — dramatically reducing cold-start time.
 *
 * <p>This class is the materialized-view analog of
 * {@link SqliteCheckpointStore}: both route JDBC work through
 * {@link DatabaseExecutor} so that writes are serialized by the single
 * platform write thread (LTD-03) and reads are bounded by the read
 * executor's thread pool with one {@link ThreadLocal}-confined
 * {@link Connection} per thread (the AMD-26/AMD-27 sqlite-jdbc JNI
 * carrier-pinning mitigation). Virtual thread callers submit work and
 * park — they never touch a JDBC object directly.</p>
 *
 * <p><strong>Distinction from {@link SqliteCheckpointStore}.</strong>
 * {@code SqliteCheckpointStore} implements the event-bus
 * {@link com.homesynapse.event.bus.CheckpointStore}, which stores a single
 * {@code long} position per subscriber in the {@code subscriber_checkpoints}
 * table. This class implements {@link ViewCheckpointStore}, which stores
 * opaque serialized view state ({@code byte[]}) keyed by view name in the
 * {@code view_checkpoints} table. The two are complementary — Doc 04 §3.12
 * describes how they participate in same-transaction writes during
 * checkpoint operations (that composition is M2.8+ scope).</p>
 *
 * <p><strong>Write priority.</strong> Checkpoint writes flow through the
 * {@link WritePriority#STATE_PROJECTION} tier — the same tier used by
 * subscriber checkpoint writes in {@link SqliteCheckpointStore}.</p>
 *
 * <p><strong>Opaque data.</strong> The persistence layer stores checkpoint
 * data without interpreting it (Doc 04). The {@code data} column is
 * {@code BLOB NOT NULL}. The {@link CheckpointRecord#projectionVersion()}
 * field is populated with a default value of {@code 1} on read — the
 * actual projection version is embedded within the opaque {@code data}
 * blob by the State Store and extracted at checkpoint load time
 * (Doc 03 §3.6, AMD-10).</p>
 *
 * <p><strong>Atomic upsert.</strong> Checkpoint writes use
 * {@code INSERT OR REPLACE} on the primary-key column {@code view_name},
 * so that the first checkpoint inserts a new row and every subsequent
 * checkpoint replaces the existing row. Only the latest checkpoint per
 * view is retained — no history.</p>
 *
 * <p>Package-private — external modules construct and consume this store
 * through the higher-level {@code PersistenceLifecycle} facade, which
 * returns the public {@link ViewCheckpointStore} interface only.</p>
 *
 * @see DatabaseExecutor
 * @see WritePriority#STATE_PROJECTION
 * @see TimeConversion
 * @see SqliteCheckpointStore
 */
final class SqliteViewCheckpointStore implements ViewCheckpointStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteViewCheckpointStore.class);

    /**
     * Default projection version populated on read. The persistence layer
     * does not interpret checkpoint content — the real projection version
     * is embedded within the opaque {@code data} blob. This sentinel value
     * satisfies the {@link CheckpointRecord} constructor and the contract
     * test assertion ({@code projectionVersion >= 1}).
     */
    private static final int DEFAULT_PROJECTION_VERSION = 1;

    /**
     * Atomic upsert into the view checkpoint table. Uses {@code INSERT OR REPLACE}
     * against the primary-key column {@code view_name} so that the first
     * checkpoint for a view inserts a new row and every subsequent checkpoint
     * replaces the existing row's {@code position}, {@code data}, and
     * {@code updated_at} fields in one statement — no read-then-write gap.
     */
    private static final String UPSERT_SQL = """
            INSERT OR REPLACE INTO view_checkpoints (
                view_name, position, data, updated_at
            ) VALUES (?, ?, ?, ?)
            """;

    /**
     * Reads the full checkpoint for a view. Selects all columns needed to
     * construct a {@link CheckpointRecord}.
     */
    private static final String SELECT_SQL = """
            SELECT view_name, position, data, updated_at
            FROM view_checkpoints
            WHERE view_name = ?
            """;

    private final DatabaseExecutor dbExecutor;
    private final Clock clock;

    /**
     * Round-robin index used to assign a read {@link Connection} to each
     * read-pool thread on first use. Matches the
     * {@link SqliteCheckpointStore} pattern.
     */
    private final AtomicInteger readConnectionCursor = new AtomicInteger();

    /**
     * Thread-confined read connection — each {@code hs-read-*} thread is
     * assigned a single {@link Connection} on its first read and reuses it
     * thereafter. AMD-26/AMD-27 mitigation for sqlite-jdbc JNI carrier
     * pinning.
     */
    private final ThreadLocal<Connection> readConnection = new ThreadLocal<>();

    /**
     * Constructs a SQLite-backed view checkpoint store over the given
     * database executor.
     *
     * @param dbExecutor the database executor providing write/read
     *                   coordination and the write connection; never
     *                   {@code null} and must be started
     * @param clock      the clock for {@code updated_at} stamping;
     *                   never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    SqliteViewCheckpointStore(DatabaseExecutor dbExecutor, Clock clock) {
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ──────────────────────────────────────────────────────────────────
    // ViewCheckpointStore
    // ──────────────────────────────────────────────────────────────────

    @Override
    public void writeCheckpoint(String viewName, long position, byte[] data) {
        Objects.requireNonNull(viewName, "viewName must not be null");
        Objects.requireNonNull(data, "data must not be null");
        if (position < 0L) {
            throw new IllegalArgumentException(
                    "position must be >= 0, got " + position);
        }

        // Capture timestamp on caller thread — deterministic under injected Clock.
        long updatedAtMicros = TimeConversion.toMicros(clock.instant());

        dbExecutor.writeCoordinator().submit(WritePriority.STATE_PROJECTION, () -> {
            Connection conn = dbExecutor.writeConnection();
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
                ps.setString(1, viewName);
                ps.setLong(2, position);
                ps.setBytes(3, data);
                ps.setLong(4, updatedAtMicros);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<CheckpointRecord> readLatestCheckpoint(String viewName) {
        Objects.requireNonNull(viewName, "viewName must not be null");

        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = acquireReadConnection();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
                ps.setString(1, viewName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        CheckpointRecord record = new CheckpointRecord(
                                rs.getString("view_name"),
                                rs.getLong("position"),
                                rs.getBytes("data"),
                                TimeConversion.fromMicros(rs.getLong("updated_at")),
                                DEFAULT_PROJECTION_VERSION);
                        return Optional.of(record);
                    }
                    return Optional.empty();
                }
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // Read-thread machinery
    // ──────────────────────────────────────────────────────────────────

    /**
     * Resolves (and on first call, binds) the thread-confined read
     * {@link Connection} for the current read-pool thread. Called from
     * inside a {@code readExecutor.execute(...)} lambda — always on an
     * {@code hs-read-*} platform thread, never on a virtual thread or
     * the write thread.
     */
    private Connection acquireReadConnection() {
        Connection conn = readConnection.get();
        if (conn == null) {
            List<Connection> pool = dbExecutor.readConnections();
            int index = readConnectionCursor.getAndIncrement() % pool.size();
            if (index < 0) {
                // Integer overflow — rare, but keep the index in range.
                index = Math.abs(index % pool.size());
            }
            conn = pool.get(index);
            readConnection.set(conn);
            LOG.debug("Bound read connection #{} to thread {}",
                    index, Thread.currentThread().getName());
        }
        return conn;
    }

    // ──────────────────────────────────────────────────────────────────
    // Test-support utilities (package-private)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Clears the current thread's {@link ThreadLocal} read-connection
     * binding. Exposed for test tear-down — the contract test fixture
     * discards the store between test methods, and the test thread that
     * ran the previous test would otherwise retain a stale connection
     * reference that points at a closed {@link DatabaseExecutor}'s
     * read pool. Not used by production code.
     */
    void clearThreadLocalForTesting() {
        readConnection.remove();
    }
}
