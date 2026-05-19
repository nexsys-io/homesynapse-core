/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.DeadLetter;
import com.homesynapse.platform.identity.Ulid;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed durable dead-letter store against the V002
 * {@code subscriber_dead_letters} table (AMD-36).
 *
 * <p>Provides the persistent overflow that backs the event-bus's in-memory
 * {@link com.homesynapse.event.bus.SubscriberDlq} ring. Production wiring
 * adapts a method reference on an instance of this class into a
 * {@link com.homesynapse.event.bus.PersistentDlqWriter} so the bus has no
 * compile-time dependency on persistence.</p>
 *
 * <p>Mirrors the executor discipline of {@link SqliteCheckpointStore} and
 * {@link SqliteViewCheckpointStore}: write operations submit through
 * {@link DatabaseExecutor#writeCoordinator()} at the
 * {@link WritePriority#STATE_PROJECTION} tier, read operations route through
 * {@link DatabaseExecutor#readExecutor()} with a {@code ThreadLocal}-confined
 * {@link Connection} per read-pool thread (the AMD-26/27 sqlite-jdbc JNI
 * carrier-pinning mitigation).</p>
 *
 * <p><strong>Idempotency.</strong> Park uses SQLite's
 * {@code INSERT ... ON CONFLICT(subscriber_id, event_position) DO UPDATE SET ...}
 * pattern. The V002 schema enforces {@code UNIQUE(subscriber_id,
 * event_position)} — a duplicate park for an event the subscriber has
 * previously failed updates {@code attempt_count}, {@code last_attempt_at},
 * and the cause/diagnostic columns rather than inserting a new row. The
 * {@code first_seen_at} column and the assigned {@code dlq_id} remain frozen
 * across upsert.</p>
 *
 * <p>Package-private — external modules construct via the composition root
 * and consume through the {@link com.homesynapse.event.bus.PersistentDlqWriter}
 * functional interface.</p>
 *
 * @see DatabaseExecutor
 * @see WritePriority#STATE_PROJECTION
 * @see com.homesynapse.event.bus.PersistentDlqWriter
 */
final class SqliteDeadLetterStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteDeadLetterStore.class);

    /**
     * Upsert SQL. {@code ON CONFLICT} targets the
     * {@code UNIQUE(subscriber_id, event_position)} constraint declared in
     * V002. On conflict, the existing row's mutable fields are updated and
     * {@code first_seen_at} / {@code dlq_id} stay frozen.
     */
    private static final String UPSERT_SQL = """
            INSERT INTO subscriber_dead_letters (
                subscriber_id, sequence_key, event_position, event_id,
                cause_class, cause_message, attempt_count,
                first_seen_at, last_attempt_at, diagnostics
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(subscriber_id, event_position) DO UPDATE SET
                attempt_count   = excluded.attempt_count,
                last_attempt_at = excluded.last_attempt_at,
                cause_class     = excluded.cause_class,
                cause_message   = excluded.cause_message,
                diagnostics     = excluded.diagnostics
            """;

    /**
     * Per-subscriber listing, ordered by {@code last_attempt_at} so admin
     * tooling sees the freshest failures first.
     */
    private static final String SELECT_BY_SUBSCRIBER_SQL = """
            SELECT dlq_id, subscriber_id, sequence_key, event_position, event_id,
                   cause_class, cause_message, attempt_count,
                   first_seen_at, last_attempt_at, diagnostics
            FROM subscriber_dead_letters
            WHERE subscriber_id = ?
            ORDER BY last_attempt_at DESC, dlq_id DESC
            """;

    /**
     * Single-row lookup keyed by the UNIQUE constraint columns.
     */
    private static final String SELECT_BY_POSITION_SQL = """
            SELECT dlq_id, subscriber_id, sequence_key, event_position, event_id,
                   cause_class, cause_message, attempt_count,
                   first_seen_at, last_attempt_at, diagnostics
            FROM subscriber_dead_letters
            WHERE subscriber_id = ? AND event_position = ?
            """;

    private static final String COUNT_BY_SUBSCRIBER_SQL =
            "SELECT COUNT(*) FROM subscriber_dead_letters WHERE subscriber_id = ?";

    private final DatabaseExecutor dbExecutor;

    private final AtomicInteger readConnectionCursor = new AtomicInteger();
    private final ThreadLocal<Connection> readConnection = new ThreadLocal<>();

    /**
     * Constructs a SQLite-backed DLQ store over the given database executor.
     *
     * @param dbExecutor the database executor providing write/read
     *                   coordination and connections; never {@code null} and
     *                   must be started
     * @throws NullPointerException if {@code dbExecutor} is {@code null}
     */
    SqliteDeadLetterStore(DatabaseExecutor dbExecutor) {
        this.dbExecutor = Objects.requireNonNull(dbExecutor,
                "dbExecutor must not be null");
    }

    // ──────────────────────────────────────────────────────────────────
    // Write path
    // ──────────────────────────────────────────────────────────────────

    /**
     * Parks (or upserts) a dead-letter entry.
     *
     * <p>Routes through {@code WriteCoordinator.submit(STATE_PROJECTION, ...)}
     * — same priority tier as the subscriber- and view-checkpoint stores.
     * Parameter validation runs on the caller's thread before the work
     * submission, so callers see the typed exceptions directly rather than
     * via the executor's wrapping.</p>
     *
     * @param deadLetter the dead-letter to park; never {@code null}
     * @throws NullPointerException if {@code deadLetter} is {@code null}
     */
    void park(DeadLetter deadLetter) {
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");

        dbExecutor.writeCoordinator().submit(WritePriority.STATE_PROJECTION, () -> {
            Connection conn = dbExecutor.writeConnection();
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
                ps.setString(1, deadLetter.subscriberId());
                ps.setString(2, deadLetter.sequenceKey());
                ps.setLong(3, deadLetter.eventPosition());
                ps.setBytes(4, deadLetter.eventId().toBytes());
                ps.setString(5, deadLetter.causeClass());
                ps.setString(6, deadLetter.causeMessage());
                ps.setInt(7, deadLetter.attemptCount());
                ps.setLong(8, TimeConversion.toMicros(deadLetter.firstSeenAt()));
                ps.setLong(9, TimeConversion.toMicros(deadLetter.lastAttemptAt()));
                if (deadLetter.diagnostics() == null) {
                    ps.setNull(10, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(10, deadLetter.diagnostics());
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // Read path
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns all dead-letter entries parked for {@code subscriberId},
     * ordered by {@code lastAttemptAt} descending.
     *
     * @param subscriberId the subscriber identifier; never {@code null}
     * @return entries for this subscriber; empty list when unknown
     */
    List<DeadLetter> findBySubscriber(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");

        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = acquireReadConnection();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_SUBSCRIBER_SQL)) {
                ps.setString(1, subscriberId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<DeadLetter> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(rowToDeadLetter(rs));
                    }
                    return List.copyOf(results);
                }
            }
        });
    }

    /**
     * Returns the entry for {@code (subscriberId, eventPosition)}, if one
     * has been parked.
     *
     * @param subscriberId  the subscriber identifier; never {@code null}
     * @param eventPosition the event global position
     * @return the entry, or empty when no row exists
     */
    Optional<DeadLetter> findByPosition(String subscriberId, long eventPosition) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");

        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = acquireReadConnection();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_POSITION_SQL)) {
                ps.setString(1, subscriberId);
                ps.setLong(2, eventPosition);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rowToDeadLetter(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    /**
     * Returns the count of parked entries for {@code subscriberId}.
     *
     * @param subscriberId the subscriber identifier; never {@code null}
     * @return the count; zero when unknown
     */
    int countBySubscriber(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");

        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = acquireReadConnection();
            try (PreparedStatement ps = conn.prepareStatement(COUNT_BY_SUBSCRIBER_SQL)) {
                ps.setString(1, subscriberId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                    return 0;
                }
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    private static DeadLetter rowToDeadLetter(ResultSet rs) throws java.sql.SQLException {
        return new DeadLetter(
                rs.getLong("dlq_id"),
                rs.getString("subscriber_id"),
                rs.getString("sequence_key"),
                rs.getLong("event_position"),
                Ulid.fromBytes(rs.getBytes("event_id")),
                rs.getString("cause_class"),
                rs.getString("cause_message"),
                rs.getInt("attempt_count"),
                TimeConversion.fromMicros(rs.getLong("first_seen_at")),
                TimeConversion.fromMicros(rs.getLong("last_attempt_at")),
                rs.getString("diagnostics"));
    }

    /**
     * Resolves (and on first call, binds) the thread-confined read
     * {@link Connection} for the current read-pool thread. Mirrors
     * {@link SqliteCheckpointStore#acquireReadConnection()}.
     */
    private Connection acquireReadConnection() {
        Connection conn = readConnection.get();
        if (conn == null) {
            List<Connection> pool = dbExecutor.readConnections();
            int index = readConnectionCursor.getAndIncrement() % pool.size();
            if (index < 0) {
                index = Math.abs(index % pool.size());
            }
            conn = pool.get(index);
            readConnection.set(conn);
            LOG.debug("Bound DLQ read connection #{} to thread {}",
                    index, Thread.currentThread().getName());
        }
        return conn;
    }

    /**
     * Clears the current thread's {@link ThreadLocal} read-connection binding.
     * Exposed for test tear-down — see {@link SqliteCheckpointStore} for the
     * rationale.
     */
    void clearThreadLocalForTesting() {
        readConnection.remove();
    }
}
