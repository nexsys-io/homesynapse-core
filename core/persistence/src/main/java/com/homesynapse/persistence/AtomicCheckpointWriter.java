/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

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

        writeCoordinator.submit(WritePriority.STATE_PROJECTION, () -> {
            Connection conn = databaseExecutor.writeConnection();
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
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

                conn.commit();
            } catch (Exception e) {
                // Rollback must not swallow the original exception. If
                // rollback itself fails, log the rollback failure but
                // propagate the original.
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    LOG.error("Rollback failed after atomic checkpoint write "
                            + "failure for subscriber={} view={}: {}",
                            subscriberId, viewName, rollbackEx.getMessage(),
                            rollbackEx);
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
}
