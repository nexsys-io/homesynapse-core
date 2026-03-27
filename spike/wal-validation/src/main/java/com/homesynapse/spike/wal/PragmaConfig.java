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
 * Applies and verifies the 7 PRAGMA values from the WAL validation spike plan §3.
 * These match the LTD-03 production configuration exactly.
 */
public final class PragmaConfig {

    private PragmaConfig() {
        // utility class — no instantiation
    }

    /**
     * Applies all 7 PRAGMAs from spike plan §3 in order.
     * Must be called immediately after opening the connection, before any schema
     * or data operations.
     */
    public static void apply(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA cache_size = -128000");
            stmt.execute("PRAGMA mmap_size = 1073741824");
            stmt.execute("PRAGMA temp_store = MEMORY");
            stmt.execute("PRAGMA journal_size_limit = 6144000");
            stmt.execute("PRAGMA busy_timeout = 5000");
        }
    }

    /**
     * Reads back each PRAGMA value and prints confirmation to stdout.
     * Throws {@link IllegalStateException} if any value does not match the expected
     * LTD-03 configuration.
     */
    public static void verify(Connection connection) throws SQLException {
        System.out.println("--- PRAGMA Verification ---");
        try (Statement stmt = connection.createStatement()) {
            verifyPragma(stmt, "journal_mode",       "wal");
            verifyPragma(stmt, "synchronous",        "1");       // NORMAL = 1
            verifyPragma(stmt, "cache_size",         "-128000");
            verifyPragma(stmt, "mmap_size",          "1073741824");
            verifyPragma(stmt, "temp_store",         "2");       // MEMORY = 2
            verifyPragma(stmt, "journal_size_limit", "6144000");
            verifyPragma(stmt, "busy_timeout",       "5000");
        }
        System.out.println("--- All PRAGMAs verified OK ---");
    }

    private static void verifyPragma(Statement stmt, String pragma, String expected)
            throws SQLException {
        try (ResultSet rs = stmt.executeQuery("PRAGMA " + pragma)) {
            if (!rs.next()) {
                throw new IllegalStateException(
                        "PRAGMA " + pragma + " returned no result");
            }
            String actual = rs.getString(1);
            if (!expected.equals(actual)) {
                throw new IllegalStateException(
                        "PRAGMA " + pragma + " mismatch: expected "
                                + expected + ", got " + actual);
            }
            System.out.printf("  %-20s = %-12s OK%n", pragma, actual);
        }
    }
}
