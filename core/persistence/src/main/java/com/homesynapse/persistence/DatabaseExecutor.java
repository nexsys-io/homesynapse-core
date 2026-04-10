/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lifecycle manager for a single SQLite database file within the persistence
 * layer. Owns the JDBC connections, the write coordinator, and the read
 * executor that together implement the AMD-26/AMD-27 mitigation for
 * sqlite-jdbc JNI carrier pinning.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Open the write connection against the target database file.</li>
 *   <li>On a new database, set creation-time PRAGMAs ({@code page_size},
 *       {@code auto_vacuum}) before any table exists. On an existing
 *       database, leave them untouched.</li>
 *   <li>Apply all 8 LTD-03 connection PRAGMAs on the write connection with
 *       {@code journal_mode = WAL} first.</li>
 *   <li>Run {@link MigrationRunner} to bring the schema to the latest
 *       declared version.</li>
 *   <li>Open N read connections (one per read thread) and apply the same
 *       connection PRAGMAs, then start the {@link PlatformThreadReadExecutor}.</li>
 *   <li>Start the {@link PlatformThreadWriteCoordinator}.</li>
 * </ul>
 *
 * <p>Shutdown reverses the order: write coordinator stops first (so no new
 * writes are in flight), then the read executor drains, then all read
 * connections close, then the write connection closes.</p>
 *
 * <p><strong>Thread safety:</strong> {@link #start} and {@link #shutdown}
 * are guarded by a lifecycle lock and are safe to call from any thread.
 * The {@link #writeCoordinator()}, {@link #readExecutor()}, and
 * {@link #writeConnection()} accessors are safe to call from any thread
 * once {@link #start} has returned. They throw {@link IllegalStateException}
 * before {@link #start} or after {@link #shutdown}.</p>
 *
 * <p>This class is package-private — module consumers interact with
 * higher-level APIs such as {@code EventStore} and {@code CheckpointStore},
 * which route their database work through the coordinator and executor
 * returned here.</p>
 *
 * @see PlatformThreadWriteCoordinator
 * @see PlatformThreadReadExecutor
 * @see MigrationRunner
 */
final class DatabaseExecutor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseExecutor.class);

    /** Connection PRAGMAs from LTD-03, applied in this order on every connection. */
    private static final List<String> CONNECTION_PRAGMAS = List.of(
            "journal_mode = WAL",
            "synchronous = NORMAL",
            "cache_size = -128000",
            "mmap_size = 1073741824",
            "temp_store = MEMORY",
            "busy_timeout = 5000",
            "journal_size_limit = 6144000",
            "cell_size_check = ON");

    private final int readThreadCount;
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private volatile boolean started;
    private volatile boolean shutdown;

    private Connection writeConnection;
    private final List<Connection> readConnections = new ArrayList<>();
    private PlatformThreadWriteCoordinator writeCoordinator;
    private PlatformThreadReadExecutor readExecutor;

    /**
     * Creates a database executor that will open {@code readThreadCount}
     * read threads (and matching read connections) when {@link #start} is
     * invoked.
     *
     * @param readThreadCount number of platform read threads; must be
     *                        {@code >= 1}. AMD-27 default is 2.
     * @throws IllegalArgumentException if {@code readThreadCount < 1}
     */
    DatabaseExecutor(int readThreadCount) {
        if (readThreadCount < 1) {
            throw new IllegalArgumentException(
                    "readThreadCount must be >= 1, got " + readThreadCount);
        }
        this.readThreadCount = readThreadCount;
    }

    /**
     * Starts the database executor: opens the write connection, applies
     * PRAGMAs, runs migrations, opens read connections, and starts the
     * write coordinator and read executor.
     *
     * <p>This method is not idempotent — calling it twice throws
     * {@link IllegalStateException}. Once started, the executor cannot be
     * re-used after {@link #shutdown}.</p>
     *
     * @param dbPath         path to the SQLite database file; the parent
     *                       directory must exist
     * @param migrationPath  classpath prefix for migration SQL files
     *                       (e.g., {@code "db/migration/events"})
     * @param migrationFiles ordered list of migration resource filenames
     *                       relative to {@code migrationPath}
     * @param migrationConfig migration configuration (backup/retry flags)
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if the executor has already been started
     *                               or shut down
     * @throws MigrationException    if migration fails
     * @throws RuntimeException      if the database cannot be opened or
     *                               PRAGMAs cannot be applied
     */
    void start(
            Path dbPath,
            String migrationPath,
            List<String> migrationFiles,
            MigrationConfig migrationConfig) {
        Objects.requireNonNull(dbPath, "dbPath");
        Objects.requireNonNull(migrationPath, "migrationPath");
        Objects.requireNonNull(migrationFiles, "migrationFiles");
        Objects.requireNonNull(migrationConfig, "migrationConfig");

        lifecycleLock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("DatabaseExecutor has been shut down");
            }
            if (started) {
                throw new IllegalStateException("DatabaseExecutor has already been started");
            }

            String jdbcUrl = "jdbc:sqlite:" + dbPath;
            log.info("Starting DatabaseExecutor: path={} readThreads={}",
                    dbPath, readThreadCount);

            boolean success = false;
            try {
                // 1. Open the write connection.
                writeConnection = DriverManager.getConnection(jdbcUrl);

                // 2. Detect whether this is a new database (no user tables)
                //    and, if so, set the creation-time PRAGMAs BEFORE any
                //    table is created. These PRAGMAs are silently ignored
                //    once any table exists.
                boolean isNewDatabase = isNewDatabase(writeConnection);
                if (isNewDatabase) {
                    log.info("New database detected — applying creation-time PRAGMAs");
                    setCreationTimePragmas(writeConnection);
                } else {
                    log.info("Existing database detected — skipping creation-time PRAGMAs");
                }

                // 3. Apply the 8 LTD-03 connection PRAGMAs on the write
                //    connection. journal_mode = WAL must be first so the
                //    rest of the connection lifecycle sees WAL state.
                applyConnectionPragmas(writeConnection);

                // 4. Run migrations on the write connection.
                new MigrationRunner(writeConnection)
                        .migrate(migrationPath, migrationFiles, migrationConfig);

                // 5. Open N read connections and apply the same connection
                //    PRAGMAs. Read connections inherit the database-wide
                //    WAL state but still need per-connection settings like
                //    cache_size, mmap_size, busy_timeout, and temp_store.
                for (int i = 0; i < readThreadCount; i++) {
                    Connection readConnection = DriverManager.getConnection(jdbcUrl);
                    applyConnectionPragmas(readConnection);
                    readConnections.add(readConnection);
                }

                // 6. Start the write coordinator and read executor.
                writeCoordinator = new PlatformThreadWriteCoordinator();
                readExecutor = new PlatformThreadReadExecutor(readThreadCount);

                started = true;
                success = true;
                log.info("DatabaseExecutor started: path={}", dbPath);
            } catch (SQLException e) {
                throw new RuntimeException(
                        "Failed to open or configure database at " + dbPath + ": " + e.getMessage(),
                        e);
            } finally {
                if (!success) {
                    closeAllResourcesQuietly();
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Shuts down the executor: stops the write coordinator, stops the read
     * executor, closes all read connections, then closes the write
     * connection. Idempotent — subsequent calls are no-ops.
     */
    void shutdown() {
        lifecycleLock.lock();
        try {
            if (shutdown) {
                return;
            }
            shutdown = true;
            log.info("Shutting down DatabaseExecutor");

            if (writeCoordinator != null) {
                try {
                    writeCoordinator.shutdown();
                } catch (RuntimeException e) {
                    log.warn("Write coordinator shutdown failed: {}", e.getMessage(), e);
                }
            }
            if (readExecutor != null) {
                try {
                    readExecutor.shutdown();
                } catch (RuntimeException e) {
                    log.warn("Read executor shutdown failed: {}", e.getMessage(), e);
                }
            }

            closeAllResourcesQuietly();
            log.info("DatabaseExecutor shutdown complete");
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Returns the write coordinator for submitting write operations.
     *
     * @throws IllegalStateException if {@link #start} has not been called
     *                               or {@link #shutdown} has been called
     */
    WriteCoordinator writeCoordinator() {
        checkStarted();
        return writeCoordinator;
    }

    /**
     * Returns the read executor for submitting read operations.
     *
     * @throws IllegalStateException if {@link #start} has not been called
     *                               or {@link #shutdown} has been called
     */
    ReadExecutor readExecutor() {
        checkStarted();
        return readExecutor;
    }

    /**
     * Returns the write connection. Exposed for callers that need direct
     * JDBC access from inside a write operation running on the write thread
     * (e.g., SqliteEventStore, MaintenanceService). Callers must never
     * issue JDBC calls on this connection from a thread other than the
     * write thread — doing so would violate the single-writer contract and
     * expose the sqlite-jdbc JNI carrier pinning problem this class
     * exists to prevent.
     *
     * @throws IllegalStateException if {@link #start} has not been called
     *                               or {@link #shutdown} has been called
     */
    Connection writeConnection() {
        checkStarted();
        return writeConnection;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void checkStarted() {
        if (shutdown) {
            throw new IllegalStateException("DatabaseExecutor has been shut down");
        }
        if (!started) {
            throw new IllegalStateException("DatabaseExecutor has not been started");
        }
    }

    private static boolean isNewDatabase(Connection c) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table'")) {
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    private static void setCreationTimePragmas(Connection c) throws SQLException {
        try (Statement stmt = c.createStatement()) {
            // page_size must be set before any table is created; auto_vacuum
            // must be set before any table is created. Both are silently
            // ignored if user tables already exist.
            stmt.execute("PRAGMA page_size = 4096");
            stmt.execute("PRAGMA auto_vacuum = INCREMENTAL");
        }
    }

    private static void applyConnectionPragmas(Connection c) throws SQLException {
        try (Statement stmt = c.createStatement()) {
            for (String pragma : CONNECTION_PRAGMAS) {
                stmt.execute("PRAGMA " + pragma);
            }
        }
    }

    private void closeAllResourcesQuietly() {
        for (Connection readConnection : readConnections) {
            try {
                if (readConnection != null && !readConnection.isClosed()) {
                    readConnection.close();
                }
            } catch (SQLException e) {
                log.warn("Failed to close read connection: {}", e.getMessage(), e);
            }
        }
        readConnections.clear();

        if (writeConnection != null) {
            try {
                if (!writeConnection.isClosed()) {
                    writeConnection.close();
                }
            } catch (SQLException e) {
                log.warn("Failed to close write connection: {}", e.getMessage(), e);
            }
        }
    }
}
