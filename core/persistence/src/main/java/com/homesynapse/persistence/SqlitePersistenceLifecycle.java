/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.platform.identity.HomeId;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production implementation of {@link PersistenceLifecycle} that wires all
 * persistence components into a single lifecycle facade.
 *
 * <p>{@code SqlitePersistenceLifecycle} is the single entry point for
 * persistence layer initialization, invoked by the startup sequence
 * (Doc 12 §3.4). It handles:</p>
 * <ul>
 *   <li>Database initialization — creating or opening the SQLite database,
 *       setting PRAGMAs, running migrations via {@link MigrationRunner}</li>
 *   <li>Store construction — building {@link SqliteEventStore},
 *       {@link SqliteCheckpointStore}, {@link SqliteViewCheckpointStore},
 *       and {@link AtomicCheckpointWriter} on top of the initialized
 *       {@link DatabaseExecutor}</li>
 *   <li>Storage validation — detecting SD card backing storage and logging
 *       a WARNING (Research R-04)</li>
 *   <li>Graceful shutdown — flushing the WAL via
 *       {@code PRAGMA wal_checkpoint(TRUNCATE)}, stopping the executor,
 *       closing connections</li>
 *   <li>Readiness signaling — the {@link CompletableFuture} returned by
 *       {@link #start()} completes when the persistence layer is fully
 *       operational</li>
 * </ul>
 *
 * <p><strong>Backup and restore</strong> are not implemented in M2.9.
 * {@link #createBackup(BackupOptions)} and {@link #restoreFromBackup(Path)}
 * throw {@link UnsupportedOperationException}. Backup infrastructure is
 * post-M2 scope.</p>
 *
 * <p><strong>Thread safety:</strong> {@link #start()} and {@link #stop()}
 * are called sequentially by the startup system (Doc 12). The
 * {@code volatile started} flag provides visibility across threads for
 * the store accessors. No {@code synchronized} blocks are used
 * (LTD-11).</p>
 *
 * @see PersistenceLifecycle
 * @see DatabaseExecutor
 * @see SqliteEventStore
 * @since 1.0
 */
final class SqlitePersistenceLifecycle implements PersistenceLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(SqlitePersistenceLifecycle.class);

    /** Classpath directory holding the events-database migration scripts. */
    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";

    /**
     * Ordered list of migration files — the authoritative manifest (LTD-07).
     *
     * <p>V002 (M3 bridge) added the {@code subscriber_dead_letters} table
     * defined in AMD-36. V003 added the {@code snapshots} table for State
     * Projection rebuild performance and dropped the redundant
     * {@code idx_events_subject} index. V004 (M3.5b) adds operational
     * indices on {@code subscriber_dead_letters} for admin query paths.</p>
     */
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql",
            "V002__subscriber_dead_letter_queue.sql",
            "V003__add_snapshots_and_drop_redundant_index.sql",
            "V004__dlq_operational_indices.sql");

    private final Path databasePath;
    private final int readThreadCount;
    private final Clock clock;
    private final HomeId homeId;
    private final List<Class<? extends DomainEvent>> eventClasses;
    private final Function<WriteCoordinator, WriteCoordinator> writeCoordinatorDecorator;

    // Constructed during start()
    private DatabaseExecutor databaseExecutor;
    private SqliteEventStore eventStore;
    private SqliteCheckpointStore checkpointStore;
    private SqliteViewCheckpointStore viewCheckpointStore;
    private AtomicCheckpointWriter atomicCheckpointWriter;
    private volatile boolean started = false;

    /**
     * Creates a new lifecycle manager for the persistence layer.
     *
     * @param databasePath   full path to the SQLite database file
     *                       (e.g., {@code /var/lib/homesynapse/homesynapse-events.db}
     *                       in production, {@code @TempDir} path in tests)
     * @param readThreadCount number of read connections/threads (default 2,
     *                        per AMD-27); must be {@code >= 1}
     * @param clock          injected clock for all timestamp operations;
     *                       use {@code Clock.systemUTC()} in production,
     *                       {@code Clock.fixed(...)} in tests
     * @param homeId         the home identity for this installation (AMD-34);
     *                       passed to {@link SqliteEventStore} for the
     *                       {@code home_id} column; never {@code null}
     * @param eventClasses   the explicit list of {@link DomainEvent} record
     *                       classes to register for polymorphic serialization;
     *                       must not be empty (no classpath scanning per LTD-07)
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code readThreadCount < 1}
     */
    public SqlitePersistenceLifecycle(
            Path databasePath,
            int readThreadCount,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses) {
        this(databasePath, readThreadCount, clock, homeId, eventClasses,
                Function.identity());
    }

    /**
     * Test-only constructor accepting a {@link WriteCoordinator} decorator
     * applied to the {@link DatabaseExecutor}'s underlying
     * {@code PlatformThreadWriteCoordinator}. Lives in the same package as
     * {@link PersistenceTestHarness}, which calls this overload from
     * {@code startWithWriteCoordinator(...)} to install a
     * {@code ThrottledWriteCoordinator} (M3.4b).
     *
     * <p>Pass {@link Function#identity()} for production-equivalent behavior.
     * Production composition (M3.6) MUST use the public 5-arg constructor.</p>
     *
     * @param databasePath              full path to the SQLite database file
     * @param readThreadCount           number of read connections/threads
     * @param clock                     injected clock
     * @param homeId                    home identity for this installation
     * @param eventClasses              domain-event record classes
     * @param writeCoordinatorDecorator decorator applied to the
     *                                  {@code WriteCoordinator}; never
     *                                  {@code null}
     */
    SqlitePersistenceLifecycle(
            Path databasePath,
            int readThreadCount,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses,
            Function<WriteCoordinator, WriteCoordinator> writeCoordinatorDecorator) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath");
        if (readThreadCount < 1) {
            throw new IllegalArgumentException(
                    "readThreadCount must be >= 1, got " + readThreadCount);
        }
        this.readThreadCount = readThreadCount;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.homeId = Objects.requireNonNull(homeId, "homeId");
        this.eventClasses = List.copyOf(
                Objects.requireNonNull(eventClasses, "eventClasses"));
        this.writeCoordinatorDecorator = Objects.requireNonNull(
                writeCoordinatorDecorator, "writeCoordinatorDecorator");
    }

    // ──────────────────────────────────────────────────────────────────
    // PersistenceLifecycle implementation
    // ──────────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Initialization sequence:</p>
     * <ol>
     *   <li>Detect removable/SD card storage (Research R-04) — best-effort,
     *       never blocks startup</li>
     *   <li>Create and start {@link DatabaseExecutor} — handles database file
     *       creation, creation-time PRAGMAs, connection PRAGMAs, migration
     *       execution via {@link MigrationRunner}, read connections, write
     *       coordinator, and read executor</li>
     *   <li>Construct serialization infrastructure —
     *       {@link EventTypeRegistry}, {@link PersistenceObjectMapper},
     *       {@link JacksonWarmup} (on a platform thread per LTD-19 /
     *       AMD-30), {@link EventPayloadCodec}</li>
     *   <li>Construct stores — {@link SqliteEventStore},
     *       {@link SqliteCheckpointStore}, {@link SqliteViewCheckpointStore},
     *       {@link AtomicCheckpointWriter}</li>
     * </ol>
     *
     * <p>The work runs synchronously on the calling thread. The startup
     * system (Doc 12) calls this from a platform thread, satisfying the
     * {@link JacksonWarmup} platform-thread requirement. The returned
     * {@link CompletableFuture} is already completed when this method
     * returns.</p>
     */
    @Override
    public CompletableFuture<Void> start() {
        if (started) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            // 1. SD card detection (Research R-04) — best-effort, never throws
            detectStorageType(databasePath);

            // 2. Create and start DatabaseExecutor.
            //    start() handles: file creation, creation-time PRAGMAs
            //    (page_size, auto_vacuum) on new databases, the 8 LTD-03
            //    connection PRAGMAs with journal_mode=WAL first,
            //    MigrationRunner execution, read connections, write coordinator,
            //    and read executor.
            databaseExecutor = new DatabaseExecutor(
                    readThreadCount, clock, writeCoordinatorDecorator);
            databaseExecutor.start(
                    databasePath,
                    EVENTS_MIGRATION_PATH,
                    EVENTS_MIGRATION_FILES,
                    MigrationConfig.freshInstall());

            // 3. Construct serialization infrastructure.
            //    Construction order matters: registry → mapper → warmup → codec.
            //    JacksonWarmup.warmup() MUST run on a platform thread before
            //    any virtual thread touches the codec (LTD-19 / DECIDE-M2-05).
            //    The calling thread is the startup system's platform thread.
            EventTypeRegistry registry = new EventTypeRegistry(eventClasses);
            ObjectMapper mapper = PersistenceObjectMapper.create();
            JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);
            EventPayloadCodec codec = new EventPayloadCodec(registry, warmup);

            // 4. Construct stores on top of the initialized executor.
            eventStore = new SqliteEventStore(
                    databaseExecutor, codec, registry, clock, homeId);
            checkpointStore = new SqliteCheckpointStore(
                    databaseExecutor, clock);
            viewCheckpointStore = new SqliteViewCheckpointStore(
                    databaseExecutor, clock);
            atomicCheckpointWriter = new AtomicCheckpointWriter(
                    databaseExecutor, clock);

            started = true;
            LOG.info("Persistence layer started: database={}, readThreads={}",
                    databasePath, readThreadCount);

            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            // Clean up any partially initialized resources.
            if (databaseExecutor != null) {
                try {
                    databaseExecutor.shutdown();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Shutdown sequence:</p>
     * <ol>
     *   <li>WAL flush — {@code PRAGMA wal_checkpoint(TRUNCATE)} through the
     *       {@link WriteCoordinator} at {@link WritePriority#WAL_CHECKPOINT}
     *       to ensure all WAL data is in the main database file. A failed
     *       checkpoint is non-fatal — SQLite recovers the WAL on next
     *       startup.</li>
     *   <li>Shutdown {@link DatabaseExecutor} — stops the write coordinator,
     *       read executor, and closes all connections.</li>
     * </ol>
     */
    @Override
    public void stop() {
        if (!started) {
            return;
        }

        // 1. WAL flush — ensure all data is in the main database file.
        //    Routed through the write coordinator so it does not interleave
        //    with pending writes (W-4).
        try {
            databaseExecutor.writeCoordinator().submit(
                    WritePriority.WAL_CHECKPOINT, () -> {
                        try (var stmt = databaseExecutor.writeConnection()
                                .createStatement()) {
                            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                        }
                        return null;
                    });
            LOG.info("WAL checkpoint completed: database={}", databasePath);
        } catch (Exception e) {
            // Non-fatal — SQLite will recover the WAL on next startup.
            LOG.warn("WAL checkpoint failed during shutdown: {}",
                    e.getMessage());
        }

        // 2. Shutdown DatabaseExecutor (stops coordinator, read executor,
        //    closes all connections).
        databaseExecutor.shutdown();

        started = false;
        LOG.info("Persistence layer stopped: database={}", databasePath);
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Not yet implemented.</strong> Backup infrastructure is
     * post-M2 scope.</p>
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public BackupResult createBackup(BackupOptions options) {
        throw new UnsupportedOperationException(
                "Backup not yet implemented. Planned for post-M2 milestone.");
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Not yet implemented.</strong> Restore infrastructure is
     * post-M2 scope.</p>
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void restoreFromBackup(Path backupDirectory) {
        throw new UnsupportedOperationException(
                "Restore not yet implemented. Planned for post-M2 milestone.");
    }

    // ──────────────────────────────────────────────────────────────────
    // Store accessors — concrete types for app-module wiring
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns the production event store and publisher.
     *
     * <p>The concrete type is returned (not the {@code EventStore} interface)
     * so the app module can wire both the {@code EventPublisher} and
     * {@code EventStore} roles from the same instance.</p>
     *
     * @return the initialized event store, never {@code null}
     * @throws IllegalStateException if the persistence layer has not been
     *                               started via {@link #start()}
     */
    public SqliteEventStore eventStore() {
        requireStarted();
        return eventStore;
    }

    /**
     * Returns the production subscriber checkpoint store.
     *
     * @return the initialized checkpoint store, never {@code null}
     * @throws IllegalStateException if the persistence layer has not been
     *                               started via {@link #start()}
     */
    public SqliteCheckpointStore checkpointStore() {
        requireStarted();
        return checkpointStore;
    }

    /**
     * Returns the production view checkpoint store.
     *
     * @return the initialized view checkpoint store, never {@code null}
     * @throws IllegalStateException if the persistence layer has not been
     *                               started via {@link #start()}
     */
    public SqliteViewCheckpointStore viewCheckpointStore() {
        requireStarted();
        return viewCheckpointStore;
    }

    /**
     * Returns the atomic checkpoint writer for same-transaction composition
     * of subscriber and view checkpoints.
     *
     * @return the initialized atomic checkpoint writer, never {@code null}
     * @throws IllegalStateException if the persistence layer has not been
     *                               started via {@link #start()}
     */
    public AtomicCheckpointWriter atomicCheckpointWriter() {
        requireStarted();
        return atomicCheckpointWriter;
    }

    // ──────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────

    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("Persistence layer not started");
        }
    }

    /**
     * Best-effort detection of removable/SD card storage backing the
     * database path. Logs a WARNING if the database resides on storage
     * identified as an SD card (mmcblk device).
     *
     * <p>On non-Linux platforms (development machines, CI), this method
     * does nothing. Detection failures are silently ignored — this is a
     * user-protection feature, not a correctness requirement.</p>
     *
     * @param dbPath the database file path to check
     */
    private static void detectStorageType(Path dbPath) {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) {
            return;
        }

        try {
            Path parentDir = dbPath.getParent();
            if (parentDir == null || !Files.exists(parentDir)) {
                return;
            }
            FileStore store = Files.getFileStore(parentDir);
            String deviceName = store.name();
            if (deviceName != null && deviceName.contains("mmcblk")) {
                LOG.warn("Database is on removable storage ({}). "
                        + "SD card storage is not recommended for production "
                        + "use — database corruption and performance "
                        + "degradation are common. See "
                        + "https://homesynapse.com/docs/storage for "
                        + "recommended configurations.", deviceName);
            }
        } catch (IOException | SecurityException e) {
            // Best-effort detection — never block startup, never throw.
        }
    }
}
