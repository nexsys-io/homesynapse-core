/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.spike.wal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * D1: WAL Checkpoint Starvation Under Concurrent Reader — spike plan Phase D1.
 *
 * <p>Runs three independent scenarios back-to-back to determine whether a continuous
 * reader (simulating the M3 State Projection) causes WAL checkpoint starvation at
 * HomeSynapse event rates, and whether the bounded-window reader pattern prevents
 * it — with or without raising {@code journal_size_limit} from 6 MB to 64 MB.
 *
 * <ul>
 *   <li><b>Run 1</b> — continuous reader (never commits read txn); LTD-03 defaults
 *       (6 MB journal_size_limit). Pathology reproduction.
 *   <li><b>Run 2</b> — bounded-window reader (close/reopen every 500 rows); 64 MB
 *       journal_size_limit; active passive-checkpoint every 30s. Full mitigation.
 *   <li><b>Run 3</b> — bounded-window reader; LTD-03 defaults (6 MB); no active
 *       checkpoint. Determines whether the 64 MB limit is load-bearing.
 * </ul>
 *
 * <p>Gates two DRAFT amendments:
 * <ul>
 *   <li><b>AMD-38</b> (checkpoint policy: 200 events / 2 s) — validated if Run 1
 *       reproduces and Run 2/3 prevents the pathology.
 *   <li><b>AMD-39</b> (journal_size_limit 6 MB → 64 MB) — validated if Run 3 fails
 *       (WAL hits 6 MB and gets truncated under bounded reader load).
 * </ul>
 *
 * <p>Usage: {@code java -cp ... com.homesynapse.spike.wal.D1WalStarvationTest <db-prefix>}
 * <br>Produces three database files: {@code <prefix>-run1.db}, {@code <prefix>-run2.db},
 * {@code <prefix>-run3.db}. Files are NOT deleted post-run so Nick may inspect them.
 */
public final class D1WalStarvationTest {

    // ===== Shared run parameters =====
    private static final int EVENT_RATE_PER_SEC = 5;
    private static final int DURATION_SEC = 120;
    private static final int TOTAL_EVENTS = EVENT_RATE_PER_SEC * DURATION_SEC; // 600
    private static final long WRITE_INTERVAL_NANOS = 200_000_000L;             // 200 ms = 5/sec

    private static final int SUBJECT_POOL_SIZE = 50;
    private static final int READER_CHUNK_SIZE = 500;
    private static final long READER_ROW_DELAY_NANOS = 20_000_000L;            // 20 ms per row
    private static final long READER_EMPTY_BACKOFF_NANOS = 500_000_000L;       // 500 ms
    private static final long READER_IDLE_POLL_NANOS = 250_000_000L;           // Run 1 idle poll

    private static final long WAL_POLL_INTERVAL_NANOS = 100_000_000L;          // 100 ms
    private static final long ACTIVE_CHECKPOINT_INTERVAL_NANOS = 30_000_000_000L; // 30 s

    private static final long JOURNAL_SIZE_LIMIT_DEFAULT = 6_144_000L;         // LTD-03
    private static final long JOURNAL_SIZE_LIMIT_EXPANDED = 67_108_864L;       // AMD-39 (64 MB)

    private static final long RUN1_FAIL_THRESHOLD_BYTES = 6L * 1024 * 1024;    // Run 1 PASS = > 6 MB
    private static final long RUN2_PASS_THRESHOLD_BYTES = 8L * 1024 * 1024;    // Run 2 PASS = < 8 MB
    private static final long RUN3_PASS_THRESHOLD_BYTES = 6L * 1024 * 1024;    // Run 3 PASS = < 6 MB

    // ===== V001 schema (inlined from V001__initial_event_store_schema.sql) =====
    // The events table only — subscriber_checkpoints and view_checkpoints are unused by D1.
    // chain_hash is NOT NULL with a zero-hash default in V001; the spike binds 32 zero bytes
    // explicitly. The UNIQUE(subject_ref, subject_sequence) constraint is preserved — the
    // event generator uses a 50-entity pool with per-entity sequence counters to satisfy it.
    private static final String CREATE_EVENTS_TABLE = """
            CREATE TABLE IF NOT EXISTS events (
                global_position   INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id          BLOB(16) NOT NULL,
                home_id           BLOB(16) NOT NULL,
                event_type        TEXT     NOT NULL,
                schema_version    INTEGER  NOT NULL DEFAULT 1,
                ingest_time       INTEGER  NOT NULL,
                event_time        INTEGER,
                subject_ref       BLOB(16) NOT NULL,
                subject_type      TEXT     NOT NULL,
                subject_sequence  INTEGER  NOT NULL,
                priority          TEXT     NOT NULL DEFAULT 'NORMAL',
                origin            TEXT     NOT NULL DEFAULT 'UNKNOWN',
                actor_ref         BLOB(16),
                idempotency_key   TEXT,
                correlation_id    BLOB(16) NOT NULL,
                causation_id      BLOB(16),
                event_category    TEXT     NOT NULL,
                payload_size      INTEGER  NOT NULL,
                batch_id          BLOB(16),
                external_ref      TEXT,
                intent_kind       TEXT     NOT NULL DEFAULT 'UNSPECIFIED',
                logical_time      INTEGER  NOT NULL DEFAULT 0,
                node_id           INTEGER  NOT NULL DEFAULT 0,
                payload           BLOB     NOT NULL,
                chain_hash        BLOB(32) NOT NULL DEFAULT x'0000000000000000000000000000000000000000000000000000000000000000',
                UNIQUE(subject_ref, subject_sequence)
            )
            """;

    // 7 explicit indexes per V001 (the autoindex for UNIQUE(subject_ref, subject_sequence)
    // and the rowid PK index are created automatically).
    private static final String[] CREATE_INDEX_STATEMENTS = {
        "CREATE INDEX IF NOT EXISTS idx_events_subject     ON events(subject_ref, subject_sequence)",
        "CREATE INDEX IF NOT EXISTS idx_events_type        ON events(event_type, global_position)",
        "CREATE INDEX IF NOT EXISTS idx_events_correlation ON events(correlation_id, global_position)",
        "CREATE INDEX IF NOT EXISTS idx_events_ingest_time ON events(ingest_time)",
        "CREATE INDEX IF NOT EXISTS idx_events_event_time  ON events(COALESCE(event_time, ingest_time))",
        "CREATE INDEX IF NOT EXISTS idx_events_actor       ON events(actor_ref) WHERE actor_ref IS NOT NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_events_idempotency "
            + "ON events(home_id, idempotency_key) WHERE idempotency_key IS NOT NULL"
    };

    // 24 columns (omit AUTOINCREMENT global_position) — matches production single-event
    // append shape.
    private static final String INSERT_EVENT_SQL = """
            INSERT INTO events (
                event_id, home_id, event_type, schema_version, ingest_time,
                event_time, subject_ref, subject_type, subject_sequence,
                priority, origin, actor_ref, idempotency_key, correlation_id,
                causation_id, event_category, payload_size, batch_id,
                external_ref, intent_kind, logical_time, node_id, payload,
                chain_hash
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static final String SELECT_READER_SQL =
        "SELECT global_position, event_id, payload FROM events"
            + " WHERE global_position > ? ORDER BY global_position LIMIT " + READER_CHUNK_SIZE;

    // Cycle pattern for subject_type column (50 subjects mapped through a 5-entry cycle).
    private static final String[] SUBJECT_TYPE_CYCLE = {
        "DEVICE", "DEVICE", "DEVICE", "SENSOR", "AUTOMATION"
    };

    private static final String EVENT_TYPE = "io.homesynapse.sensor.state.reported";

    private D1WalStarvationTest() {
        // runnable class — use main()
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: D1WalStarvationTest <db-prefix>");
            System.exit(1);
        }
        String prefix = args[0];

        System.out.println("=== D1: WAL Checkpoint Starvation Under Concurrent Reader ===");
        System.out.printf(Locale.US,
            "Configuration: %d events/sec for %ds (= %d events total), %d-entity pool%n",
            EVENT_RATE_PER_SEC, DURATION_SEC, TOTAL_EVENTS, SUBJECT_POOL_SIZE);
        System.out.println();

        RunConfig run1Config = new RunConfig(
            "Run 1", "Continuous Reader (pathology reproduction)",
            prefix + "-run1.db",
            JOURNAL_SIZE_LIMIT_DEFAULT, /* boundedReader= */ false,
            /* activeCheckpoint= */ false);
        RunConfig run2Config = new RunConfig(
            "Run 2", "Bounded Reader + 64 MB limit + active checkpoint",
            prefix + "-run2.db",
            JOURNAL_SIZE_LIMIT_EXPANDED, /* boundedReader= */ true,
            /* activeCheckpoint= */ true);
        RunConfig run3Config = new RunConfig(
            "Run 3", "Bounded Reader only (6 MB limit, no active checkpoint)",
            prefix + "-run3.db",
            JOURNAL_SIZE_LIMIT_DEFAULT, /* boundedReader= */ true,
            /* activeCheckpoint= */ false);

        RunResult run1 = runScenario(run1Config);
        System.out.println();
        RunResult run2 = runScenario(run2Config);
        System.out.println();
        RunResult run3 = runScenario(run3Config);
        System.out.println();

        printSummary(run1, run2, run3);
        printGateDecisions(run1, run2, run3);
        printTimeSeries(run1);
        printTimeSeries(run2);
        printTimeSeries(run3);
    }

    // -----------------------------------------------------------------------------------
    // Scenario orchestration
    // -----------------------------------------------------------------------------------

    private static RunResult runScenario(RunConfig config) throws InterruptedException {
        System.out.printf(Locale.US, ">>> %s — %s%n", config.name(), config.description());
        System.out.printf(Locale.US,
            "    db=%s  journal_size_limit=%d  boundedReader=%s  activeCheckpoint=%s%n",
            config.dbPath(), config.journalSizeLimit(),
            config.boundedReader(), config.activeCheckpoint());

        // Fresh DB files
        deleteIfExists(config.dbPath());
        deleteIfExists(config.dbPath() + "-wal");
        deleteIfExists(config.dbPath() + "-shm");

        String jdbcUrl = "jdbc:sqlite:" + config.dbPath();

        // Setup: schema + PRAGMAs on initial connection
        try (Connection setupConn = DriverManager.getConnection(jdbcUrl)) {
            applyPragmas(setupConn, config.journalSizeLimit());
            try (Statement stmt = setupConn.createStatement()) {
                stmt.execute(CREATE_EVENTS_TABLE);
                for (String idx : CREATE_INDEX_STATEMENTS) {
                    stmt.execute(idx);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                config.name() + " setup failed: " + e.getMessage(), e);
        }

        // Per-scenario shared state
        ScenarioState state = new ScenarioState(jdbcUrl, config);

        // 1. WAL monitor starts FIRST (per brief — captures baseline samples).
        Thread monitorThread = new Thread(state::runWalMonitor, "d1-monitor-" + config.name());
        monitorThread.start();
        // Brief settle delay so monitor records baseline before writer/reader start.
        LockSupport.parkNanos(100_000_000L);

        // 2. Writer, reader (and optional checkpointer) await a shared latch for synchronized start.
        Thread writerThread = new Thread(state::runWriter, "d1-writer-" + config.name());
        Thread readerThread = new Thread(state::runReader, "d1-reader-" + config.name());
        Thread checkpointThread = config.activeCheckpoint()
            ? new Thread(state::runCheckpointer, "d1-checkpointer-" + config.name())
            : null;

        writerThread.start();
        readerThread.start();
        if (checkpointThread != null) {
            checkpointThread.start();
        }

        // Release the latch — writer/reader/(checkpointer) begin their loops simultaneously.
        long latchReleaseNanos = System.nanoTime();
        state.startLatch.countDown();

        // Writer drives the run length. When it completes (or fails) we shut everything down.
        writerThread.join();
        long runEndNanos = System.nanoTime();
        state.running = false;

        // Drain readers/checkpointer/monitor.
        readerThread.join(10_000);
        if (checkpointThread != null) {
            checkpointThread.join(10_000);
        }
        monitorThread.join(2_000);

        double durationSec = (runEndNanos - latchReleaseNanos) / 1_000_000_000.0;
        double writerRate = state.eventsWritten.get() / durationSec;

        // Threshold-based pass logic per brief (§Three Runs):
        //   Run 1 (continuous reader, no active cp): PASS = peak > 6 MB (pathology reproduced)
        //   Run 2 (bounded reader, 64 MB limit, active cp): PASS = peak < 8 MB
        //   Run 3 (bounded reader, 6 MB limit, no active cp): PASS = peak < 6 MB
        boolean pass;
        String thresholdDescription;
        if (!config.boundedReader()) {
            pass = state.walPeakBytes.get() > RUN1_FAIL_THRESHOLD_BYTES;
            thresholdDescription = "WAL peak > 6 MB";
        } else if (config.activeCheckpoint()) {
            pass = state.walPeakBytes.get() < RUN2_PASS_THRESHOLD_BYTES;
            thresholdDescription = "WAL peak < 8 MB";
        } else {
            pass = state.walPeakBytes.get() < RUN3_PASS_THRESHOLD_BYTES;
            thresholdDescription = "WAL peak < 6 MB";
        }

        RunResult result = new RunResult(
            config.name(),
            config.description(),
            config.boundedReader(),
            config.activeCheckpoint(),
            durationSec,
            state.eventsWritten.get(),
            writerRate,
            state.lastReaderPosition.get(),
            state.readerChunksCompleted.get(),
            state.activeCheckpointsTriggered.get(),
            state.walPeakBytes.get(),
            state.walFinalBytes.get(),
            state.walSampleCount.get(),
            new ArrayList<>(state.walSamples),
            pass,
            thresholdDescription
        );

        System.out.printf(Locale.US,
            "    Completed: events=%d duration=%.1fs walPeak=%.2f MB result=%s%n",
            result.eventsWritten(), result.durationSec(),
            result.walPeakBytes() / (1024.0 * 1024.0),
            result.pass() ? "PASS" : "FAIL");
        return result;
    }

    // -----------------------------------------------------------------------------------
    // PRAGMA application
    // -----------------------------------------------------------------------------------

    /**
     * Applies LTD-03 PRAGMAs with a caller-controlled {@code journal_size_limit}. PragmaConfig
     * exists in this module but pins journal_size_limit to the 6 MB default; D1 needs Run 2 to
     * use the AMD-39 proposed value (64 MB), so the PRAGMA list is inlined here.
     */
    private static void applyPragmas(Connection conn, long journalSizeLimitBytes) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA synchronous = NORMAL");
            stmt.execute("PRAGMA cache_size = -128000");
            stmt.execute("PRAGMA mmap_size = 1073741824");
            stmt.execute("PRAGMA temp_store = MEMORY");
            stmt.execute("PRAGMA journal_size_limit = " + journalSizeLimitBytes);
            stmt.execute("PRAGMA busy_timeout = 5000");
        }
    }

    // -----------------------------------------------------------------------------------
    // Per-scenario state (one instance per run)
    // -----------------------------------------------------------------------------------

    private static final class ScenarioState {

        final String jdbcUrl;
        final RunConfig config;

        final CountDownLatch startLatch = new CountDownLatch(1);
        volatile boolean running = true;

        // Writer-owned, read by all
        final AtomicInteger eventsWritten = new AtomicInteger(0);

        // Reader-owned
        final AtomicLong lastReaderPosition = new AtomicLong(0);
        final AtomicInteger readerChunksCompleted = new AtomicInteger(0);

        // Checkpointer-owned
        final AtomicInteger activeCheckpointsTriggered = new AtomicInteger(0);

        // Monitor-owned
        final AtomicLong walPeakBytes = new AtomicLong(0);
        final AtomicLong walFinalBytes = new AtomicLong(0);
        final AtomicInteger walSampleCount = new AtomicInteger(0);
        final List<long[]> walSamples = new ArrayList<>(1600); // (elapsed_ms, bytes) — single-threaded writes from monitor only

        // Pre-generated event payloads (shared, immutable)
        final byte[] homeId;
        final byte[][] subjectRefs;
        final byte[] chainHashZero;

        ScenarioState(String jdbcUrl, RunConfig config) {
            this.jdbcUrl = jdbcUrl;
            this.config = config;
            this.homeId = SpikeUlidGenerator.generate();
            this.subjectRefs = new byte[SUBJECT_POOL_SIZE][];
            for (int i = 0; i < SUBJECT_POOL_SIZE; i++) {
                this.subjectRefs[i] = SpikeUlidGenerator.generate();
            }
            this.chainHashZero = new byte[32]; // 32 bytes of zero per AMD-37 default
        }

        // -----------------------------------------------------------------------------------
        // Writer: 5 events/sec via BEGIN IMMEDIATE; INSERT; COMMIT per event
        // -----------------------------------------------------------------------------------

        void runWriter() {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                applyPragmas(conn, config.journalSizeLimit());
                // autoCommit=true (JDBC default) — explicit "BEGIN IMMEDIATE" / "COMMIT" via
                // raw Statement.execute() opens and closes the transaction. xerial sqlite-jdbc
                // recognizes these and adjusts internal autocommit state accordingly.

                int[] subjectSequences = new int[SUBJECT_POOL_SIZE];

                startLatch.await();

                try (Statement txnStmt = conn.createStatement();
                     PreparedStatement insert = conn.prepareStatement(INSERT_EVENT_SQL)) {

                    for (int i = 0; i < TOTAL_EVENTS && running; i++) {
                        int entityIdx = i % SUBJECT_POOL_SIZE;
                        subjectSequences[entityIdx]++;
                        int seq = subjectSequences[entityIdx];

                        long ingestMicros = System.currentTimeMillis() * 1000L;
                        byte[] payload = buildPayload(seq, ingestMicros);

                        txnStmt.execute("BEGIN IMMEDIATE");
                        bindInsert(insert, entityIdx, seq, ingestMicros, payload);
                        insert.executeUpdate();
                        txnStmt.execute("COMMIT");

                        eventsWritten.incrementAndGet();
                        LockSupport.parkNanos(WRITE_INTERVAL_NANOS);
                    }
                }
            } catch (SQLException e) {
                System.err.println("[" + config.name() + "] writer SQLException: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void bindInsert(PreparedStatement ps, int entityIdx, int seq,
                                long ingestMicros, byte[] payload) throws SQLException {
            int idx = 1;
            ps.setBytes(idx++, SpikeUlidGenerator.generate());     // event_id
            ps.setBytes(idx++, homeId);                            // home_id
            ps.setString(idx++, EVENT_TYPE);                       // event_type
            ps.setInt(idx++, 1);                                   // schema_version
            ps.setLong(idx++, ingestMicros);                       // ingest_time
            ps.setLong(idx++, ingestMicros);                       // event_time (same — sensor)
            ps.setBytes(idx++, subjectRefs[entityIdx]);            // subject_ref
            ps.setString(idx++, SUBJECT_TYPE_CYCLE[entityIdx % SUBJECT_TYPE_CYCLE.length]);
            ps.setInt(idx++, seq);                                 // subject_sequence
            ps.setString(idx++, "DIAGNOSTIC");                     // priority
            ps.setString(idx++, "DEVICE_AUTONOMOUS");              // origin
            ps.setNull(idx++, Types.BLOB);                         // actor_ref
            ps.setNull(idx++, Types.VARCHAR);                      // idempotency_key
            ps.setBytes(idx++, SpikeUlidGenerator.generate());     // correlation_id (NOT NULL)
            ps.setNull(idx++, Types.BLOB);                         // causation_id
            ps.setString(idx++, "[\"environmental\"]");            // event_category
            ps.setInt(idx++, payload.length);                      // payload_size
            ps.setNull(idx++, Types.BLOB);                         // batch_id
            ps.setNull(idx++, Types.VARCHAR);                      // external_ref
            ps.setString(idx++, "UNSPECIFIED");                    // intent_kind
            ps.setLong(idx++, 0L);                                 // logical_time
            ps.setLong(idx++, 0L);                                 // node_id
            ps.setBytes(idx++, payload);                           // payload
            ps.setBytes(idx, chainHashZero);                       // chain_hash (32 bytes zero)
        }

        private byte[] buildPayload(int seq, long ingestMicros) {
            String json = "{\"type\":\"" + EVENT_TYPE + "\""
                + ",\"value\":" + (seq % 100)
                + ",\"unit\":\"°C\""
                + ",\"ts\":" + ingestMicros
                + ",\"source\":\"spike-d1\""
                + ",\"seq\":" + seq
                + ",\"detail\":\"synthetic D1 WAL starvation validation event\"}";
            return json.getBytes(StandardCharsets.UTF_8);
        }

        // -----------------------------------------------------------------------------------
        // Reader: dispatches to bounded or continuous strategy
        // -----------------------------------------------------------------------------------

        void runReader() {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                applyPragmas(conn, config.journalSizeLimit());
                startLatch.await();
                if (config.boundedReader()) {
                    runBoundedReader(conn);
                } else {
                    runContinuousReader(conn);
                }
            } catch (SQLException e) {
                System.err.println("[" + config.name() + "] reader SQLException: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Run 1 strategy: open a single read transaction at startup, never commit it. SQLite
         * WAL semantics freeze the read snapshot at the first SELECT, so subsequent SELECTs
         * against {@code global_position > lastSeen} return zero rows even as the writer
         * continues to commit. The held snapshot is the anchor that prevents
         * wal_autocheckpoint from making progress — the pathology this run reproduces.
         */
        private void runContinuousReader(Connection conn) {
            long lastSeen = 0;
            try (Statement txnStmt = conn.createStatement();
                 PreparedStatement select = conn.prepareStatement(SELECT_READER_SQL)) {

                txnStmt.execute("BEGIN");
                while (running) {
                    select.setLong(1, lastSeen);
                    int rowsThisIteration = 0;
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next() && running) {
                            lastSeen = rs.getLong(1);
                            lastReaderPosition.set(lastSeen);
                            rowsThisIteration++;
                            LockSupport.parkNanos(READER_ROW_DELAY_NANOS);
                        }
                    }
                    if (rowsThisIteration == 0) {
                        // Snapshot is frozen; nothing new is visible. Idle-poll keeps the
                        // transaction alive without spinning.
                        LockSupport.parkNanos(READER_IDLE_POLL_NANOS);
                    }
                }
                // Intentionally no COMMIT — close-on-exit aborts the read transaction.
            } catch (SQLException e) {
                System.err.println("[" + config.name() + "] continuous reader SQLException: "
                    + e.getMessage());
            }
        }

        /**
         * Runs 2 and 3 strategy: open a transaction, read up to {@link #READER_CHUNK_SIZE}
         * rows, commit, then back off and start a fresh transaction. Each fresh
         * {@code BEGIN} establishes a new snapshot, releasing the previous snapshot anchor
         * and allowing wal_autocheckpoint to truncate the WAL past the released position.
         * This models the {@code ProjectionAdvancer} bounded-window contract.
         */
        private void runBoundedReader(Connection conn) {
            long lastSeen = 0;
            try (Statement txnStmt = conn.createStatement();
                 PreparedStatement select = conn.prepareStatement(SELECT_READER_SQL)) {

                while (running) {
                    txnStmt.execute("BEGIN");
                    int rowsThisChunk = 0;
                    select.setLong(1, lastSeen);
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next() && running) {
                            lastSeen = rs.getLong(1);
                            lastReaderPosition.set(lastSeen);
                            rowsThisChunk++;
                            LockSupport.parkNanos(READER_ROW_DELAY_NANOS);
                        }
                    }
                    txnStmt.execute("COMMIT");
                    readerChunksCompleted.incrementAndGet();

                    if (rowsThisChunk < READER_CHUNK_SIZE) {
                        LockSupport.parkNanos(READER_EMPTY_BACKOFF_NANOS);
                    }
                }
            } catch (SQLException e) {
                System.err.println("[" + config.name() + "] bounded reader SQLException: "
                    + e.getMessage());
            }
        }

        // -----------------------------------------------------------------------------------
        // Active checkpointer (Run 2 only)
        // -----------------------------------------------------------------------------------

        /**
         * Run 2 only. Opens a dedicated connection (rather than sharing the writer's
         * connection) and issues {@code PRAGMA wal_checkpoint(PASSIVE)} every 30 seconds.
         * PASSIVE serializes with writers but does not abort readers, matching the M3
         * AMD-38 proposed policy. Separate connection avoids cross-thread connection-state
         * hazards — the brief specifies "on the writer connection" but SQLite checkpoints
         * synchronize at the database level regardless of which connection issues the PRAGMA.
         */
        void runCheckpointer() {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                applyPragmas(conn, config.journalSizeLimit());
                startLatch.await();

                while (running) {
                    LockSupport.parkNanos(ACTIVE_CHECKPOINT_INTERVAL_NANOS);
                    if (!running) {
                        break;
                    }
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("PRAGMA wal_checkpoint(PASSIVE)");
                    }
                    activeCheckpointsTriggered.incrementAndGet();
                }
            } catch (SQLException e) {
                System.err.println("[" + config.name() + "] checkpointer SQLException: "
                    + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // -----------------------------------------------------------------------------------
        // WAL monitor — polls .wal file size every 100ms
        // -----------------------------------------------------------------------------------

        void runWalMonitor() {
            File walFile = new File(config.dbPath() + "-wal");
            // Time-series origin: monitor start. The monitor starts ~100 ms before the
            // writer/reader (settle delay), so early samples may show baseline 0 bytes.
            long monitorStartNanos = System.nanoTime();
            while (running) {
                long size = walFile.exists() ? walFile.length() : 0L;
                long elapsedMs = (System.nanoTime() - monitorStartNanos) / 1_000_000L;

                walSamples.add(new long[] { elapsedMs, size });
                walSampleCount.incrementAndGet();
                walPeakBytes.accumulateAndGet(size, Math::max);
                walFinalBytes.set(size);

                LockSupport.parkNanos(WAL_POLL_INTERVAL_NANOS);
            }
            // One final sample after running=false
            long size = walFile.exists() ? walFile.length() : 0L;
            walFinalBytes.set(size);
            walPeakBytes.accumulateAndGet(size, Math::max);
        }
    }

    // -----------------------------------------------------------------------------------
    // Output formatting
    // -----------------------------------------------------------------------------------

    private static void printSummary(RunResult r1, RunResult r2, RunResult r3) {
        System.out.println("=== D1 WAL Starvation Spike Results ===");
        System.out.println();
        // Display knobs are inferred from RunResult flags: r1 is the only run with a
        // continuous reader (boundedReader=false); only r2 had an active checkpoint thread.
        printRunSection(r1);
        System.out.println();
        printRunSection(r2);
        System.out.println();
        printRunSection(r3);
        System.out.println();
    }

    private static void printRunSection(RunResult r) {
        double walPeakMb = r.walPeakBytes() / (1024.0 * 1024.0);
        double walFinalMb = r.walFinalBytes() / (1024.0 * 1024.0);
        long readerLag = Math.max(0L, r.eventsWritten() - r.readerPositionAtEnd());
        boolean continuousReader = !r.boundedReader();
        boolean hadActiveCheckpoint = r.activeCheckpointEnabled();
        System.out.printf(Locale.US, "%s: %s%n", r.name(), r.description());
        System.out.printf(Locale.US, "  Duration: %.1f seconds%n", r.durationSec());
        System.out.printf(Locale.US, "  Events written: %d%n", r.eventsWritten());
        System.out.printf(Locale.US, "  Writer rate: %.2f events/sec%n", r.writerRate());
        if (continuousReader) {
            System.out.printf(Locale.US,
                "  Reader position at end: %d (lagged by %d events)%n",
                r.readerPositionAtEnd(), readerLag);
        } else {
            String catchupNote = (readerLag == 0) ? " (caught up)" : (" (lagged by " + readerLag + ")");
            System.out.printf(Locale.US, "  Reader position at end: %d%s%n",
                r.readerPositionAtEnd(), catchupNote);
            System.out.printf(Locale.US, "  Reader chunks processed: %d%n", r.readerChunks());
        }
        if (hadActiveCheckpoint) {
            System.out.printf(Locale.US, "  Active checkpoints triggered: %d%n",
                r.activeCheckpointsTriggered());
        }
        System.out.printf(Locale.US, "  WAL peak size: %.2f MB%n", walPeakMb);
        System.out.printf(Locale.US, "  WAL final size: %.2f MB%n", walFinalMb);
        System.out.printf(Locale.US, "  WAL samples (every 100ms): %d%n", r.walSampleCount());
        System.out.printf(Locale.US, "  RESULT: %s (threshold: %s)%n",
            r.pass() ? "PASS" : "FAIL", r.thresholdDescription());
    }

    private static void printGateDecisions(RunResult r1, RunResult r2, RunResult r3) {
        boolean run1Reproduces = r1.pass();              // Run 1 PASS == pathology reproduced
        boolean run2Prevents   = r2.pass();              // Run 2 PASS == bounded+64MB+ckpt works
        boolean run3Prevents   = r3.pass();              // Run 3 PASS == bounded@6MB works alone
        boolean run3ExceedsLimit = r3.walPeakBytes() >= RUN3_PASS_THRESHOLD_BYTES;

        String amd38Recommendation;
        if (!run1Reproduces) {
            amd38Recommendation = "WITHDRAW (pathology did not reproduce at 5 events/s)";
        } else if (run2Prevents || run3Prevents) {
            amd38Recommendation = "APPLY (pathology reproduced and bounded-window reader prevents it)";
        } else {
            amd38Recommendation = "REVISE (pathology reproduces; neither mitigation prevented it)";
        }

        String amd39Recommendation;
        if (run3ExceedsLimit) {
            amd39Recommendation = "APPLY (Run 3 WAL exceeded 6 MB — 64 MB limit is load-bearing)";
        } else {
            amd39Recommendation = "WITHDRAW (Run 3 stayed under 6 MB — bounded reader alone is sufficient)";
        }

        System.out.println("=== AMD Gate Decisions ===");
        System.out.println("AMD-38 (Checkpoint Policy: 200 events / 2 s):");
        System.out.printf(Locale.US, "  Run 1 confirms pathology: %s%n", run1Reproduces ? "YES" : "NO");
        System.out.printf(Locale.US, "  Bounded-window reader prevents it (Run 2): %s%n",
            run2Prevents ? "YES" : "NO");
        System.out.printf(Locale.US, "  Bounded-window reader prevents it (Run 3): %s%n",
            run3Prevents ? "YES" : "NO");
        System.out.printf(Locale.US, "  -> RECOMMENDATION: %s%n", amd38Recommendation);
        System.out.println();
        System.out.println("AMD-39 (Journal Size Limit: 6 MB -> 64 MB):");
        System.out.printf(Locale.US, "  Run 3 WAL exceeds 6 MB: %s%n", run3ExceedsLimit ? "YES" : "NO");
        System.out.printf(Locale.US, "  -> RECOMMENDATION: %s%n", amd39Recommendation);
        System.out.println();
    }

    private static void printTimeSeries(RunResult r) {
        System.out.printf(Locale.US, "=== WAL Size Time Series (%s) ===%n", r.name());
        System.out.println("time_ms\twal_bytes");
        for (long[] sample : r.walSamples()) {
            System.out.printf(Locale.US, "%d\t%d%n", sample[0], sample[1]);
        }
        System.out.println();
    }

    private static void deleteIfExists(String path) {
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            System.err.println("Warning: could not delete " + path);
        }
    }

    // -----------------------------------------------------------------------------------
    // Records: scenario configuration + result
    // -----------------------------------------------------------------------------------

    private record RunConfig(
        String name,
        String description,
        String dbPath,
        long journalSizeLimit,
        boolean boundedReader,
        boolean activeCheckpoint
    ) {}

    private record RunResult(
        String name,
        String description,
        boolean boundedReader,
        boolean activeCheckpointEnabled,
        double durationSec,
        int eventsWritten,
        double writerRate,
        long readerPositionAtEnd,
        int readerChunks,
        int activeCheckpointsTriggered,
        long walPeakBytes,
        long walFinalBytes,
        int walSampleCount,
        List<long[]> walSamples,
        boolean pass,
        String thresholdDescription
    ) {}
}
