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
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
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
 *   <li>Apply the LTD-03 connection PRAGMAs on the write connection with
 *       {@code journal_mode = WAL} first. Values for {@code cache_size},
 *       {@code mmap_size}, {@code busy_timeout}, {@code journal_size_limit},
 *       and (when not {@link LockingMode#NORMAL}) {@code locking_mode} are
 *       rendered from the supplied {@link DeploymentProfile}.</li>
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

    /**
     * LTD-03 reader-budget ceiling. {@link DeploymentProfile#readThreadCount()}
     * values must not exceed this; the constructor validates the bound.
     */
    private static final int MAX_READ_THREAD_COUNT = 8;

    private final DeploymentProfile profile;
    private final int readThreadCount;
    private final Clock clock;
    private final Function<WriteCoordinator, WriteCoordinator> writeCoordinatorDecorator;
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private volatile boolean started;
    private volatile boolean shutdown;

    private Connection writeConnection;
    private final List<Connection> readConnections = new ArrayList<>();
    /**
     * The exposed {@link WriteCoordinator}. In production this is the bare
     * {@link PlatformThreadWriteCoordinator}; tests may install a decorator
     * (e.g. {@code ThrottledWriteCoordinator}) via the package-private
     * constructor overload. The decorator's {@link WriteCoordinator#shutdown()}
     * forwards to the underlying coordinator's shutdown.
     */
    private WriteCoordinator writeCoordinator;
    private PlatformThreadReadExecutor readExecutor;

    /**
     * Creates a database executor whose read-thread count, connection
     * PRAGMAs, and locking-mode behaviour are derived from the given
     * {@link DeploymentProfile}.
     *
     * @param profile deployment profile supplying read thread count and
     *                connection PRAGMA values; never {@code null}. Its
     *                {@code readThreadCount} must be in {@code [1, 8]} —
     *                the LTD-03 reader-budget ceiling.
     * @param clock   clock forwarded to {@link MigrationRunner} for
     *                {@code hs_schema_version.applied_at} timestamp
     *                generation and diagnostic duration logging. Inject
     *                {@code Clock.systemUTC()} in production; use
     *                {@code Clock.fixed(...)} in tests.
     * @throws IllegalArgumentException if the profile's
     *                                  {@code readThreadCount} is outside
     *                                  {@code [1, 8]}
     * @throws NullPointerException     if {@code profile} or {@code clock}
     *                                  is {@code null}
     */
    DatabaseExecutor(DeploymentProfile profile, Clock clock) {
        this(profile, clock, Function.identity());
    }

    /**
     * Test-only constructor accepting a decorator function applied to the
     * {@link PlatformThreadWriteCoordinator} during {@link #start}. The
     * decorator allows the {@code testFixtures} source set to install a
     * {@code ThrottledWriteCoordinator} (M3.4b) without changing the
     * production wiring path. Pass {@link Function#identity()} for the
     * production-equivalent behavior.
     *
     * @param profile deployment profile supplying read thread count and
     *                connection PRAGMA values; never {@code null}
     * @param clock   clock for migrations and diagnostics; never {@code null}
     * @param writeCoordinatorDecorator decorator applied to the underlying
     *                        {@code PlatformThreadWriteCoordinator}; never
     *                        {@code null}. The result must implement the
     *                        full {@link WriteCoordinator} contract and
     *                        forward {@code shutdown()} to the underlying
     *                        coordinator.
     * @throws IllegalArgumentException if the profile's
     *                                  {@code readThreadCount} is outside
     *                                  {@code [1, 8]}
     * @throws NullPointerException     if {@code profile}, {@code clock}, or
     *                                  {@code writeCoordinatorDecorator} is
     *                                  {@code null}
     */
    DatabaseExecutor(
            DeploymentProfile profile,
            Clock clock,
            Function<WriteCoordinator, WriteCoordinator> writeCoordinatorDecorator) {
        this.profile = Objects.requireNonNull(profile, "profile");
        int rt = profile.readThreadCount();
        if (rt < 1 || rt > MAX_READ_THREAD_COUNT) {
            throw new IllegalArgumentException(
                    "profile.readThreadCount() must be in [1, " + MAX_READ_THREAD_COUNT
                            + "], got " + rt);
        }
        this.readThreadCount = rt;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.writeCoordinatorDecorator = Objects.requireNonNull(
                writeCoordinatorDecorator, "writeCoordinatorDecorator");
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

                // 3. Apply the LTD-03 connection PRAGMAs on the write
                //    connection, rendered from the deployment profile.
                //    journal_mode = WAL must be first so the rest of the
                //    connection lifecycle sees WAL state.
                applyConnectionPragmas(writeConnection, profile);

                // 4. Run migrations on the write connection.
                new MigrationRunner(writeConnection, clock)
                        .migrate(migrationPath, migrationFiles, migrationConfig);

                // 5. Open N read connections and apply the same connection
                //    PRAGMAs. Read connections inherit the database-wide
                //    WAL state but still need per-connection settings like
                //    cache_size, mmap_size, busy_timeout, and temp_store.
                for (int i = 0; i < readThreadCount; i++) {
                    Connection readConnection = DriverManager.getConnection(jdbcUrl);
                    applyConnectionPragmas(readConnection, profile);
                    readConnections.add(readConnection);
                }

                // 6. Start the write coordinator and read executor.
                //    The decorator hook (default Function.identity()) lets the
                //    testFixtures source set install a ThrottledWriteCoordinator
                //    (M3.4b) without changing the production code path.
                PlatformThreadWriteCoordinator underlying =
                        new PlatformThreadWriteCoordinator();
                writeCoordinator = Objects.requireNonNull(
                        writeCoordinatorDecorator.apply(underlying),
                        "writeCoordinatorDecorator returned null");
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

    /**
     * Returns the list of read connections opened at {@link #start} time, in
     * insertion order (matching the index of the corresponding read thread in
     * {@link PlatformThreadReadExecutor}).
     *
     * <p>The returned list is an unmodifiable view — callers cannot add, remove,
     * or reorder connections. Callers must never issue JDBC calls on these
     * connections from a thread other than the owning read thread, for the same
     * AMD-26/AMD-27 carrier-pinning reason that applies to
     * {@link #writeConnection()}. The typical consumer is a higher-level store
     * (e.g., {@code SqliteEventStore}) that owns a {@link java.lang.ThreadLocal}
     * mapping from read thread to connection and populates it lazily on first
     * access using a round-robin index into this list.</p>
     *
     * <p>The list size always equals the {@code readThreadCount} passed to the
     * constructor.</p>
     *
     * @return an unmodifiable view of the read connections, never {@code null}
     * @throws IllegalStateException if {@link #start} has not been called
     *                               or {@link #shutdown} has been called
     */
    List<Connection> readConnections() {
        checkStarted();
        return Collections.unmodifiableList(readConnections);
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

    private static void applyConnectionPragmas(Connection c, DeploymentProfile profile)
            throws SQLException {
        try (Statement stmt = c.createStatement()) {
            for (String pragma : connectionPragmas(profile)) {
                stmt.execute("PRAGMA " + pragma);
            }
        }
    }

    /**
     * Renders the ordered list of {@code PRAGMA} statements for the given
     * profile. {@code journal_mode = WAL} is always first because the other
     * PRAGMAs rely on WAL being active to take effect on their intended
     * semantics. The {@code locking_mode} PRAGMA is emitted only when the
     * profile selects a non-default value ({@link LockingMode#EXCLUSIVE}) —
     * SQLite's default is {@code NORMAL}, so emitting it explicitly would
     * be a no-op.
     *
     * <p><strong>locking_mode is sticky.</strong> Once a connection enters
     * {@code EXCLUSIVE}, the lock persists for the connection's lifetime and
     * cannot be downgraded back to {@code NORMAL} without closing and
     * reopening. This is fine for HomeSynapse: the PRAGMA is applied once
     * per connection at startup and the connection lives until shutdown.
     *
     * <p>The {@code cache_size} value is rendered as the negative of
     * {@link DeploymentProfile#cacheSizeKiB()} — SQLite interprets negative
     * values as KiB.
     *
     * @param profile the deployment profile supplying tuning values
     * @return the ordered list of PRAGMA bodies (without the {@code PRAGMA }
     *         prefix); 8 elements for {@link LockingMode#NORMAL}, 9 for
     *         {@link LockingMode#EXCLUSIVE}
     */
    private static List<String> connectionPragmas(DeploymentProfile profile) {
        List<String> pragmas = new ArrayList<>(9);
        pragmas.add("journal_mode = WAL");
        pragmas.add("synchronous = NORMAL");
        pragmas.add("cache_size = -" + profile.cacheSizeKiB());
        pragmas.add("mmap_size = " + profile.mmapSizeBytes());
        pragmas.add("temp_store = MEMORY");
        pragmas.add("busy_timeout = " + profile.busyTimeoutMs());
        pragmas.add("journal_size_limit = " + profile.journalSizeLimitBytes());
        pragmas.add("cell_size_check = ON");
        if (profile.lockingMode() != LockingMode.NORMAL) {
            pragmas.add("locking_mode = " + profile.lockingMode().name());
        }
        return pragmas;
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
