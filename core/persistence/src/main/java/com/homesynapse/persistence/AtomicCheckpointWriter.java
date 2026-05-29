/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.bus.DeadLetter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atomically writes a subscriber checkpoint and a view checkpoint in a single
 * SQLite transaction. This guarantees that the subscriber's position and the
 * view's snapshot data are always consistent — if the process crashes during
 * the write, neither table is updated.
 *
 * <p>This is the persistence-layer implementation of the same-transaction
 * semantics specified in Doc 04 §3.12. The entire transaction is submitted
 * as a single unit to the {@link WriteCoordinator}, executing on the
 * dedicated platform write thread (AMD-26/27).
 *
 * <p><strong>Sync scope:</strong> Both {@code subscriber_checkpoints} and
 * {@code view_checkpoints} are LOCAL-ONLY tables. They do not participate
 * in cross-instance CRDT sync (INV-LF-05). Each HomeSynapse instance
 * maintains its own projection state. The event log ({@code events} table)
 * is the syncable artifact; projections are rebuilt locally from the
 * replicated event log. Do NOT add cr-sqlite replication to these tables.
 *
 * @see SqliteCheckpointStore
 * @see SqliteViewCheckpointStore
 */
final class AtomicCheckpointWriter {

    private static final Logger LOG = LoggerFactory.getLogger(AtomicCheckpointWriter.class);

    /**
     * Subscriber checkpoint upsert — same SQL as {@link SqliteCheckpointStore}.
     * Uses {@code INSERT OR REPLACE} against the primary-key column
     * {@code subscriber_id}.
     */
    private static final String SUBSCRIBER_SQL =
            "INSERT OR REPLACE INTO subscriber_checkpoints"
            + " (subscriber_id, last_position, last_updated) VALUES (?, ?, ?)";

    /**
     * View checkpoint upsert — same SQL as {@link SqliteViewCheckpointStore}.
     * Uses {@code INSERT OR REPLACE} against the primary-key column
     * {@code view_name}.
     */
    private static final String VIEW_SQL =
            "INSERT OR REPLACE INTO view_checkpoints"
            + " (view_name, position, data, updated_at) VALUES (?, ?, ?, ?)";

    /**
     * Dead-letter upsert — same shape as {@link SqliteDeadLetterStore}'s
     * {@code UPSERT_SQL}, targeting the V002
     * {@code UNIQUE(subscriber_id, event_position)} constraint. Used by
     * {@link #writeAtomicCheckpointWithDlqPark} so the subscriber checkpoint
     * advance, the view snapshot write, and the dead-letter park all commit
     * in a single SQLite transaction (AMD-36 atomicity).
     */
    private static final String DLQ_SQL = """
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

    private final DatabaseExecutor databaseExecutor;
    private final WriteCoordinator writeCoordinator;
    private final Clock clock;

    /**
     * Constructs an atomic checkpoint writer over the given database executor.
     *
     * @param databaseExecutor the database executor providing the write
     *                         connection and write coordinator; never
     *                         {@code null} and must be started
     * @param clock            the clock for timestamp stamping; never
     *                         {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    AtomicCheckpointWriter(DatabaseExecutor databaseExecutor, Clock clock) {
        this.databaseExecutor = Objects.requireNonNull(databaseExecutor,
                "databaseExecutor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.writeCoordinator = databaseExecutor.writeCoordinator();
    }

    /**
     * Atomically writes a subscriber checkpoint and a view checkpoint in a
     * single SQLite transaction. Both the subscriber's position and the view's
     * serialized state are updated together — if either fails, neither write
     * is committed.
     *
     * <p>The {@code position} value is written into BOTH tables:
     * {@code subscriber_checkpoints.last_position} and
     * {@code view_checkpoints.position}. This invariant is what makes crash
     * recovery correct — the subscriber's delivery position always matches
     * the view's snapshot position.</p>
     *
     * @param subscriberId the subscriber's stable string identifier
     *                     (e.g., {@code "state-projection"})
     * @param position     the {@code global_position} being checkpointed;
     *                     written to both tables
     * @param viewName     the view's stable identifier
     *                     (e.g., {@code "entity_state"})
     * @param viewData     the opaque serialized view snapshot
     * @throws NullPointerException     if {@code subscriberId},
     *                                  {@code viewName}, or {@code viewData}
     *                                  is {@code null}
     * @throws IllegalArgumentException if {@code position} is negative
     */
    void writeAtomicCheckpoint(String subscriberId, long position,
                               String viewName, byte[] viewData) {
        // Parameter validation on the caller's thread — matches M2.6/M2.7
        // pattern so callers see clean exception types without Future unwrap.
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        Objects.requireNonNull(viewName, "viewName must not be null");
        Objects.requireNonNull(viewData, "viewData must not be null");
        if (position < 0L) {
            throw new IllegalArgumentException(
                    "position must be >= 0, got " + position);
        }

        executeInTransaction(
                "atomic checkpoint write for subscriber=" + subscriberId
                        + " view=" + viewName,
                conn -> {
                    // 1. Subscriber checkpoint — same SQL as SqliteCheckpointStore
                    try (PreparedStatement ps = conn.prepareStatement(SUBSCRIBER_SQL)) {
                        ps.setString(1, subscriberId);
                        ps.setLong(2, position);
                        ps.setLong(3, TimeConversion.toMicros(clock.instant()));
                        ps.executeUpdate();
                    }

                    // 2. View checkpoint — same SQL as SqliteViewCheckpointStore
                    try (PreparedStatement ps = conn.prepareStatement(VIEW_SQL)) {
                        ps.setString(1, viewName);
                        ps.setLong(2, position);
                        ps.setBytes(3, viewData);
                        ps.setLong(4, TimeConversion.toMicros(clock.instant()));
                        ps.executeUpdate();
                    }
                });
    }

    /**
     * Atomically advances the subscriber checkpoint, writes the view
     * checkpoint, and parks (or upserts) a dead-letter entry in a single
     * SQLite transaction (AMD-36 atomicity).
     *
     * <p>This is the three-way extension of {@link #writeAtomicCheckpoint}
     * used when a subscriber's delivery has exhausted retries and the bus
     * needs to park the event AND advance past it atomically. If any of the
     * three writes fails, the transaction rolls back — neither the
     * checkpoint moves nor the dead-letter row appears, leaving the
     * subscriber free to retry on next iteration without a half-applied
     * state (INV-RF-04).</p>
     *
     * <p>Follows the same autoCommit-restore-in-finally pattern as
     * {@link #writeAtomicCheckpoint} so the write connection's autoCommit
     * default is preserved for sibling stores after this transaction
     * completes.</p>
     *
     * @param subscriberId the subscriber identifier; never {@code null}
     * @param position     the global position being checkpointed; non-negative
     * @param viewName     the view name; never {@code null}
     * @param viewData     the opaque view snapshot bytes; never {@code null}
     * @param deadLetter   the dead-letter to park; never {@code null}
     * @throws NullPointerException     if any reference parameter is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code position} is negative
     */
    void writeAtomicCheckpointWithDlqPark(
            String subscriberId, long position,
            String viewName, byte[] viewData,
            DeadLetter deadLetter) {
        Objects.requireNonNull(subscriberId, "subscriberId must not be null");
        Objects.requireNonNull(viewName, "viewName must not be null");
        Objects.requireNonNull(viewData, "viewData must not be null");
        Objects.requireNonNull(deadLetter, "deadLetter must not be null");
        if (position < 0L) {
            throw new IllegalArgumentException(
                    "position must be >= 0, got " + position);
        }

        executeInTransaction(
                "three-way atomic write for subscriber=" + subscriberId
                        + " view=" + viewName + " dlq.event=" + deadLetter.eventPosition(),
                conn -> {
                    // 1. Subscriber checkpoint
                    try (PreparedStatement ps = conn.prepareStatement(SUBSCRIBER_SQL)) {
                        ps.setString(1, subscriberId);
                        ps.setLong(2, position);
                        ps.setLong(3, TimeConversion.toMicros(clock.instant()));
                        ps.executeUpdate();
                    }

                    // 2. View checkpoint
                    try (PreparedStatement ps = conn.prepareStatement(VIEW_SQL)) {
                        ps.setString(1, viewName);
                        ps.setLong(2, position);
                        ps.setBytes(3, viewData);
                        ps.setLong(4, TimeConversion.toMicros(clock.instant()));
                        ps.executeUpdate();
                    }

                    // 3. DLQ upsert
                    try (PreparedStatement ps = conn.prepareStatement(DLQ_SQL)) {
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
                });
    }

    // ──────────────────────────────────────────────────────────────────
    // Shared transaction wrapper (H2 — M3.5b Cowork review)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Runs {@code work} inside a single SQLite transaction on the write
     * coordinator's dedicated platform thread (AMD-26/27, LTD-11 — no
     * {@code synchronized}; the coordinator serializes through a
     * {@link ReentrantLock}-free single-thread executor). Saves and restores
     * the write connection's {@code autoCommit} flag, commits on success, and
     * rolls back on any failure while preserving the original exception.
     *
     * <p>This is the H2 deduplication of the transaction boilerplate that
     * {@link #writeAtomicCheckpoint} and
     * {@link #writeAtomicCheckpointWithDlqPark} previously duplicated. Scoped
     * to the atomic-write path only — sibling stores' single-statement
     * autoCommit writes do not route through here.</p>
     *
     * @param context short description of the work, used in the
     *                rollback-failure log message
     * @param work    the statements to execute inside the transaction boundary
     */
    private void executeInTransaction(String context, TransactionalWork work) {
        writeCoordinator.submit(WritePriority.STATE_PROJECTION, () -> {
            Connection conn = databaseExecutor.writeConnection();
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                work.execute(conn);
                conn.commit();
            } catch (Exception e) {
                // Rollback must not swallow the original exception. If rollback
                // itself fails, log the rollback failure but propagate the
                // original.
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.error("Rollback failed after {}: {}",
                            context, rollbackEx.getMessage(), rollbackEx);
                }
                throw e;
            } finally {
                // W-1: autoCommit MUST be restored. The write connection is
                // shared with SqliteEventStore, SqliteCheckpointStore, and
                // SqliteViewCheckpointStore — all rely on autoCommit=true.
                conn.setAutoCommit(originalAutoCommit);
            }
            return null;
        });
    }

    /**
     * The statements to run inside an {@link #executeInTransaction} boundary.
     * May throw any exception; the wrapper rolls back and propagates it.
     */
    @FunctionalInterface
    private interface TransactionalWork {
        void execute(Connection conn) throws Exception;
    }
}
