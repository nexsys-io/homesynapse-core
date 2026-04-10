/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies forward-only SQL migrations to a single SQLite database, tracking
 * applied versions and checksums in an {@code hs_schema_version} table.
 *
 * <p>This class implements the authoritative behavior specified by LTD-07:
 * version-ordered forward-only migrations, SHA-256 checksums, explicit halt
 * on checksum drift, version gaps, or a database whose schema is ahead of
 * the application. The tracking table schema matches the LTD-07 reference
 * exactly — {@code version} (PK), {@code checksum}, {@code description},
 * {@code applied_at} (ISO 8601), and {@code success} (1/0).</p>
 *
 * <p>Migration file naming convention: {@code V###__description.sql} —
 * {@code ###} is parsed as an integer version number, and the
 * {@code description} portion (with underscores converted to spaces) is
 * stored in the tracking table for operational visibility.</p>
 *
 * <p><strong>Thread safety:</strong> not thread-safe. Intended for single-thread
 * invocation during persistence startup. Concurrent-startup safety across
 * processes or JVMs is provided by SQLite's own write-lock contention — one
 * runner acquires the lock and commits the tracking row, the other sees the
 * committed state on its next transaction and takes the idempotent path.</p>
 *
 * <p><strong>Preconditions:</strong> the caller is responsible for setting
 * creation-time PRAGMAs ({@code auto_vacuum}, {@code page_size}) BEFORE
 * invoking {@link #migrate}. These PRAGMAs are silently ignored once any
 * table exists, and {@code MigrationRunner} unconditionally creates the
 * {@code hs_schema_version} tracking table on first invocation.</p>
 *
 * <p>This class is package-private — external modules interact with
 * {@link PersistenceLifecycle}, which owns the startup invocation.</p>
 *
 * @see MigrationConfig
 * @see MigrationException
 */
final class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    /** Matches filenames of the form {@code V123__some_description.sql}. */
    private static final Pattern FILENAME_PATTERN =
        Pattern.compile("^V(\\d+)__(.+)\\.sql$");

    private static final String CREATE_TRACKING_TABLE = """
        CREATE TABLE IF NOT EXISTS hs_schema_version (
            version     INTEGER PRIMARY KEY,
            checksum    TEXT    NOT NULL,
            description TEXT    NOT NULL,
            applied_at  TEXT    NOT NULL,
            success     INTEGER NOT NULL DEFAULT 1
        )
        """;

    private final Connection connection;

    /**
     * Creates a migration runner bound to an open SQLite connection.
     *
     * @param connection an open JDBC connection to the target SQLite database;
     *                   must not be {@code null}. Caller retains ownership and
     *                   is responsible for closing it.
     */
    MigrationRunner(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Applies all pending migrations from the given classpath prefix.
     *
     * <p>Creates the {@code hs_schema_version} tracking table if it does not
     * exist. For each file in {@code migrationFiles} (in the given order):</p>
     *
     * <ol>
     *   <li>Loads the SQL content from {@code classpath:migrationPath/filename}</li>
     *   <li>Computes its SHA-256 checksum</li>
     *   <li>Looks up the version in {@code hs_schema_version}:
     *     <ul>
     *       <li>If present with {@code success=1}: validates the checksum
     *           matches the on-disk file, then skips execution. A mismatch
     *           halts the run with {@link MigrationException}.</li>
     *       <li>If present with {@code success=0} and {@code forceRetryFailed}
     *           is false: halts with {@link MigrationException}.</li>
     *       <li>If present with {@code success=0} and {@code forceRetryFailed}
     *           is true: deletes the failed row and re-executes.</li>
     *       <li>If absent: executes the SQL in a transaction and records the
     *           result (success=1 on success, success=0 on failure).</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>Version gaps (e.g., V001 applied, V003 pending but V002 never run)
     * and database-ahead-of-application states halt immediately. Both
     * conditions indicate operator error or catastrophic version-skew and
     * must not be silently corrected.</p>
     *
     * <p>When {@code backupRequired} is true and the database already contains
     * applied migrations (i.e., this is a real upgrade, not a fresh install),
     * the run halts unless {@code backupVerified} is also true. This enforces
     * LTD-07's mandatory pre-upgrade backup requirement.</p>
     *
     * @param migrationPath  classpath prefix for migration SQL files
     *                       (e.g., {@code "db/migration/events"})
     * @param migrationFiles ordered list of migration resource filenames
     *                       relative to {@code migrationPath}. The explicit
     *                       list avoids classpath scanning, which is
     *                       unreliable under JPMS.
     * @param config         migration configuration — backup and retry flags
     * @throws MigrationException if any migration fails, a checksum mismatches,
     *                            versions are out of order, a required backup
     *                            is not verified, or the tracking table cannot
     *                            be read/written
     * @throws NullPointerException if any argument is {@code null}
     */
    void migrate(String migrationPath, List<String> migrationFiles, MigrationConfig config) {
        Objects.requireNonNull(migrationPath, "migrationPath");
        Objects.requireNonNull(migrationFiles, "migrationFiles");
        Objects.requireNonNull(config, "config");

        var runStart = Instant.now();
        log.info("Migration run starting: path={} files={}", migrationPath, migrationFiles.size());

        try {
            ensureTrackingTable();
            Map<Integer, TrackedMigration> tracked = readTrackingTable();

            List<ParsedMigration> parsed = parseAndValidate(migrationPath, migrationFiles);
            enforceVersionAgainstTrackedState(parsed, tracked);
            enforceBackupRequirement(parsed, tracked, config);

            int applied = 0;
            int skipped = 0;
            for (ParsedMigration migration : parsed) {
                TrackedMigration existing = tracked.get(migration.version());
                if (existing == null) {
                    applyMigration(migration);
                    applied++;
                } else if (existing.success() == 1) {
                    if (!existing.checksum().equals(migration.checksum())) {
                        throw new MigrationException(String.format(
                            "Migration checksum mismatch: version %d expected %s, found %s",
                            migration.version(), existing.checksum(), migration.checksum()));
                    }
                    log.warn("Skipping already-applied migration: version={} description='{}'",
                        migration.version(), migration.description());
                    skipped++;
                } else {
                    // success=0
                    if (!config.forceRetryFailed()) {
                        throw new MigrationException(String.format(
                            "Migration %d ('%s') was previously failed (success=0). "
                                + "Fix the migration file and re-run with MigrationConfig.recovery() "
                                + "to retry.",
                            migration.version(), existing.description()));
                    }
                    deleteFailedRecord(migration.version());
                    applyMigration(migration);
                    applied++;
                }
            }

            var runDuration = Duration.between(runStart, Instant.now());
            log.info("Migration run complete: applied={} skipped={} duration={}ms",
                applied, skipped, runDuration.toMillis());
        } catch (MigrationException e) {
            throw e;
        } catch (SQLException e) {
            throw new MigrationException(
                "Migration run failed due to a database error: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Tracking table
    // ------------------------------------------------------------------

    private void ensureTrackingTable() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(CREATE_TRACKING_TABLE);
        }
    }

    private Map<Integer, TrackedMigration> readTrackingTable() throws SQLException {
        Map<Integer, TrackedMigration> result = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT version, checksum, description, success FROM hs_schema_version")) {
            while (rs.next()) {
                result.put(
                    rs.getInt("version"),
                    new TrackedMigration(
                        rs.getInt("version"),
                        rs.getString("checksum"),
                        rs.getString("description"),
                        rs.getInt("success")));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void deleteFailedRecord(int version) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM hs_schema_version WHERE version = ?")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
    }

    private void recordSuccess(ParsedMigration migration) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO hs_schema_version(version, checksum, description, applied_at, success) "
                    + "VALUES (?, ?, ?, ?, 1)")) {
            ps.setInt(1, migration.version());
            ps.setString(2, migration.checksum());
            ps.setString(3, migration.description());
            ps.setString(4, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            ps.executeUpdate();
        }
    }

    private void recordFailure(ParsedMigration migration) {
        // The caller has just rolled back the failed migration transaction.
        // The connection is still in auto-commit=false mode, so we explicitly
        // commit after the insert to make the failure record durable even
        // though the surrounding transaction was rolled back.
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO hs_schema_version(version, checksum, description, applied_at, success) "
                    + "VALUES (?, ?, ?, ?, 0)")) {
            ps.setInt(1, migration.version());
            ps.setString(2, migration.checksum());
            ps.setString(3, migration.description());
            ps.setString(4, DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException recordError) {
            // The underlying migration has already failed; surface the record
            // error as a log event rather than masking the original cause.
            log.error("Failed to record migration failure for version {}: {}",
                migration.version(), recordError.getMessage(), recordError);
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                log.error("Rollback of failure-record insert also failed: {}",
                    rollbackError.getMessage(), rollbackError);
            }
        }
    }

    // ------------------------------------------------------------------
    // Migration file parsing and validation
    // ------------------------------------------------------------------

    private List<ParsedMigration> parseAndValidate(
            String migrationPath, List<String> migrationFiles) {
        List<ParsedMigration> parsed = new ArrayList<>(migrationFiles.size());
        int lastVersion = -1;
        for (String filename : migrationFiles) {
            Matcher m = FILENAME_PATTERN.matcher(filename);
            if (!m.matches()) {
                throw new MigrationException(String.format(
                    "Invalid migration filename '%s': expected pattern V###__description.sql",
                    filename));
            }
            int version = Integer.parseInt(m.group(1));
            if (version <= lastVersion) {
                throw new MigrationException(String.format(
                    "Migration filenames out of order: version %d follows version %d",
                    version, lastVersion));
            }
            String description = m.group(2).replace('_', ' ');
            String resourcePath = migrationPath + "/" + filename;
            String content = readResource(resourcePath);
            String checksum = sha256Hex(content);
            parsed.add(new ParsedMigration(version, description, resourcePath, content, checksum));
            lastVersion = version;
        }
        return parsed;
    }

    private void enforceVersionAgainstTrackedState(
            List<ParsedMigration> parsed, Map<Integer, TrackedMigration> tracked) {
        int maxTracked = tracked.keySet().stream().max(Integer::compareTo).orElse(0);
        int maxParsed = parsed.isEmpty()
            ? 0
            : parsed.get(parsed.size() - 1).version();

        // Database schema ahead of application — check this FIRST so the error
        // message reflects the ahead condition rather than a derivative gap.
        if (maxTracked > maxParsed) {
            throw new MigrationException(String.format(
                "Database schema version %d is ahead of application (latest known: %d)",
                maxTracked, maxParsed));
        }

        // Gap detection: the union of tracked versions and parsed versions must
        // form a contiguous range [1..N]. This catches both "fresh DB + V002
        // only" and "V001 applied + V003 pending without V002" cases.
        if (tracked.isEmpty() && parsed.isEmpty()) {
            return;
        }
        TreeSet<Integer> union = new TreeSet<>(tracked.keySet());
        for (ParsedMigration m : parsed) {
            union.add(m.version());
        }
        int expected = 1;
        for (int v : union) {
            if (v != expected) {
                throw new MigrationException(String.format(
                    "Migration gap detected: version %d is missing (next known version is %d)",
                    expected, v));
            }
            expected++;
        }
    }

    private void enforceBackupRequirement(
            List<ParsedMigration> parsed,
            Map<Integer, TrackedMigration> tracked,
            MigrationConfig config) {
        if (!config.backupRequired()) {
            return;
        }
        if (config.backupVerified()) {
            return;
        }
        // Pending work exists if any parsed migration is not already applied.
        boolean hasPending = parsed.stream()
            .anyMatch(m -> !tracked.containsKey(m.version())
                || (tracked.get(m.version()).success() == 0));
        if (hasPending && !tracked.isEmpty()) {
            throw new MigrationException(
                "Backup required: the database contains applied migrations and pending "
                    + "migrations exist, but backupVerified=false. Take a verified backup "
                    + "and set backupVerified=true before retrying.");
        }
    }

    // ------------------------------------------------------------------
    // Migration execution
    // ------------------------------------------------------------------

    private void applyMigration(ParsedMigration migration) throws SQLException {
        log.info("Applying migration: version={} description='{}'",
            migration.version(), migration.description());
        var migrationStart = Instant.now();

        boolean previousAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                for (String sql : splitSqlStatements(migration.content())) {
                    stmt.execute(sql);
                }
            }
            recordSuccess(migration);
            connection.commit();
            var migrationDuration = Duration.between(migrationStart, Instant.now());
            log.info("Applied migration: version={} description='{}' duration={}ms",
                migration.version(), migration.description(), migrationDuration.toMillis());
        } catch (SQLException sqlError) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                log.error("Rollback failed after migration {} failure: {}",
                    migration.version(), rollbackError.getMessage(), rollbackError);
            }
            log.error("Migration failed: version={} description='{}' error={}",
                migration.version(), migration.description(), sqlError.getMessage(), sqlError);
            recordFailure(migration);
            throw new MigrationException(String.format(
                "Migration %d ('%s') failed to execute: %s",
                migration.version(), migration.description(), sqlError.getMessage()), sqlError);
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignore) {
                // Restoring auto-commit is best-effort; the connection may be in a
                // bad state if the migration failed catastrophically.
            }
        }
    }

    /**
     * Splits a SQL file into individual statements on {@code ;} boundaries.
     *
     * <p>Deliberately lightweight — this is sufficient for the straightforward
     * DDL files authored by HomeSynapse developers (no triggers with embedded
     * semicolons, no dollar-quoted strings). If a future migration needs more
     * sophisticated parsing, replace this method rather than building a full
     * SQL parser inside the runner.</p>
     */
    private static List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLineComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                current.append(c);
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                current.append(c);
                continue;
            }
            if (c == ';') {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    // ------------------------------------------------------------------
    // Resource loading and checksums
    // ------------------------------------------------------------------

    private String readResource(String resourcePath) {
        // Try the class loader of this class first (covers main-sourceset resources
        // under JPMS); fall back to the system classloader (covers test-sourceset
        // resources during testing).
        try (InputStream stream = openResource(resourcePath)) {
            if (stream == null) {
                throw new MigrationException(
                    "Migration resource not found on classpath: " + resourcePath);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MigrationException(
                "Failed to read migration resource: " + resourcePath, e);
        }
    }

    private InputStream openResource(String resourcePath) {
        ClassLoader classLoader = MigrationRunner.class.getClassLoader();
        InputStream stream = classLoader != null
            ? classLoader.getResourceAsStream(resourcePath)
            : null;
        if (stream != null) {
            return stream;
        }
        return ClassLoader.getSystemResourceAsStream(resourcePath);
    }

    private static String sha256Hex(String content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new MigrationException("SHA-256 algorithm not available", e);
        }
        byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // ------------------------------------------------------------------
    // Internal records
    // ------------------------------------------------------------------

    private record ParsedMigration(
        int version,
        String description,
        String resourcePath,
        String content,
        String checksum) {
    }

    private record TrackedMigration(
        int version,
        String checksum,
        String description,
        int success) {
    }
}
