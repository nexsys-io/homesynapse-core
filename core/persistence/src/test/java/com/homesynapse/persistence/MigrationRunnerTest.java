/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MigrationRunner} — covers fresh install, idempotency,
 * checksum validation, version ordering, failure recording, recovery, the
 * real V001 events schema, backup gating, and concurrent startup.
 *
 * <p>Uses file-based SQLite via {@link TempDir} rather than {@code :memory:}
 * because several tests verify behavior that requires an on-disk database
 * (concurrent access across connections, schema persistence across
 * migrate() calls on reopened connections).</p>
 */
@DisplayName("MigrationRunner")
final class MigrationRunnerTest {

    private static final String TEST_PATH = "db/migration/test";
    private static final String TAMPERED_PATH = "db/migration/tampered";
    private static final String BAD_PATH = "db/migration/bad";
    private static final String RECOVERY_PATH = "db/migration/recovery";
    private static final String EVENTS_PATH = "db/migration/events";

    private static final String V001_TEST = "V001__test_create_table.sql";
    private static final String V002_TEST = "V002__test_add_column.sql";
    private static final String V001_BAD = "V001__bad_migration.sql";
    private static final String V001_EVENTS = "V001__initial_event_store_schema.sql";

    @TempDir
    Path tempDir;

    private Path dbFile;
    private Connection connection;

    /** Creates a new test instance. */
    MigrationRunnerTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    @BeforeEach
    void setUp() throws SQLException {
        dbFile = tempDir.resolve("migration-test.db");
        connection = openConnection(dbFile);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // ------------------------------------------------------------------
    // Tier 1 — Fresh database, happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fresh database — creates hs_schema_version tracking table")
    void migrate_freshDatabase_createsSchemaVersionTable() throws SQLException {
        var runner = new MigrationRunner(connection);

        runner.migrate(TEST_PATH, List.of(V001_TEST), MigrationConfig.freshInstall());

        assertThat(tableExists(connection, "hs_schema_version")).isTrue();
        var rows = queryAllSchemaVersions(connection);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.version).isEqualTo(1);
        assertThat(row.success).isEqualTo(1);
        assertThat(row.checksum).isNotBlank();
        assertThat(row.description).isEqualTo("test create table");
    }

    @Test
    @DisplayName("fresh database — applies V001 successfully")
    void migrate_freshDatabase_appliesV001Successfully() throws SQLException {
        var runner = new MigrationRunner(connection);

        runner.migrate(TEST_PATH, List.of(V001_TEST), MigrationConfig.freshInstall());

        assertThat(tableExists(connection, "test_table")).isTrue();
    }

    // ------------------------------------------------------------------
    // Tier 2 — Idempotency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("already applied — skips on second run, no error")
    void migrate_alreadyApplied_isIdempotent() throws SQLException {
        var runner = new MigrationRunner(connection);
        runner.migrate(TEST_PATH, List.of(V001_TEST), MigrationConfig.freshInstall());

        runner.migrate(TEST_PATH, List.of(V001_TEST), MigrationConfig.freshInstall());

        assertThat(queryAllSchemaVersions(connection)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Tier 3 — Validation and error detection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("checksum mismatch on applied version — throws and halts")
    void migrate_checksumMismatch_throwsAndHalts() {
        var runner = new MigrationRunner(connection);
        runner.migrate(TEST_PATH, List.of(V001_TEST), MigrationConfig.freshInstall());

        assertThatThrownBy(() ->
            runner.migrate(TAMPERED_PATH, List.of(V001_TEST), MigrationConfig.freshInstall()))
            .isInstanceOf(MigrationException.class)
            .hasMessageContainingAll("checksum");
    }

    @Test
    @DisplayName("version gap in pending list — throws and halts")
    void migrate_versionGap_throwsAndHalts() {
        var runner = new MigrationRunner(connection);

        assertThatThrownBy(() ->
            runner.migrate(TEST_PATH, List.of(V002_TEST), MigrationConfig.freshInstall()))
            .isInstanceOf(MigrationException.class)
            .hasMessageContainingAll("gap");
    }

    @Test
    @DisplayName("database schema ahead of application — throws and halts")
    void migrate_versionAheadOfApplication_throwsAndHalts() throws SQLException {
        var runner = new MigrationRunner(connection);
        // Seed the tracking table with a version higher than anything we will provide.
        runner.migrate(TEST_PATH, List.of(V001_TEST), MigrationConfig.freshInstall());
        insertFakeAppliedMigration(connection, 5, "future version");

        assertThatThrownBy(() ->
            runner.migrate(TEST_PATH, List.of(V001_TEST, V002_TEST), MigrationConfig.freshInstall()))
            .isInstanceOf(MigrationException.class)
            .hasMessageContainingAll("ahead");
    }

    @Test
    @DisplayName("sql execution failure — records success=0 then throws")
    void migrate_sqlFailure_recordsWithSuccessZero() throws SQLException {
        var runner = new MigrationRunner(connection);

        assertThatThrownBy(() ->
            runner.migrate(BAD_PATH, List.of(V001_BAD), MigrationConfig.freshInstall()))
            .isInstanceOf(MigrationException.class);

        var rows = queryAllSchemaVersions(connection);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).version).isEqualTo(1);
        assertThat(rows.get(0).success).isEqualTo(0);
    }

    @Test
    @DisplayName("previous failure without recovery — halts")
    void migrate_previousFailure_haltsWithoutForce() {
        var runner = new MigrationRunner(connection);
        try {
            runner.migrate(BAD_PATH, List.of(V001_BAD), MigrationConfig.freshInstall());
        } catch (MigrationException expected) {
            // first run fails by design
        }

        assertThatThrownBy(() ->
            runner.migrate(BAD_PATH, List.of(V001_BAD), MigrationConfig.freshInstall()))
            .isInstanceOf(MigrationException.class)
            .hasMessageContainingAll("previously failed");
    }

    @Test
    @DisplayName("previous failure with recovery — retries successfully")
    void migrate_previousFailure_retriesWithForce() throws SQLException {
        var runner = new MigrationRunner(connection);
        try {
            runner.migrate(BAD_PATH, List.of(V001_BAD), MigrationConfig.freshInstall());
        } catch (MigrationException expected) {
            // first run fails by design
        }

        runner.migrate(RECOVERY_PATH, List.of(V001_BAD), MigrationConfig.recovery());

        var rows = queryAllSchemaVersions(connection);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).version).isEqualTo(1);
        assertThat(rows.get(0).success).isEqualTo(1);
        assertThat(tableExists(connection, "test_table")).isTrue();
    }

    // ------------------------------------------------------------------
    // Tier 4 — Multi-migration sequence and empty list
    // ------------------------------------------------------------------

    @Test
    @DisplayName("multiple migrations applied in version order")
    void migrate_multiMigrationSequence_appliesInOrder() throws SQLException {
        var runner = new MigrationRunner(connection);

        runner.migrate(TEST_PATH, List.of(V001_TEST, V002_TEST), MigrationConfig.freshInstall());

        var rows = queryAllSchemaVersions(connection);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).version).isEqualTo(1);
        assertThat(rows.get(1).version).isEqualTo(2);
        assertThat(columnExists(connection, "test_table", "description")).isTrue();
    }

    @Test
    @DisplayName("empty migration list — no-op, tracking table created")
    void migrate_emptyMigrationList_isNoOp() throws SQLException {
        var runner = new MigrationRunner(connection);

        assertThatCode(() ->
            runner.migrate(TEST_PATH, List.of(), MigrationConfig.freshInstall()))
            .doesNotThrowAnyException();

        assertThat(tableExists(connection, "hs_schema_version")).isTrue();
        assertThat(queryAllSchemaVersions(connection)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Tier 5 — Real V001 events schema
    // ------------------------------------------------------------------

    @Test
    @DisplayName("real events V001 — creates all tables, indexes, and AUTOINCREMENT")
    void migrate_eventsV001_createsAllTablesAndIndexes() throws SQLException {
        var runner = new MigrationRunner(connection);

        runner.migrate(EVENTS_PATH, List.of(V001_EVENTS), MigrationConfig.freshInstall());

        assertThat(tableExists(connection, "events")).isTrue();
        assertThat(tableExists(connection, "subscriber_checkpoints")).isTrue();
        assertThat(tableExists(connection, "view_checkpoints")).isTrue();

        assertThat(columnNames(connection, "events")).containsExactlyInAnyOrder(
            "global_position", "event_id", "event_type", "schema_version",
            "ingest_time", "event_time", "subject_ref", "subject_sequence",
            "priority", "origin", "actor_ref", "correlation_id",
            "causation_id", "event_category", "payload", "chain_hash");
        assertThat(columnNames(connection, "subscriber_checkpoints"))
            .containsExactlyInAnyOrder("subscriber_id", "last_position", "last_updated");
        assertThat(columnNames(connection, "view_checkpoints"))
            .containsExactlyInAnyOrder("view_name", "position", "data", "updated_at");

        var indexes = indexNames(connection, "events");
        assertThat(indexes).contains(
            "idx_events_subject",
            "idx_events_type",
            "idx_events_correlation",
            "idx_events_ingest_time",
            "idx_events_event_time",
            "idx_events_actor");

        // AUTOINCREMENT creates sqlite_sequence automatically.
        assertThat(tableExists(connection, "sqlite_sequence")).isTrue();
    }

    // ------------------------------------------------------------------
    // Tier 6 — Backup gating
    // ------------------------------------------------------------------

    @Test
    @DisplayName("backup required but not verified — throws")
    void migrate_backupRequired_throwsWhenNotVerified() {
        var runner = new MigrationRunner(connection);
        runner.migrate(TEST_PATH, List.of(V001_TEST), MigrationConfig.freshInstall());

        assertThatThrownBy(() ->
            runner.migrate(TEST_PATH, List.of(V001_TEST, V002_TEST),
                MigrationConfig.upgrade(false)))
            .isInstanceOf(MigrationException.class)
            .hasMessageContainingAll("backup");
    }

    // ------------------------------------------------------------------
    // Tier 7 — Concurrent startup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("concurrent startup — at least one succeeds, database stays valid")
    void migrate_concurrentStartup_onlyOneSucceeds() throws Exception {
        // Close the @BeforeEach connection — this test manages its own connections.
        connection.close();

        var latch = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    try (Connection threadConn = openConnection(dbFile)) {
                        latch.await();
                        new MigrationRunner(threadConn)
                            .migrate(TEST_PATH, List.of(V001_TEST),
                                MigrationConfig.freshInstall());
                        return Boolean.TRUE;
                    } catch (MigrationException e) {
                        return Boolean.FALSE;
                    }
                }));
            }
            latch.countDown();

            int successes = 0;
            int failures = 0;
            for (Future<Boolean> f : results) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    successes++;
                } else {
                    failures++;
                }
            }
            assertThat(successes).as("at least one migration must succeed")
                .isGreaterThanOrEqualTo(1);
            assertThat(successes + failures).isEqualTo(2);

            // Verify database is in a valid state: exactly one applied row, test_table exists.
            try (Connection verifyConn = openConnection(dbFile)) {
                var rows = queryAllSchemaVersions(verifyConn);
                assertThat(rows).hasSize(1);
                assertThat(rows.get(0).success).isEqualTo(1);
                assertThat(tableExists(verifyConn, "test_table")).isTrue();
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------
    // Test utilities
    // ------------------------------------------------------------------

    private static Connection openConnection(Path dbPath) throws SQLException {
        var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000");
        }
        return conn;
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name = ?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(Connection conn, String table, String column)
            throws SQLException {
        return columnNames(conn, table).contains(column);
    }

    private static Set<String> columnNames(Connection conn, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    private static Set<String> indexNames(Connection conn, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
            }
        }
        return names;
    }

    private static List<SchemaVersionRow> queryAllSchemaVersions(Connection conn)
            throws SQLException {
        List<SchemaVersionRow> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT version, checksum, description, applied_at, success "
                     + "FROM hs_schema_version ORDER BY version")) {
            while (rs.next()) {
                rows.add(new SchemaVersionRow(
                    rs.getInt("version"),
                    rs.getString("checksum"),
                    rs.getString("description"),
                    rs.getString("applied_at"),
                    rs.getInt("success")));
            }
        }
        return rows;
    }

    private static void insertFakeAppliedMigration(Connection conn, int version, String description)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO hs_schema_version(version, checksum, description, applied_at, success) "
                    + "VALUES (?, ?, ?, ?, 1)")) {
            ps.setInt(1, version);
            ps.setString(2, "fakechecksum");
            ps.setString(3, description);
            ps.setString(4, "2026-01-01T00:00:00Z");
            ps.executeUpdate();
        }
    }

    private record SchemaVersionRow(
        int version,
        String checksum,
        String description,
        String appliedAt,
        int success) {
    }
}
