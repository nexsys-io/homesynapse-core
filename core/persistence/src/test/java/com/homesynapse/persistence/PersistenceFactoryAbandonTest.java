/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link PersistenceFactory#abandon()} and its mutual exclusion
 * with {@link PersistenceFactory#close()} (M3.7 abandon contract).
 *
 * <p>The four scenarios match the four-case unit test pattern documented
 * in the M3.7 task brief: abandon releases resources, close-after-abandon
 * is a no-op, abandon-after-close is a no-op, double-abandon is a no-op.</p>
 */
@DisplayName("PersistenceFactory.abandon — mutual exclusion with close")
final class PersistenceFactoryAbandonTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-27T12:00:00Z"), ZoneOffset.UTC);

    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    PersistenceFactoryAbandonTest() {
    }

    @Test
    @DisplayName("abandon releases OS handles so the database file can be reopened")
    void abandonReleasesResources(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("homesynapse-events.db");

        PersistenceFactory factory = PersistenceFactory.start(
                dbPath, PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK,
                TEST_HOME_ID, AllEventClasses.ALL_EVENTS, null);
        factory.abandon();

        // After abandon, opening a fresh JDBC connection to the same file
        // and running a trivial query must succeed without SQLITE_BUSY.
        // If executors or write connections were still alive the WAL/shm
        // sidecars would block this on Windows.
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("close after abandon is a no-op (no exception, no double-release)")
    void closeAfterAbandonIsNoOp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("homesynapse-events.db");

        PersistenceFactory factory = PersistenceFactory.start(
                dbPath, PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK,
                TEST_HOME_ID, AllEventClasses.ALL_EVENTS, null);
        factory.abandon();

        assertThatCode(factory::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("abandon after close is a no-op (no exception, no double-release)")
    void abandonAfterCloseIsNoOp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("homesynapse-events.db");

        PersistenceFactory factory = PersistenceFactory.start(
                dbPath, PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK,
                TEST_HOME_ID, AllEventClasses.ALL_EVENTS, null);
        factory.close();

        assertThatCode(factory::abandon).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("double abandon is a no-op")
    void doubleAbandonIsNoOp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("homesynapse-events.db");

        PersistenceFactory factory = PersistenceFactory.start(
                dbPath, PersistenceConfig.HOME_DEFAULT, FIXED_CLOCK,
                TEST_HOME_ID, AllEventClasses.ALL_EVENTS, null);
        factory.abandon();

        assertThatCode(factory::abandon).doesNotThrowAnyException();
    }
}
