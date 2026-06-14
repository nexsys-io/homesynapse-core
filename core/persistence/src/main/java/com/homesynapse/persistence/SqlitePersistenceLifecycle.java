/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.bus.SubscriberReadConnectionFactory;
import com.homesynapse.platform.identity.HomeId;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
     * indices on {@code subscriber_dead_letters} for admin query paths.
     * V005 (M6.3) adds the {@code payload_iv} / {@code dek_ref} at-rest
     * encryption columns (Doc 15 §4.1).</p>
     */
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql",
            "V002__subscriber_dead_letter_queue.sql",
            "V003__add_snapshots_and_drop_redundant_index.sql",
            "V004__dlq_operational_indices.sql",
            "V005__at_rest_payload_encryption_columns.sql");

    /**
     * The MVP default {@code crypto.encryption.encrypted_scopes} set (Doc 15
     * §9 / OQ-15-2). Mirrors config's {@code EncryptionScope}
     * {@code DEFAULT_ENCRYPTED_SCOPE_IDS} as a {@code java.base} {@code Set}
     * (persistence must not name a {@code config} type — Doc 15 §3.8). Wired
     * into the event store iff a {@link PayloadCipher} is present (M6.3); a
     * null cipher leaves the enabled set empty (the M6.2 plaintext state).
     */
    private static final Set<String> DEFAULT_ENCRYPTED_SCOPES =
            Set.of("identity", "presence_personal");

    private final Path databasePath;
    private final PersistenceConfig config;
    private final Clock clock;
    private final HomeId homeId;
    private final List<Class<? extends DomainEvent>> eventClasses;
    private final Function<WriteCoordinator, WriteCoordinator> writeCoordinatorDecorator;

    /**
     * At-rest payload cipher (Doc 15 §3.8 / M6.3). Nullable — {@code null} is
     * the M6.2 state (no crypto) and the path every existing test/harness
     * takes; the event store is then wired with an empty enabled-scope set
     * (plaintext for all). {@link PersistenceFactory#start} forwards the
     * composition-root cipher here.
     */
    private final PayloadCipher payloadCipher;

    // Constructed during start()
    private DatabaseExecutor databaseExecutor;
    private SqliteEventStore eventStore;
    private SqliteCheckpointStore checkpointStore;
    private SqliteViewCheckpointStore viewCheckpointStore;
    private AtomicCheckpointWriter atomicCheckpointWriter;
    private SqliteStateStore stateStore;
    private SqliteDeadLetterStore deadLetterStore;
    private SqliteSubscriberReadConnectionFactory subscriberReadConnectionFactory;
    private volatile boolean started = false;
    private volatile boolean abandoned = false;

    /**
     * Creates a new lifecycle manager for the persistence layer.
     *
     * @param databasePath full path to the SQLite database file
     *                     (e.g., {@code /var/lib/homesynapse/homesynapse-events.db}
     *                     in production, {@code @TempDir} path in tests)
     * @param config       persistence configuration bundling the
     *                     {@link DeploymentProfile} (read thread count,
     *                     PRAGMA values) and {@code RetentionPolicy}; never
     *                     {@code null}. Use
     *                     {@link PersistenceConfig#HOME_DEFAULT} for the MVP
     *                     default.
     * @param clock        injected clock for all timestamp operations; use
     *                     {@code Clock.systemUTC()} in production,
     *                     {@code Clock.fixed(...)} in tests
     * @param homeId       the home identity for this installation (AMD-34);
     *                     passed to {@link SqliteEventStore} for the
     *                     {@code home_id} column; never {@code null}
     * @param eventClasses the explicit list of {@link DomainEvent} record
     *                     classes to register for polymorphic serialization;
     *                     must not be empty (no classpath scanning per LTD-07)
     * @throws NullPointerException if any argument is {@code null}
     */
    public SqlitePersistenceLifecycle(
            Path databasePath,
            PersistenceConfig config,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses) {
        this(databasePath, config, clock, homeId, eventClasses,
                Function.identity(), null);
    }

    /**
     * Production constructor with the M6.3 at-rest payload cipher (Doc 15
     * §3.8). Used by {@link PersistenceFactory#start}.
     *
     * @param databasePath  full path to the SQLite database file
     * @param config        persistence configuration
     * @param clock         injected clock
     * @param homeId        home identity for this installation (AMD-34)
     * @param eventClasses  domain-event record classes
     * @param payloadCipher the at-rest cipher, or {@code null} when at-rest
     *                      payload encryption is unavailable (the M6.2 state)
     */
    public SqlitePersistenceLifecycle(
            Path databasePath,
            PersistenceConfig config,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses,
            PayloadCipher payloadCipher) {
        this(databasePath, config, clock, homeId, eventClasses,
                Function.identity(), payloadCipher);
    }

    /**
     * Test-only constructor accepting a {@link WriteCoordinator} decorator
     * applied to the {@link DatabaseExecutor}'s underlying
     * {@code PlatformThreadWriteCoordinator}. Lives in the same package as
     * {@link PersistenceTestHarness}, which calls this overload from
     * {@code startWithWriteCoordinator(...)} to install a
     * {@code ThrottledWriteCoordinator} (M3.4b). No at-rest cipher (M6.2
     * plaintext state).
     *
     * <p>Pass {@link Function#identity()} for production-equivalent behavior.
     * Production composition (M3.6) MUST use a public constructor.</p>
     *
     * @param databasePath              full path to the SQLite database file
     * @param config                    persistence configuration
     * @param clock                     injected clock
     * @param homeId                    home identity for this installation
     * @param eventClasses              domain-event record classes
     * @param writeCoordinatorDecorator decorator applied to the
     *                                  {@code WriteCoordinator}; never
     *                                  {@code null}
     */
    SqlitePersistenceLifecycle(
            Path databasePath,
            PersistenceConfig config,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses,
            Function<WriteCoordinator, WriteCoordinator> writeCoordinatorDecorator) {
        this(databasePath, config, clock, homeId, eventClasses,
                writeCoordinatorDecorator, null);
    }

    /**
     * Canonical constructor — combines the write-coordinator decorator and
     * the at-rest cipher. Every other constructor delegates here.
     *
     * @param databasePath              full path to the SQLite database file
     * @param config                    persistence configuration
     * @param clock                     injected clock
     * @param homeId                    home identity for this installation
     * @param eventClasses              domain-event record classes
     * @param writeCoordinatorDecorator decorator applied to the
     *                                  {@code WriteCoordinator}; never
     *                                  {@code null}
     * @param payloadCipher             the at-rest cipher, or {@code null}
     */
    SqlitePersistenceLifecycle(
            Path databasePath,
            PersistenceConfig config,
            Clock clock,
            HomeId homeId,
            List<Class<? extends DomainEvent>> eventClasses,
            Function<WriteCoordinator, WriteCoordinator> writeCoordinatorDecorator,
            PayloadCipher payloadCipher) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.homeId = Objects.requireNonNull(homeId, "homeId");
        this.eventClasses = List.copyOf(
                Objects.requireNonNull(eventClasses, "eventClasses"));
        this.writeCoordinatorDecorator = Objects.requireNonNull(
                writeCoordinatorDecorator, "writeCoordinatorDecorator");
        this.payloadCipher = payloadCipher; // nullable by design (M6.2 state)
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
                    config.profile(), clock, writeCoordinatorDecorator);
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
            //    M6.3 (Doc 15 §3.4): enable the at-rest encrypted-scope set iff
            //    a cipher is wired. Cipher-presence is the at_rest_enabled
            //    master switch for this WU — live ConfigModel wiring of the
            //    crypto.encryption knobs is app-bootstrap scope. A null cipher
            //    (the M6.2 state, and every no-crypto test/harness) leaves the
            //    set empty → plaintext for all scopes. A sensitive-scope event
            //    with an enabled set but a null cipher fails closed at the
            //    write path (it cannot occur through this wiring, which gates
            //    the set on cipher-presence).
            Set<String> encryptedScopes = payloadCipher != null
                    ? DEFAULT_ENCRYPTED_SCOPES
                    : Set.of();
            eventStore = new SqliteEventStore(
                    databaseExecutor, codec, registry, clock, homeId,
                    payloadCipher, encryptedScopes);
            checkpointStore = new SqliteCheckpointStore(
                    databaseExecutor, clock);
            viewCheckpointStore = new SqliteViewCheckpointStore(
                    databaseExecutor, clock);
            atomicCheckpointWriter = new AtomicCheckpointWriter(
                    databaseExecutor, clock);

            // 5. Checkpoint-specific ObjectMapper (M3.6d-b Gap 1).
            //    CheckpointSerializer requires Include.ALWAYS so null
            //    staleAfter and null attribute values survive round-trip
            //    (see persistence MODULE_CONTEXT gotcha). We .copy() the
            //    events mapper so the PersistenceJacksonModule, ULID serdes,
            //    and recycler pool stay registered — only the serialization
            //    inclusion changes.
            ObjectMapper checkpointMapper = mapper.copy()
                    .setSerializationInclusion(JsonInclude.Include.ALWAYS);
            CheckpointSerializer checkpointSerializer =
                    new CheckpointSerializer(checkpointMapper);

            // 6. State store (in-memory map + checkpoint durability via the
            //    view checkpoint store). The viewName MUST match the
            //    ProjectionId used by StateProjection when writing checkpoints
            //    (HomeSynapseCore.PROJECTION_SUBSCRIBER_ID = "state_projection")
            //    — otherwise rehydrate-from-checkpoint silently reads null and
            //    crash recovery cannot restore state.
            stateStore = new SqliteStateStore(
                    viewCheckpointStore, checkpointSerializer, "state_projection");

            // 7. Dead-letter store (V002 subscriber_dead_letters table).
            deadLetterStore = new SqliteDeadLetterStore(databaseExecutor);

            // 8. Per-subscriber read connection factory (INV-SUB-ISO-02).
            subscriberReadConnectionFactory =
                    new SqliteSubscriberReadConnectionFactory(
                            databasePath, config.profile());

            started = true;
            LOG.info("Persistence layer started: database={}, profile={}, readThreads={}",
                    databasePath, config.profile(), config.profile().readThreadCount());

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
        if (!started || abandoned) {
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
     * Shuts down the persistence layer without performing a WAL checkpoint.
     *
     * <p>Releases OS-level resources: shuts down the {@link DatabaseExecutor}
     * (which closes the write coordinator, read executor, and all JDBC
     * connections). The WAL and {@code -shm} files remain on disk in
     * whatever state they are; SQLite's automatic WAL recovery handles them
     * on next open.</p>
     *
     * <p>Use for crash simulation in tests and emergency shutdown in
     * production (e.g., imminent power loss). Normal shutdown MUST use
     * {@link #stop()}.</p>
     *
     * <p>Do NOT use for normal shutdown — {@link #stop()} performs WAL
     * checkpoint before closing, ensuring all data is in the main database
     * file.</p>
     *
     * <p>Idempotent. Calling this after {@link #stop()} is a no-op. Calling
     * {@link #stop()} after this is a no-op.</p>
     *
     * @implNote Mutual exclusion with {@link #stop()} is enforced via the
     *           {@code abandoned} flag. Both methods check it before acting.
     *           INV-ES-04 is preserved: events already persisted survive
     *           abandon; the replay mechanism re-processes the gap between
     *           the last projection checkpoint and the event store head.
     */
    void abandonWithoutCheckpoint() {
        if (!started || abandoned) {
            return;
        }
        abandoned = true;
        started = false;
        databaseExecutor.shutdown();
        LOG.warn("Persistence layer abandoned (no WAL checkpoint): database={}",
                databasePath);
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

    /**
     * Returns the materialized state store (M3.6d-b). The same instance
     * implements both {@link com.homesynapse.state.StateStore} and
     * {@link com.homesynapse.state.StateCheckpointSource}; the composition
     * root exposes each role through its respective interface.
     *
     * @return the initialized state store, never {@code null}
     * @throws IllegalStateException if the persistence layer has not been
     *                               started via {@link #start()}
     */
    SqliteStateStore stateStore() {
        requireStarted();
        return stateStore;
    }

    /**
     * Returns the durable dead-letter store (M3.6d-b). Backs the bus's
     * in-memory subscriber DLQ ring via the
     * {@link com.homesynapse.event.bus.PersistentDlqWriter} seam.
     *
     * @return the initialized dead-letter store, never {@code null}
     * @throws IllegalStateException if the persistence layer has not been
     *                               started via {@link #start()}
     */
    SqliteDeadLetterStore deadLetterStore() {
        requireStarted();
        return deadLetterStore;
    }

    /**
     * Returns the per-subscriber read connection factory (M3.6d-b,
     * INV-SUB-ISO-02). Each call to {@code create(subscriberId)} opens a
     * new dedicated platform-thread + SQLite read connection.
     *
     * @return the initialized factory, never {@code null}
     * @throws IllegalStateException if the persistence layer has not been
     *                               started via {@link #start()}
     */
    SubscriberReadConnectionFactory subscriberReadConnectionFactory() {
        requireStarted();
        return subscriberReadConnectionFactory;
    }

    /**
     * Returns the database executor — package-private access for
     * {@link PersistenceFactory} to reach the {@code WriteCoordinator} for
     * the {@code IntSupplier} surfaced to the event bus (DEC-M3-14).
     *
     * @return the initialized database executor, never {@code null}
     * @throws IllegalStateException if the persistence layer has not been
     *                               started via {@link #start()}
     */
    DatabaseExecutor databaseExecutor() {
        requireStarted();
        return databaseExecutor;
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
