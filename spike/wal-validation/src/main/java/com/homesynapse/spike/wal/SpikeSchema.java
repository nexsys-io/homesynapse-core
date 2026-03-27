/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.spike.wal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates and queries the spike event table.
 * Schema matches spike plan §6 exactly (derived from Doc 04 §4.1).
 */
public final class SpikeSchema {

    private SpikeSchema() {
        // utility class — no instantiation
    }

    /**
     * Creates the {@code events} table and the {@code idx_events_entity} index.
     * Schema from spike plan §6:
     * <pre>
     * CREATE TABLE events (
     *     global_position INTEGER PRIMARY KEY AUTOINCREMENT,
     *     event_id        BLOB(16) NOT NULL,
     *     entity_ref      BLOB(16) NOT NULL,
     *     entity_sequence INTEGER  NOT NULL,
     *     event_type      TEXT     NOT NULL,
     *     event_time      TEXT,
     *     ingest_time     TEXT     NOT NULL,
     *     payload         BLOB     NOT NULL
     * );
     * CREATE INDEX idx_events_entity ON events (entity_ref, entity_sequence);
     * </pre>
     */
    public static void create(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE events (
                        global_position INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id        BLOB(16) NOT NULL,
                        entity_ref      BLOB(16) NOT NULL,
                        entity_sequence INTEGER  NOT NULL,
                        event_type      TEXT     NOT NULL,
                        event_time      TEXT,
                        ingest_time     TEXT     NOT NULL,
                        payload         BLOB     NOT NULL
                    )""");
            stmt.execute(
                    "CREATE INDEX idx_events_entity ON events (entity_ref, entity_sequence)");
        }
    }

    /**
     * Returns the current row count in the {@code events} table.
     * Useful for verification after kill-9 durability tests.
     */
    public static long eventCount(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM events")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
