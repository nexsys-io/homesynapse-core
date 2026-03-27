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
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;

/**
 * C1: Append Throughput — spike plan §4 C1.
 *
 * <p>Inserts 100,000 events (~200 bytes each) into a WAL-mode SQLite database and
 * measures total time, throughput, and per-insert latency percentiles.
 *
 * <p>Usage: {@code java -cp ... com.homesynapse.spike.wal.C1AppendThroughputTest <db-path>}
 */
public final class C1AppendThroughputTest {

    private static final int EVENT_COUNT = 100_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int ENTITY_COUNT = 10;
    private static final int THROUGHPUT_THRESHOLD = 10_000;

    private static final String[] EVENT_TYPES = {
        "state_reported", "state_changed", "command_executed", "error_occurred"
    };

    private C1AppendThroughputTest() {
        // runnable class — use main()
    }

    public static void main(String[] args) throws SQLException {
        if (args.length < 1) {
            System.err.println("Usage: C1AppendThroughputTest <db-file-path>");
            System.exit(1);
        }
        String dbPath = args[0];

        // Delete pre-existing DB files for a clean run
        deleteIfExists(dbPath);
        deleteIfExists(dbPath + "-wal");
        deleteIfExists(dbPath + "-shm");

        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            PragmaConfig.apply(conn);
            PragmaConfig.verify(conn);
            SpikeSchema.create(conn);

            System.out.println();
            System.out.println("Starting C1: Append Throughput test...");
            System.out.printf("  Events: %,d  |  Batch commit: every %,d  |  Entities: %d%n",
                    EVENT_COUNT, BATCH_SIZE, ENTITY_COUNT);
            System.out.println();

            runInserts(conn, dbPath);
        }
    }

    private static void runInserts(Connection conn, String dbPath) throws SQLException {
        // Pre-generate 10 entity ULIDs
        byte[][] entityRefs = new byte[ENTITY_COUNT][];
        for (int i = 0; i < ENTITY_COUNT; i++) {
            entityRefs[i] = SpikeUlidGenerator.generate();
        }
        int[] entitySequences = new int[ENTITY_COUNT];

        LatencyStats stats = new LatencyStats(EVENT_COUNT);

        conn.setAutoCommit(false);

        String sql = "INSERT INTO events "
                + "(event_id, entity_ref, entity_sequence, event_type,"
                + " event_time, ingest_time, payload)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";

        long startNanos = System.nanoTime();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < EVENT_COUNT; i++) {
                int entityIdx = i % ENTITY_COUNT;
                entitySequences[entityIdx]++;

                String eventType = EVENT_TYPES[i % EVENT_TYPES.length];
                String now = Instant.now().toString();
                byte[] payload = buildPayload(eventType, entitySequences[entityIdx]);

                ps.setBytes(1, SpikeUlidGenerator.generate());
                ps.setBytes(2, entityRefs[entityIdx]);
                ps.setInt(3, entitySequences[entityIdx]);
                ps.setString(4, eventType);
                ps.setString(5, now);
                ps.setString(6, now);
                ps.setBytes(7, payload);

                long insertStart = System.nanoTime();
                ps.executeUpdate();
                long insertEnd = System.nanoTime();
                stats.record(insertEnd - insertStart);

                if ((i + 1) % BATCH_SIZE == 0) {
                    conn.commit();
                }
            }
            // Defensive final commit (no-op if last batch aligned)
            conn.commit();
        }

        long totalNanos = System.nanoTime() - startNanos;

        printResults(stats, totalNanos, dbPath);
    }

    /**
     * Builds a ~150-byte JSON payload for a single event.
     */
    private static byte[] buildPayload(String eventType, int sequence) {
        String json = "{\"type\":\"" + eventType + "\""
                + ",\"value\":" + (sequence % 100)
                + ",\"unit\":\"%\""
                + ",\"ts\":\"" + Instant.now() + "\""
                + ",\"source\":\"spike-gen\""
                + ",\"seq\":" + sequence
                + ",\"detail\":\"synthetic test event data for wal validation spike\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static void printResults(LatencyStats stats, long totalNanos, String dbPath) {
        double totalSeconds = totalNanos / 1_000_000_000.0;
        double eventsPerSec = EVENT_COUNT / totalSeconds;

        File dbFile = new File(dbPath);
        File walFile = new File(dbPath + "-wal");
        double dbSizeMb = dbFile.length() / (1024.0 * 1024.0);
        double walSizeMb = walFile.exists() ? walFile.length() / (1024.0 * 1024.0) : 0.0;

        boolean pass = eventsPerSec >= THROUGHPUT_THRESHOLD;

        System.out.println("=== C1: Append Throughput ===");
        System.out.printf(Locale.US, "Events inserted: %,d%n", EVENT_COUNT);
        System.out.printf(Locale.US, "Total time: %.3f seconds%n", totalSeconds);
        System.out.printf(Locale.US, "Throughput: %,.0f events/sec%n", eventsPerSec);
        System.out.printf(Locale.US, "Per-insert latency (ns): p50=%,d p95=%,d p99=%,d%n",
                stats.p50(), stats.p95(), stats.p99());
        System.out.printf(Locale.US, "DB file size: %.1f MB%n", dbSizeMb);
        System.out.printf(Locale.US, "WAL file size: %.1f MB%n", walSizeMb);
        System.out.printf("RESULT: %s (threshold: >= %,d events/sec)%n",
                pass ? "PASS" : "FAIL", THROUGHPUT_THRESHOLD);
    }

    private static void deleteIfExists(String path) {
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            System.err.println("Warning: could not delete " + path);
        }
    }
}
