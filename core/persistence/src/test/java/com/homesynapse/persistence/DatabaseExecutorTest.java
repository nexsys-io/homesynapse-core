/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * File-based SQLite integration tests for {@link DatabaseExecutor}.
 *
 * <p>Verifies the full start/shutdown lifecycle against a real SQLite
 * database file under a JUnit {@link TempDir}:</p>
 *
 * <ul>
 *   <li>Creation-time PRAGMAs ({@code auto_vacuum}, {@code page_size}) are
 *       applied only on new databases and skipped on existing ones.</li>
 *   <li>Connection PRAGMAs (journal_mode, synchronous, cache_size, etc.)
 *       match LTD-03 values on the write connection.</li>
 *   <li>Migrations run through {@link MigrationRunner} and produce the
 *       expected schema.</li>
 *   <li>The write coordinator and read executor are functional after
 *       start, and a round-trip write-then-read works correctly.</li>
 *   <li>Shutdown closes all connections and rejects subsequent access.</li>
 * </ul>
 *
 * <p>These tests use a file-based database (not {@code :memory:}) because
 * WAL mode requires a real file. Each test gets its own {@link TempDir}.</p>
 *
 * @see DatabaseExecutor
 */
@DisplayName("DatabaseExecutor — file-based SQLite integration")
final class DatabaseExecutorTest {

    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql");

    private static final Clock TEST_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private DatabaseExecutor executor;

    /** Creates a new test instance. */
    DatabaseExecutorTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @BeforeEach
    void setUp() {
        executor = new DatabaseExecutor(2, TEST_CLOCK);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            try {
                executor.shutdown();
            } catch (RuntimeException ignore) {
                // Shutdown may fail if the test left the executor in an
                // inconsistent state; swallow so it doesn't mask the real
                // assertion failure.
            }
            executor = null;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Creation-time PRAGMAs
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start against a new database sets creation-time PRAGMAs")
    void start_newDatabase_setsCreationTimePragmas() throws Exception {
        Path dbPath = tempDir.resolve("new.db");

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        int autoVacuum = readIntPragma(executor.writeConnection(), "auto_vacuum");
        int pageSize = readIntPragma(executor.writeConnection(), "page_size");

        // 2 = INCREMENTAL (SQLite enum: 0=NONE, 1=FULL, 2=INCREMENTAL)
        assertThat(autoVacuum).as("auto_vacuum should be INCREMENTAL (2)").isEqualTo(2);
        assertThat(pageSize).as("page_size should be 4096").isEqualTo(4096);
    }

    @Test
    @DisplayName("start against an existing database leaves creation-time PRAGMAs alone")
    void start_existingDatabase_doesNotResetCreationPragmas() throws Exception {
        Path dbPath = tempDir.resolve("existing.db");

        // Pre-initialize the database with a different page_size and no
        // auto_vacuum, simulating a database that was created before the
        // DatabaseExecutor convention existed.
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement s = c.createStatement()) {
            // Must set page_size before any table is created.
            s.execute("PRAGMA page_size = 8192");
            s.execute("PRAGMA auto_vacuum = NONE");
            // Create any table to lock in the creation-time PRAGMAs.
            s.execute("CREATE TABLE pre_existing (id INTEGER PRIMARY KEY)");
        }

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        int autoVacuum = readIntPragma(executor.writeConnection(), "auto_vacuum");
        int pageSize = readIntPragma(executor.writeConnection(), "page_size");

        assertThat(pageSize).as("page_size should still be 8192 from pre-init")
                .isEqualTo(8192);
        assertThat(autoVacuum).as("auto_vacuum should still be NONE (0)")
                .isEqualTo(0);
    }

    // ──────────────────────────────────────────────────────────────────
    // Connection PRAGMAs
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start applies all 8 connection PRAGMAs on the write connection")
    void start_setsConnectionPragmas() throws Exception {
        Path dbPath = tempDir.resolve("pragmas.db");

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        Connection write = executor.writeConnection();
        Map<String, String> pragmas = readPragmas(write,
                "journal_mode",
                "synchronous",
                "cache_size",
                "mmap_size",
                "temp_store",
                "busy_timeout",
                "journal_size_limit",
                "cell_size_check");

        assertThat(pragmas.get("journal_mode")).isEqualToIgnoringCase("wal");
        // synchronous: NORMAL = 1
        assertThat(pragmas.get("synchronous")).isEqualTo("1");
        // cache_size is signed: -128000 = 128 MB
        assertThat(pragmas.get("cache_size")).isEqualTo("-128000");
        assertThat(pragmas.get("mmap_size")).isEqualTo("1073741824");
        // temp_store: MEMORY = 2
        assertThat(pragmas.get("temp_store")).isEqualTo("2");
        assertThat(pragmas.get("busy_timeout")).isEqualTo("5000");
        assertThat(pragmas.get("journal_size_limit")).isEqualTo("6144000");
        // cell_size_check: ON = 1
        assertThat(pragmas.get("cell_size_check")).isEqualTo("1");
    }

    @Test
    @DisplayName("start activates WAL mode on a file-based database")
    void start_walModeActive() throws Exception {
        Path dbPath = tempDir.resolve("wal.db");

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        String mode;
        try (Statement s = executor.writeConnection().createStatement();
             ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
            rs.next();
            mode = rs.getString(1);
        }
        assertThat(mode).isEqualToIgnoringCase("wal");
    }

    // ──────────────────────────────────────────────────────────────────
    // Migrations
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start runs migrations and produces the events table")
    void start_runsMigrations() throws Exception {
        Path dbPath = tempDir.resolve("migrations.db");

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        assertThat(tableExists(executor.writeConnection(), "events")).isTrue();
        assertThat(tableExists(executor.writeConnection(), "subscriber_checkpoints"))
                .isTrue();
        assertThat(tableExists(executor.writeConnection(), "view_checkpoints"))
                .isTrue();
        assertThat(tableExists(executor.writeConnection(), "hs_schema_version"))
                .isTrue();
    }

    // ──────────────────────────────────────────────────────────────────
    // Read/write executor wiring
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("start opens the read executor and it can run a simple query")
    void start_opensReadConnections() throws Exception {
        Path dbPath = tempDir.resolve("reads.db");

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        assertThat(executor.readExecutor()).isNotNull();

        Integer one = executor.readExecutor().execute(() -> 1);
        assertThat(one).isEqualTo(1);
    }

    @Test
    @DisplayName("write coordinator is functional and writes are visible to readers")
    void start_writeCoordinatorFunctional() throws Exception {
        Path dbPath = tempDir.resolve("roundtrip.db");

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        assertThat(executor.writeCoordinator()).isNotNull();

        // Submit a simple write through the coordinator using the write
        // connection directly. In production, callers don't reach into
        // the write connection — they use a higher-level API — but the
        // contract here is that the executor holds a usable write path.
        executor.writeCoordinator().submit(WritePriority.EVENT_PUBLISH, () -> {
            try (Statement s = executor.writeConnection().createStatement()) {
                s.execute("INSERT INTO subscriber_checkpoints "
                        + "(subscriber_id, last_position, last_updated) "
                        + "VALUES ('test', 42, 1000000)");
            }
            return null;
        });

        // Read it back through the read executor, opening a fresh
        // connection so we exercise a different thread from the writer.
        Long position = executor.readExecutor().execute(() -> {
            try (Connection c = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + dbPath);
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT last_position FROM subscriber_checkpoints "
                                 + "WHERE subscriber_id = 'test'")) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        });
        assertThat(position).isEqualTo(42L);
    }

    @Test
    @DisplayName("read executor can handle concurrent reads from multiple threads")
    void start_withMultipleReadThreads() throws Exception {
        Path dbPath = tempDir.resolve("concurrent.db");

        // Use a larger pool than the default of 2 to prove the size is
        // actually wired up through the constructor.
        if (executor != null) {
            executor.shutdown();
        }
        executor = new DatabaseExecutor(3, TEST_CLOCK);
        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());

        int callerCount = 3;
        var callers = Executors.newFixedThreadPool(callerCount);
        var latch = new CountDownLatch(callerCount);
        var results = new CopyOnWriteArrayList<Integer>();
        var errors = new CopyOnWriteArrayList<Throwable>();

        for (int i = 0; i < callerCount; i++) {
            int idx = i;
            callers.submit(() -> {
                try {
                    Integer v = executor.readExecutor().execute(() -> idx + 1);
                    results.add(v);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(results).containsExactlyInAnyOrder(1, 2, 3);
        callers.shutdown();
    }

    // ──────────────────────────────────────────────────────────────────
    // Shutdown
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("shutdown closes all connections")
    void shutdown_closesAllConnections() throws Exception {
        Path dbPath = tempDir.resolve("shutdown.db");

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());
        Connection write = executor.writeConnection();

        executor.shutdown();

        assertThat(write.isClosed())
                .as("write connection should be closed after shutdown")
                .isTrue();
    }

    @Test
    @DisplayName("accessors throw IllegalStateException after shutdown")
    void shutdown_thenAccessors_throwIllegalState() throws Exception {
        Path dbPath = tempDir.resolve("post-shutdown.db");

        executor.start(dbPath, EVENTS_MIGRATION_PATH, EVENTS_MIGRATION_FILES,
                MigrationConfig.freshInstall());
        executor.shutdown();

        assertThatThrownBy(() -> executor.writeCoordinator())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executor.readExecutor())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executor.writeConnection())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("accessors throw IllegalStateException before start")
    void accessors_beforeStart_throwIllegalState() {
        assertThatThrownBy(() -> executor.writeCoordinator())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executor.readExecutor())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> executor.writeConnection())
                .isInstanceOf(IllegalStateException.class);
    }

    // ──────────────────────────────────────────────────────────────────
    // Test utilities
    // ──────────────────────────────────────────────────────────────────

    private static int readIntPragma(Connection c, String pragma) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA " + pragma)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Map<String, String> readPragmas(Connection c, String... names)
            throws SQLException {
        Map<String, String> out = new HashMap<>();
        try (Statement s = c.createStatement()) {
            for (String name : names) {
                try (ResultSet rs = s.executeQuery("PRAGMA " + name)) {
                    if (rs.next()) {
                        out.put(name, rs.getString(1));
                    }
                }
            }
        }
        return out;
    }

    private static boolean tableExists(Connection c, String tableName)
            throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='"
                             + tableName + "'")) {
            return rs.next();
        }
    }
}
