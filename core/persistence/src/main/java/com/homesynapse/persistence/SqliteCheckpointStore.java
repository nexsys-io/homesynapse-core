/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.CheckpointStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed implementation of {@link CheckpointStore} against the
 * V001 {@code subscriber_checkpoints} table in {@code homesynapse-events.db}
 * (Doc 01 §4.2, §8.1). Provides the durable backing for every event-bus
 * subscriber's {@code last_delivered_position} so that a subscriber which
 * crashes, restarts, or re-deploys can resume reading from the exact
 * position at which it last acknowledged delivery — no gaps, no replays.
 *
 * <p>This class is the event-bus subscriber analog of
 * {@link SqliteEventStore}: both route JDBC work through
 * {@link DatabaseExecutor} so that writes are serialized by the single
 * platform write thread (LTD-03) and reads are bounded by the read
 * executor's thread pool with one {@link ThreadLocal}-confined
 * {@link Connection} per thread (the AMD-26/AMD-27 sqlite-jdbc JNI
 * carrier-pinning mitigation). Virtual thread callers submit work and
 * park — they never touch a JDBC object directly.</p>
 *
 * <p><strong>Write priority.</strong> Checkpoint writes flow through the
 * {@link WritePriority#STATE_PROJECTION} tier. The write-coordinator
 * priority queue sits between event publishes ({@code EVENT_PUBLISH},
 * tier&nbsp;1) and WAL maintenance ({@code WAL_CHECKPOINT}, tier&nbsp;3),
 * so a checkpoint write cannot starve an event publish but can always
 * make progress against retention and backup work. This is the same tier
 * used by view-state projection writes in the state-store subsystem.</p>
 *
 * <p><strong>Atomic upsert.</strong> Checkpoint writes use
 * {@code INSERT OR REPLACE} on the primary-key column {@code subscriber_id},
 * which SQLite implements as an atomic transaction: the row is either
 * inserted (first checkpoint) or the existing row's {@code last_position}
 * and {@code last_updated} are replaced in a single statement. There is
 * no read-then-write window, no need for an explicit transaction, and no
 * {@code last_delivered_position} race can occur because every checkpoint
 * write runs on the same single write thread.</p>
 *
 * <p><strong>Time stamping.</strong> The {@code last_updated} column is
 * stored as microseconds since the Unix epoch via
 * {@link TimeConversion#toMicros(java.time.Instant)}, matching the
 * convention used by the domain-event store for {@code ingest_time} and
 * {@code event_time}. The timestamp is derived from the injected
 * {@link Clock} so tests can pin the value to a deterministic fixed instant
 * and the {@code NO_DIRECT_TIME_ACCESS} ArchUnit rule is never violated.</p>
 *
 * <p><strong>Unknown-subscriber semantics.</strong> Per the
 * {@link CheckpointStore} contract, a {@link #readCheckpoint} for a
 * {@code subscriber_id} that has never checkpointed returns {@code 0L}.
 * This matches the semantics used at subscriber startup: position zero
 * means "no events have been delivered yet, start from the beginning of
 * the log." There is no separate {@code absent} vs. {@code zero}
 * distinction at the API level — both are sentinel values indicating
 * fresh subscribers.</p>
 *
 * <p>Package-private — external modules construct and consume this store
 * through the higher-level {@code PersistenceLifecycle} facade, which
 * returns the public {@link CheckpointStore} interface only.</p>
 *
 * @see DatabaseExecutor
 * @see WritePriority#STATE_PROJECTION
 * @see TimeConversion
 */
final class SqliteCheckpointStore implements CheckpointStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteCheckpointStore.class);

    /**
     * Atomic upsert into the checkpoint table. Uses {@code INSERT OR REPLACE}
     * against the primary-key column {@code subscriber_id} so that the
     * first checkpoint for a subscriber inserts a new row and every
     * subsequent checkpoint replaces the existing row's
     * {@code last_position} and {@code last_updated} fields in one
     * statement — no read-then-write gap.
     */
    private static final String UPSERT_SQL = """
            INSERT OR REPLACE INTO subscriber_checkpoints (
                subscriber_id, last_position, last_updated
            ) VALUES (?, ?, ?)
            """;

    /**
     * Reads the persisted {@code last_position} for a subscriber. Returns
     * no rows when the subscriber has never checkpointed — the
     * {@link #readCheckpoint} contract maps that to the {@code 0L}
     * sentinel.
     */
    private static final String SELECT_SQL =
            "SELECT last_position FROM subscriber_checkpoints WHERE subscriber_id = ?";

    private final DatabaseExecutor dbExecutor;
    private final Clock clock;

    /**
     * Round-robin index used to assign a read {@link Connection} to each
     * read-pool thread on first use. Incremented atomically from any read
     * thread. Because the read pool size matches the read-connection list
     * size, every read thread ends up with a unique connection.
     */
    private final AtomicInteger readConnectionCursor = new AtomicInteger();

    /**
     * Thread-confined read connection — each {@code hs-read-*} thread is
     * assigned a single {@link Connection} on its first read and reuses it
     * thereafter for the lifetime of the store. This is the AMD-26/AMD-27
     * mitigation for sqlite-jdbc JNI carrier pinning: confining each
     * connection to a single platform thread prevents concurrent JNI
     * access to the same native sqlite3 pointer.
     */
    private final ThreadLocal<Connection> readConnection = new ThreadLocal<>();

    /**
     * Constructs a SQLite-backed checkpoint store over the given database
     * executor.
     *
     * @param dbExecutor the database executor providing write/read
     *                   coordination and the write connection; never
     *                   {@code null} and must be started
     * @param clock      the clock for {@code last_updated} stamping;
     *                   never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    SqliteCheckpointStore(DatabaseExecutor dbExecutor, Clock clock) {
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ──────────────────────────────────────────────────────────────────
    // CheckpointStore
    // ──────────────────────────────────────────────────────────────────

    @Override
    public long readCheckpoint(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");

        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = acquireReadConnection();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
                ps.setString(1, subscriberId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                    // Unknown subscriber — contract specifies zero sentinel.
                    return 0L;
                }
            }
        });
    }

    @Override
    public void writeCheckpoint(String subscriberId, long globalPosition) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        if (globalPosition < 0L) {
            throw new IllegalArgumentException(
                    "globalPosition must be >= 0, got " + globalPosition);
        }

        long lastUpdatedMicros = TimeConversion.toMicros(clock.instant());

        dbExecutor.writeCoordinator().submit(WritePriority.STATE_PROJECTION, () -> {
            Connection conn = dbExecutor.writeConnection();
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
                ps.setString(1, subscriberId);
                ps.setLong(2, globalPosition);
                ps.setLong(3, lastUpdatedMicros);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // Read-thread machinery
    // ──────────────────────────────────────────────────────────────────

    /**
     * Resolves (and on first call, binds) the thread-confined read
     * {@link Connection} for the current read-pool thread. Called from
     * inside a {@code readExecutor.execute(...)} lambda — i.e. always
     * on an {@code hs-read-*} platform thread, never on a virtual
     * thread or the write thread.
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
