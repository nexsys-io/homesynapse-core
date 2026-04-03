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
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * V3: Platform Thread Executor Pattern Validation — spike plan §5 V3.
 *
 * <p>Validates the AMD-27/LTD-03 executor pattern: all sqlite-jdbc calls are
 * submitted to dedicated platform thread pools via
 * {@link CompletableFuture#supplyAsync}, so carrier pinning from sqlite-jdbc's
 * {@code synchronized native} methods is confined to platform threads and never
 * affects virtual thread scheduling.
 *
 * <p>Two sub-tests:
 * <ol>
 *   <li><b>V3-Throughput:</b> C1-equivalent insert throughput test routed through
 *       the executor. Measures per-submission overhead and total throughput.
 *       Success: overhead &lt; 1ms p99, throughput &ge; 10,000 events/sec
 *       absolute floor. C1 baseline (50,964 events/sec) reported for reference.
 *   <li><b>V3-Concurrency:</b> C4-equivalent concurrent VT test where all DB
 *       operations go through the executor. Run with JFR to confirm zero
 *       {@code jdk.VirtualThreadPinned} events from sqlite-jdbc — all pinning
 *       confined to the platform thread pools.
 *       Success: zero VT pinning from sqlite-jdbc, zero errors.
 * </ol>
 *
 * <p>Usage:
 * <pre>
 *   # Throughput + concurrency (both sub-tests):
 *   java -cp ... com.homesynapse.spike.wal.V3ExecutorPatternTest &lt;db-path&gt;
 *
 *   # With JFR pinning detection (use custom settings file):
 *   java -XX:StartFlightRecording=filename=v3-pinning.jfr,settings=vt-pinning.jfc \
 *        -cp ... com.homesynapse.spike.wal.V3ExecutorPatternTest &lt;db-path&gt;
 * </pre>
 *
 * @see C1AppendThroughputTest baseline comparison
 * @see C4VirtualThreadTest direct VT comparison
 */
public final class V3ExecutorPatternTest {

    // --- Throughput sub-test parameters (C1-equivalent) ---
    private static final int THROUGHPUT_EVENT_COUNT = 100_000;
    private static final int THROUGHPUT_BATCH_SIZE = 1_000;
    private static final int ENTITY_COUNT = 10;
    private static final double C1_BASELINE_EVENTS_PER_SEC = 50_964.0;
    private static final double THROUGHPUT_ABSOLUTE_FLOOR = 10_000.0;

    // --- Concurrency sub-test parameters (C4-equivalent) ---
    private static final int CONCURRENCY_DURATION_SEC = 60;
    private static final int READER_VT_COUNT = 20;
    private static final int WRITE_RATE = 100;
    private static final long WRITE_INTERVAL_NANOS = 10_000_000L; // 10ms = 100/sec
    private static final long JOIN_TIMEOUT_MS = 10_000L;

    // --- Executor pool sizes (AMD-27/LTD-03) ---
    private static final int WRITE_POOL_SIZE = 1;
    private static final int READ_POOL_SIZE = 2;

    private static final String[] EVENT_TYPES = {
        "state_reported", "state_changed", "command_executed", "error_occurred"
    };

    private V3ExecutorPatternTest() {
        // runnable class — use main()
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println(
                    "Usage: V3ExecutorPatternTest <db-file-path>");
            System.exit(1);
        }
        String dbPath = args[0];

        System.out.println("=== V3: Platform Thread Executor Pattern Validation ===");
        System.out.printf("  Write pool: %d platform thread(s)%n", WRITE_POOL_SIZE);
        System.out.printf("  Read pool:  %d platform thread(s)%n", READ_POOL_SIZE);
        System.out.println();

        // Create the executor pools (platform threads — NOT virtual threads).
        // These are the pools that absorb sqlite-jdbc's synchronized native pinning.
        ExecutorService writeExecutor = Executors.newFixedThreadPool(WRITE_POOL_SIZE,
                r -> {
                    Thread t = new Thread(r, "hs-db-writer-0");
                    t.setDaemon(true);
                    return t;
                });
        AtomicInteger readThreadCounter = new AtomicInteger(0);
        ExecutorService readExecutor = Executors.newFixedThreadPool(READ_POOL_SIZE,
                r -> {
                    Thread t = new Thread(r,
                            "hs-db-reader-" + readThreadCounter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });

        try {
            runThroughputTest(dbPath, writeExecutor);
            System.out.println();
            runConcurrencyTest(dbPath, writeExecutor, readExecutor);
        } finally {
            writeExecutor.shutdownNow();
            readExecutor.shutdownNow();
        }
    }

    // ========================================================================
    // V3-Throughput: C1-equivalent routed through write executor
    // ========================================================================

    private static void runThroughputTest(String dbPath,
            ExecutorService writeExecutor) throws Exception {

        String throughputDb = dbPath + "-throughput";
        deleteIfExists(throughputDb);
        deleteIfExists(throughputDb + "-wal");
        deleteIfExists(throughputDb + "-shm");

        String jdbcUrl = "jdbc:sqlite:" + throughputDb;

        // Setup schema on the write executor (all DB access goes through executors)
        CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                PragmaConfig.apply(conn);
                PragmaConfig.verify(conn);
                SpikeSchema.create(conn);
            } catch (SQLException e) {
                throw new RuntimeException("Schema setup failed", e);
            }
        }, writeExecutor).join();

        System.out.println("--- V3-Throughput: C1-equivalent through executor ---");
        System.out.printf("  Events: %,d  |  Batch: %,d  |  Entities: %d%n",
                THROUGHPUT_EVENT_COUNT, THROUGHPUT_BATCH_SIZE, ENTITY_COUNT);
        System.out.println();

        // Pre-generate entity refs
        byte[][] entityRefs = new byte[ENTITY_COUNT][];
        for (int i = 0; i < ENTITY_COUNT; i++) {
            entityRefs[i] = SpikeUlidGenerator.generate();
        }

        LatencyStats submissionStats = new LatencyStats(THROUGHPUT_EVENT_COUNT);

        long startNanos = System.nanoTime();

        // Open a persistent connection on the write executor.
        // In production, the write executor owns a single Connection.
        Connection writeConn = CompletableFuture.supplyAsync(() -> {
            try {
                Connection conn = DriverManager.getConnection(jdbcUrl);
                PragmaConfig.apply(conn);
                conn.setAutoCommit(false);
                return conn;
            } catch (SQLException e) {
                throw new RuntimeException("Write connection failed", e);
            }
        }, writeExecutor).join();

        // Prepare the statement once on the write executor — matches C1's
        // pattern. This isolates executor routing overhead from PreparedStatement
        // allocation cost, giving a fair throughput comparison to C1.
        String insertSql = "INSERT INTO events "
                + "(event_id, entity_ref, entity_sequence, event_type,"
                + " event_time, ingest_time, payload)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";

        PreparedStatement writePs = CompletableFuture.supplyAsync(() -> {
            try {
                return writeConn.prepareStatement(insertSql);
            } catch (SQLException e) {
                throw new RuntimeException("PrepareStatement failed", e);
            }
        }, writeExecutor).join();

        try {
            int[] entitySequences = new int[ENTITY_COUNT];

            for (int i = 0; i < THROUGHPUT_EVENT_COUNT; i++) {
                int entityIdx = i % ENTITY_COUNT;
                entitySequences[entityIdx]++;
                String eventType = EVENT_TYPES[i % EVENT_TYPES.length];
                String now = Instant.now().toString();
                byte[] eventId = SpikeUlidGenerator.generate();
                byte[] entityRef = entityRefs[entityIdx];
                int seq = entitySequences[entityIdx];
                byte[] payload = buildPayload(eventType, seq);
                boolean shouldCommit = (i + 1) % THROUGHPUT_BATCH_SIZE == 0;

                long submitStart = System.nanoTime();

                // Production pattern: VT submits work to platform thread
                // executor and awaits the result. The executor thread owns
                // the Connection and its PreparedStatement.
                CompletableFuture.runAsync(() -> {
                    try {
                        writePs.setBytes(1, eventId);
                        writePs.setBytes(2, entityRef);
                        writePs.setInt(3, seq);
                        writePs.setString(4, eventType);
                        writePs.setString(5, now);
                        writePs.setString(6, now);
                        writePs.setBytes(7, payload);
                        writePs.executeUpdate();
                        if (shouldCommit) {
                            writeConn.commit();
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException("Insert failed", e);
                    }
                }, writeExecutor).join();

                long submitEnd = System.nanoTime();
                submissionStats.record(submitEnd - submitStart);
            }

            // Final commit
            CompletableFuture.runAsync(() -> {
                try {
                    writeConn.commit();
                } catch (SQLException e) {
                    throw new RuntimeException("Final commit failed", e);
                }
            }, writeExecutor).join();

        } finally {
            CompletableFuture.runAsync(() -> {
                try {
                    writePs.close();
                    writeConn.close();
                } catch (SQLException e) {
                    System.err.println("Warning: cleanup failed: "
                            + e.getMessage());
                }
            }, writeExecutor).join();
        }

        long totalNanos = System.nanoTime() - startNanos;
        printThroughputResults(submissionStats, totalNanos, throughputDb);
    }

    private static void doInsert(Connection conn, byte[] eventId,
            byte[] entityRef, int seq, String eventType, String now,
            byte[] payload) throws SQLException {
        String sql = "INSERT INTO events "
                + "(event_id, entity_ref, entity_sequence, event_type,"
                + " event_time, ingest_time, payload)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, eventId);
            ps.setBytes(2, entityRef);
            ps.setInt(3, seq);
            ps.setString(4, eventType);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.setBytes(7, payload);
            ps.executeUpdate();
        }
    }

    private static void printThroughputResults(LatencyStats stats,
            long totalNanos, String dbPath) {
        double totalSeconds = totalNanos / 1_000_000_000.0;
        double eventsPerSec = THROUGHPUT_EVENT_COUNT / totalSeconds;
        double ratio = eventsPerSec / C1_BASELINE_EVENTS_PER_SEC;

        File dbFile = new File(dbPath);
        File walFile = new File(dbPath + "-wal");
        double dbSizeMb = dbFile.length() / (1024.0 * 1024.0);
        double walSizeMb = walFile.exists()
                ? walFile.length() / (1024.0 * 1024.0) : 0.0;

        double overheadP99Ms = stats.p99() / 1_000_000.0;
        boolean overheadPass = overheadP99Ms < 1.0;
        boolean throughputPass = eventsPerSec >= THROUGHPUT_ABSOLUTE_FLOOR;
        boolean pass = overheadPass && throughputPass;

        System.out.println("V3-Throughput Results:");
        System.out.printf(Locale.US, "  Events inserted: %,d%n",
                THROUGHPUT_EVENT_COUNT);
        System.out.printf(Locale.US, "  Total time: %.3f seconds%n",
                totalSeconds);
        System.out.printf(Locale.US, "  Throughput: %,.0f events/sec%n",
                eventsPerSec);
        System.out.printf(Locale.US,
                "  C1 baseline: %,.0f events/sec  |  Ratio: %.1f%% (informational)%n",
                C1_BASELINE_EVENTS_PER_SEC, ratio * 100);
        System.out.printf(Locale.US,
                "  Per-submission overhead (ns): p50=%,d  p95=%,d  p99=%,d%n",
                stats.p50(), stats.p95(), stats.p99());
        System.out.printf(Locale.US,
                "  Per-submission overhead (ms): p50=%.3f  p95=%.3f  p99=%.3f%n",
                stats.p50() / 1_000_000.0,
                stats.p95() / 1_000_000.0,
                stats.p99() / 1_000_000.0);
        System.out.printf(Locale.US, "  DB file size: %.1f MB%n", dbSizeMb);
        System.out.printf(Locale.US, "  WAL file size: %.1f MB%n", walSizeMb);
        System.out.println();
        System.out.printf(
                "  Overhead check: %s (p99 = %.3f ms, threshold < 1.0 ms)%n",
                overheadPass ? "PASS" : "FAIL", overheadP99Ms);
        System.out.printf(Locale.US,
                "  Throughput check: %s (%,.0f events/sec, floor >= %,.0f)%n",
                throughputPass ? "PASS" : "FAIL",
                eventsPerSec, THROUGHPUT_ABSOLUTE_FLOOR);
        System.out.printf("  V3-Throughput RESULT: %s%n",
                pass ? "PASS" : "FAIL");
    }

    // ========================================================================
    // V3-Concurrency: C4-equivalent with all DB calls through executors
    // ========================================================================

    private static void runConcurrencyTest(String dbPath,
            ExecutorService writeExecutor, ExecutorService readExecutor)
            throws Exception {

        String concurrencyDb = dbPath + "-concurrency";
        deleteIfExists(concurrencyDb);
        deleteIfExists(concurrencyDb + "-wal");
        deleteIfExists(concurrencyDb + "-shm");

        String jdbcUrl = "jdbc:sqlite:" + concurrencyDb;

        // Pre-generate entity refs
        byte[][] entityRefs = new byte[ENTITY_COUNT][];
        for (int i = 0; i < ENTITY_COUNT; i++) {
            entityRefs[i] = SpikeUlidGenerator.generate();
        }

        // Setup schema via write executor
        CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                PragmaConfig.apply(conn);
                PragmaConfig.verify(conn);
                SpikeSchema.create(conn);
            } catch (SQLException e) {
                throw new RuntimeException("Schema setup failed", e);
            }
        }, writeExecutor).join();

        System.out.println("--- V3-Concurrency: C4-equivalent through executor ---");
        System.out.printf("  Writer VTs: 1  |  Reader VTs: %d  |  Duration: %ds%n",
                READER_VT_COUNT, CONCURRENCY_DURATION_SEC);
        System.out.printf("  Write pool: %d PT  |  Read pool: %d PT%n",
                WRITE_POOL_SIZE, READ_POOL_SIZE);
        System.out.println();

        // Shared counters
        AtomicLong totalWrites = new AtomicLong(0);
        AtomicLong totalReads = new AtomicLong(0);
        AtomicInteger readErrors = new AtomicInteger(0);
        AtomicInteger writeErrors = new AtomicInteger(0);
        AtomicInteger exceptions = new AtomicInteger(0);

        AtomicBoolean running = new AtomicBoolean(true);

        // Open persistent connections on the executor threads
        Connection writeConn = CompletableFuture.supplyAsync(() -> {
            try {
                Connection conn = DriverManager.getConnection(jdbcUrl);
                PragmaConfig.apply(conn);
                conn.setAutoCommit(false);
                return conn;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, writeExecutor).join();

        // Read connections — one per read pool thread, managed by the executor
        // In production, read connections are pooled. For the spike, open two
        // connections (matching READ_POOL_SIZE) and let the executor route.
        Connection[] readConns = new Connection[READ_POOL_SIZE];
        for (int i = 0; i < READ_POOL_SIZE; i++) {
            readConns[i] = CompletableFuture.supplyAsync(() -> {
                try {
                    Connection conn = DriverManager.getConnection(jdbcUrl);
                    PragmaConfig.apply(conn);
                    return conn;
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }, readExecutor).join();
        }
        AtomicInteger readConnRoundRobin = new AtomicInteger(0);

        // --- 1 writer virtual thread ---
        Thread writerVt = Thread.ofVirtual().name("vt-v3-writer").start(() -> {
            int insertCount = 0;
            int[] entitySequences = new int[ENTITY_COUNT];

            while (running.get()) {
                int entityIdx = insertCount % ENTITY_COUNT;
                entitySequences[entityIdx]++;
                String eventType = EVENT_TYPES[insertCount % EVENT_TYPES.length];
                String now = Instant.now().toString();
                byte[] eventId = SpikeUlidGenerator.generate();
                byte[] entityRef = entityRefs[entityIdx];
                int seq = entitySequences[entityIdx];
                byte[] payload = buildPayload(eventType, seq);
                boolean shouldCommit = (insertCount + 1) % WRITE_RATE == 0;

                try {
                    // VT submits to platform thread write pool
                    CompletableFuture.runAsync(() -> {
                        try {
                            doInsert(writeConn, eventId, entityRef, seq,
                                    eventType, now, payload);
                            if (shouldCommit) {
                                writeConn.commit();
                            }
                        } catch (SQLException e) {
                            if (isBusyError(e)) {
                                writeErrors.incrementAndGet();
                            } else {
                                exceptions.incrementAndGet();
                                System.err.println("vt-v3-writer executor error: "
                                        + e.getMessage());
                            }
                        }
                    }, writeExecutor).join();

                    totalWrites.incrementAndGet();
                    insertCount++;
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                    System.err.println("vt-v3-writer submission error: "
                            + e.getMessage());
                }

                LockSupport.parkNanos(WRITE_INTERVAL_NANOS);
            }

            // Final commit
            try {
                CompletableFuture.runAsync(() -> {
                    try {
                        writeConn.commit();
                    } catch (SQLException e) {
                        // Best-effort on shutdown
                    }
                }, writeExecutor).join();
            } catch (Exception ignored) {
                // Executor may be shut down
            }
        });

        // --- 20 reader virtual threads ---
        Thread[] readerVts = new Thread[READER_VT_COUNT];
        for (int r = 0; r < READER_VT_COUNT; r++) {
            int readerId = r;
            readerVts[r] = Thread.ofVirtual()
                    .name("vt-v3-reader-" + r)
                    .start(() -> {
                int queryIndex = 0;

                while (running.get()) {
                    // Round-robin across read connections
                    int connIdx = readConnRoundRobin.getAndIncrement()
                            % READ_POOL_SIZE;
                    Connection readConn = readConns[connIdx];
                    byte[] targetEntity = entityRefs[
                            (readerId + queryIndex) % ENTITY_COUNT];

                    try {
                        // VT submits to platform thread read pool
                        CompletableFuture.runAsync(() -> {
                            try (PreparedStatement ps = readConn.prepareStatement(
                                    "SELECT * FROM events WHERE entity_ref = ?"
                                            + " ORDER BY entity_sequence")) {
                                ps.setBytes(1, targetEntity);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        // consume results
                                    }
                                }
                            } catch (SQLException e) {
                                if (isBusyError(e)) {
                                    readErrors.incrementAndGet();
                                } else {
                                    exceptions.incrementAndGet();
                                    System.err.println("vt-v3-reader-" + readerId
                                            + " executor error: " + e.getMessage());
                                }
                            }
                        }, readExecutor).join();

                        totalReads.incrementAndGet();
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                    }
                    queryIndex++;
                    LockSupport.parkNanos(1_000_000L); // 1ms between queries
                }
            });
        }

        // Run for specified duration
        Thread.sleep(CONCURRENCY_DURATION_SEC * 1000L);
        running.set(false);

        // Join with deadlock detection
        int deadlocks = 0;
        writerVt.join(JOIN_TIMEOUT_MS);
        if (writerVt.isAlive()) {
            deadlocks++;
        }
        for (Thread reader : readerVts) {
            reader.join(JOIN_TIMEOUT_MS);
            if (reader.isAlive()) {
                deadlocks++;
            }
        }

        // Close connections via their respective executors
        try {
            CompletableFuture.runAsync(() -> {
                try { writeConn.close(); } catch (SQLException ignored) { }
            }, writeExecutor).join();
        } catch (Exception ignored) { }
        for (Connection readConn : readConns) {
            try {
                CompletableFuture.runAsync(() -> {
                    try { readConn.close(); } catch (SQLException ignored) { }
                }, readExecutor).join();
            } catch (Exception ignored) { }
        }

        // --- Results ---
        boolean pass = readErrors.get() == 0
                && writeErrors.get() == 0
                && deadlocks == 0
                && exceptions.get() == 0;

        System.out.println("V3-Concurrency Results:");
        System.out.printf(Locale.US, "  Duration: %d seconds%n",
                CONCURRENCY_DURATION_SEC);
        System.out.printf(Locale.US, "  Writer: %,d events written%n",
                totalWrites.get());
        System.out.printf(Locale.US,
                "  Readers: %,d total queries across %d VTs%n",
                totalReads.get(), READER_VT_COUNT);
        System.out.printf(Locale.US, "  SQLITE_BUSY errors (read): %d%n",
                readErrors.get());
        System.out.printf(Locale.US, "  SQLITE_BUSY errors (write): %d%n",
                writeErrors.get());
        System.out.printf(Locale.US, "  Exceptions: %d%n", exceptions.get());
        System.out.printf(Locale.US, "  Deadlocks: %d%n", deadlocks);
        System.out.println();
        System.out.println("  JFR pinning analysis: check the JFR recording"
                + " for jdk.VirtualThreadPinned events.");
        System.out.println("  Expected: zero VT pinning events (all"
                + " sqlite-jdbc ops on platform threads).");
        System.out.printf("  V3-Concurrency RESULT: %s "
                        + "(threshold: zero errors, zero deadlocks)%n",
                pass ? "PASS" : "FAIL");
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    private static byte[] buildPayload(String eventType, int sequence) {
        String json = "{\"type\":\"" + eventType + "\""
                + ",\"value\":" + (sequence % 100)
                + ",\"unit\":\"%\""
                + ",\"ts\":\"" + Instant.now() + "\""
                + ",\"source\":\"spike-gen\""
                + ",\"seq\":" + sequence
                + ",\"detail\":\"synthetic test event for wal validation spike\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isBusyError(SQLException e) {
        return e.getErrorCode() == 5; // SQLITE_BUSY
    }

    private static void deleteIfExists(String path) {
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            System.err.println("Warning: could not delete " + path);
        }
    }
}
