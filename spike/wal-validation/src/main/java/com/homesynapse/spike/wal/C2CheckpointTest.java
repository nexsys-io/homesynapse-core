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
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * C2: WAL Checkpoint Non-Blocking — spike plan §4 C2.
 *
 * <p>During sustained writes (100 events/sec for 30 seconds), triggers a manual WAL
 * checkpoint ({@code PRAGMA wal_checkpoint(PASSIVE)}) and verifies that concurrent
 * readers are not blocked. Measures checkpoint duration, reader latency during
 * checkpoint, and any SQLITE_BUSY errors on read connections.
 *
 * <p>Usage: {@code java -cp ... com.homesynapse.spike.wal.C2CheckpointTest <db-path>}
 */
public final class C2CheckpointTest {

    private static final int WRITE_RATE = 100;
    private static final int WRITE_DURATION_SEC = 30;
    private static final int TOTAL_EVENTS = WRITE_RATE * WRITE_DURATION_SEC;
    private static final int ENTITY_COUNT = 10;
    private static final int READER_COUNT = 5;
    private static final int CHECKPOINT_DELAY_SEC = 10;
    private static final long WRITE_INTERVAL_NANOS = 10_000_000L; // 10ms = 100/sec

    private static final String[] EVENT_TYPES = {
        "state_reported", "state_changed", "command_executed", "error_occurred"
    };

    private static volatile boolean running = true;
    private static volatile boolean checkpointActive = false;

    private C2CheckpointTest() {
        // runnable class — use main()
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: C2CheckpointTest <db-file-path>");
            System.exit(1);
        }
        String dbPath = args[0];

        deleteIfExists(dbPath);
        deleteIfExists(dbPath + "-wal");
        deleteIfExists(dbPath + "-shm");

        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        // Pre-generate entity refs (shared across writer and readers)
        byte[][] entityRefs = new byte[ENTITY_COUNT][];
        for (int i = 0; i < ENTITY_COUNT; i++) {
            entityRefs[i] = SpikeUlidGenerator.generate();
        }

        // Setup: schema on initial connection
        try (Connection setupConn = DriverManager.getConnection(jdbcUrl)) {
            PragmaConfig.apply(setupConn);
            PragmaConfig.verify(setupConn);
            SpikeSchema.create(setupConn);
        }

        // Shared counters
        AtomicInteger totalReaderQueries = new AtomicInteger(0);
        AtomicInteger readerBusyErrors = new AtomicInteger(0);
        ConcurrentLinkedQueue<Long> checkpointLatencies = new ConcurrentLinkedQueue<>();
        AtomicInteger eventsWritten = new AtomicInteger(0);
        AtomicLong checkpointDurationMs = new AtomicLong(0);

        System.out.println("Starting C2: WAL Checkpoint Non-Blocking test...");
        System.out.printf("  Write rate: %d/sec  |  Duration: %ds  |  Checkpoint at: %ds%n",
                WRITE_RATE, WRITE_DURATION_SEC, CHECKPOINT_DELAY_SEC);
        System.out.println();

        long testStart = System.nanoTime();

        // --- Writer thread: 100 inserts/sec for 30 seconds ---
        Thread writer = new Thread(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                PragmaConfig.apply(conn);
                conn.setAutoCommit(false);
                int[] entitySequences = new int[ENTITY_COUNT];

                String sql = "INSERT INTO events "
                        + "(event_id, entity_ref, entity_sequence, event_type,"
                        + " event_time, ingest_time, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)";

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < TOTAL_EVENTS && running; i++) {
                        int entityIdx = i % ENTITY_COUNT;
                        entitySequences[entityIdx]++;
                        String eventType = EVENT_TYPES[i % EVENT_TYPES.length];
                        String now = Instant.now().toString();
                        byte[] payload = buildPayload(eventType,
                                entitySequences[entityIdx]);

                        ps.setBytes(1, SpikeUlidGenerator.generate());
                        ps.setBytes(2, entityRefs[entityIdx]);
                        ps.setInt(3, entitySequences[entityIdx]);
                        ps.setString(4, eventType);
                        ps.setString(5, now);
                        ps.setString(6, now);
                        ps.setBytes(7, payload);
                        ps.executeUpdate();
                        eventsWritten.incrementAndGet();

                        // Commit every 1 second of writes
                        if ((i + 1) % WRITE_RATE == 0) {
                            conn.commit();
                        }

                        LockSupport.parkNanos(WRITE_INTERVAL_NANOS);
                    }
                    conn.commit(); // Final flush
                }
            } catch (SQLException e) {
                System.err.println("Writer error: " + e.getMessage());
            }
        }, "c2-writer");

        // --- 5 reader threads: concurrent queries ---
        Thread[] readers = new Thread[READER_COUNT];
        for (int r = 0; r < READER_COUNT; r++) {
            int readerId = r;
            readers[r] = new Thread(() -> {
                try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                    PragmaConfig.apply(conn);
                    int queryIndex = 0;

                    while (running) {
                        long queryStart = System.nanoTime();
                        try {
                            if (queryIndex % 2 == 0) {
                                try (Statement stmt = conn.createStatement();
                                     ResultSet rs = stmt.executeQuery(
                                             "SELECT COUNT(*) FROM events")) {
                                    rs.next();
                                }
                            } else {
                                try (PreparedStatement ps = conn.prepareStatement(
                                        "SELECT * FROM events WHERE entity_ref = ?"
                                                + " ORDER BY entity_sequence")) {
                                    ps.setBytes(1,
                                            entityRefs[readerId % ENTITY_COUNT]);
                                    try (ResultSet rs = ps.executeQuery()) {
                                        while (rs.next()) {
                                            // consume results
                                        }
                                    }
                                }
                            }
                            long queryEnd = System.nanoTime();
                            totalReaderQueries.incrementAndGet();

                            if (checkpointActive) {
                                checkpointLatencies.add(queryEnd - queryStart);
                            }
                        } catch (SQLException e) {
                            if (isBusyError(e)) {
                                readerBusyErrors.incrementAndGet();
                            } else {
                                System.err.println("Reader-" + readerId
                                        + " error: " + e.getMessage());
                            }
                        }
                        queryIndex++;
                        LockSupport.parkNanos(1_000_000L); // 1ms between queries
                    }
                } catch (SQLException e) {
                    System.err.println("Reader-" + readerId
                            + " connection error: " + e.getMessage());
                }
            }, "c2-reader-" + r);
        }

        // Launch all threads
        writer.start();
        for (Thread reader : readers) {
            reader.start();
        }

        // Wait 10 seconds, then trigger checkpoint on separate connection
        Thread.sleep(CHECKPOINT_DELAY_SEC * 1000L);

        checkpointActive = true;
        long cpStart = System.nanoTime();
        try (Connection cpConn = DriverManager.getConnection(jdbcUrl)) {
            PragmaConfig.apply(cpConn);
            try (Statement stmt = cpConn.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(PASSIVE)");
            }
        }
        long cpEnd = System.nanoTime();
        checkpointActive = false;
        checkpointDurationMs.set((cpEnd - cpStart) / 1_000_000);

        // Wait for writer to finish (remaining ~20 seconds)
        writer.join();
        running = false;
        for (Thread reader : readers) {
            reader.join(5_000);
        }

        long testEnd = System.nanoTime();
        double totalSeconds = (testEnd - testStart) / 1_000_000_000.0;

        // --- Aggregate checkpoint-period latency ---
        int cpSampleCount = checkpointLatencies.size();

        System.out.println("=== C2: WAL Checkpoint Non-Blocking ===");
        System.out.printf(Locale.US, "Write duration: %.1f seconds%n", totalSeconds);
        System.out.printf(Locale.US, "Events written: %,d%n", eventsWritten.get());
        System.out.printf(Locale.US, "Checkpoint duration: %d ms%n",
                checkpointDurationMs.get());
        System.out.printf(Locale.US, "Reader queries during checkpoint: %d%n",
                cpSampleCount);
        System.out.printf(Locale.US, "Reader SQLITE_BUSY errors: %d%n",
                readerBusyErrors.get());

        if (cpSampleCount > 0) {
            LatencyStats cpStats = new LatencyStats(cpSampleCount);
            for (Long lat : checkpointLatencies) {
                cpStats.record(lat);
            }
            System.out.printf(Locale.US,
                    "Reader latency during checkpoint:"
                            + " p50=%.2f p95=%.2f p99=%.2f ms%n",
                    cpStats.p50() / 1_000_000.0,
                    cpStats.p95() / 1_000_000.0,
                    cpStats.p99() / 1_000_000.0);
        } else {
            System.out.println("Reader latency during checkpoint:"
                    + " no queries completed during checkpoint window");
        }

        boolean pass = readerBusyErrors.get() == 0;
        System.out.printf("RESULT: %s (threshold: zero SQLITE_BUSY on readers"
                        + " during checkpoint)%n",
                pass ? "PASS" : "FAIL");
    }

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
