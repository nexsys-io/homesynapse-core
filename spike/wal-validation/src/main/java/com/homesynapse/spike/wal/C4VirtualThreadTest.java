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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * C4: Virtual Thread Compatibility — spike plan §4 C4.
 *
 * <p>Spawns 1 writer virtual thread (100 inserts/sec) and 20 reader virtual threads
 * (continuous {@code SELECT} by {@code entity_ref}), each on its own JDBC connection.
 * Runs for 60 seconds. Validates that WAL mode allows concurrent VT readers without
 * SQLITE_BUSY errors, deadlocks, or JVM crashes.
 *
 * <p>Each virtual thread gets a dedicated {@link Connection} — sqlite-jdbc connections
 * are not thread-safe.
 *
 * <p>Usage: {@code java -cp ... com.homesynapse.spike.wal.C4VirtualThreadTest <db-path>}
 */
public final class C4VirtualThreadTest {

    private static final int WRITE_RATE = 100;
    private static final int DURATION_SEC = 60;
    private static final int ENTITY_COUNT = 10;
    private static final int READER_COUNT = 20;
    private static final long WRITE_INTERVAL_NANOS = 10_000_000L; // 10ms = 100/sec
    private static final long JOIN_TIMEOUT_MS = 10_000L;          // deadlock detection

    private static final String[] EVENT_TYPES = {
        "state_reported", "state_changed", "command_executed", "error_occurred"
    };

    private static volatile boolean running = true;

    private C4VirtualThreadTest() {
        // runnable class — use main()
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: C4VirtualThreadTest <db-file-path>");
            System.exit(1);
        }
        String dbPath = args[0];

        deleteIfExists(dbPath);
        deleteIfExists(dbPath + "-wal");
        deleteIfExists(dbPath + "-shm");

        String jdbcUrl = "jdbc:sqlite:" + dbPath;

        // Pre-generate entity refs
        byte[][] entityRefs = new byte[ENTITY_COUNT][];
        for (int i = 0; i < ENTITY_COUNT; i++) {
            entityRefs[i] = SpikeUlidGenerator.generate();
        }

        // Setup schema
        try (Connection setupConn = DriverManager.getConnection(jdbcUrl)) {
            PragmaConfig.apply(setupConn);
            PragmaConfig.verify(setupConn);
            SpikeSchema.create(setupConn);
        }

        // Shared counters
        AtomicLong totalWrites = new AtomicLong(0);
        AtomicLong totalReads = new AtomicLong(0);
        AtomicInteger readBusyErrors = new AtomicInteger(0);
        AtomicInteger writeBusyErrors = new AtomicInteger(0);
        AtomicInteger exceptions = new AtomicInteger(0);

        System.out.println("Starting C4: Virtual Thread Compatibility test...");
        System.out.printf("  Writer VTs: 1  |  Reader VTs: %d  |  Duration: %ds%n",
                READER_COUNT, DURATION_SEC);
        System.out.println();

        // --- 1 writer virtual thread ---
        Thread writer = Thread.ofVirtual().name("vt-writer").start(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                PragmaConfig.apply(conn);
                conn.setAutoCommit(false);
                int[] entitySequences = new int[ENTITY_COUNT];
                int insertCount = 0;

                String sql = "INSERT INTO events "
                        + "(event_id, entity_ref, entity_sequence, event_type,"
                        + " event_time, ingest_time, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)";

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    while (running) {
                        int entityIdx = insertCount % ENTITY_COUNT;
                        entitySequences[entityIdx]++;
                        String eventType =
                                EVENT_TYPES[insertCount % EVENT_TYPES.length];
                        String now = Instant.now().toString();
                        byte[] payload = buildPayload(eventType,
                                entitySequences[entityIdx]);

                        try {
                            ps.setBytes(1, SpikeUlidGenerator.generate());
                            ps.setBytes(2, entityRefs[entityIdx]);
                            ps.setInt(3, entitySequences[entityIdx]);
                            ps.setString(4, eventType);
                            ps.setString(5, now);
                            ps.setString(6, now);
                            ps.setBytes(7, payload);
                            ps.executeUpdate();
                            insertCount++;
                            totalWrites.incrementAndGet();

                            if (insertCount % WRITE_RATE == 0) {
                                conn.commit();
                            }
                        } catch (SQLException e) {
                            if (isBusyError(e)) {
                                writeBusyErrors.incrementAndGet();
                            } else {
                                exceptions.incrementAndGet();
                                System.err.println("vt-writer exception: "
                                        + e.getMessage());
                            }
                        }

                        LockSupport.parkNanos(WRITE_INTERVAL_NANOS);
                    }
                    conn.commit(); // Final flush
                }
            } catch (SQLException e) {
                exceptions.incrementAndGet();
                System.err.println("vt-writer connection error: " + e.getMessage());
            }
        });

        // --- 20 reader virtual threads ---
        Thread[] readers = new Thread[READER_COUNT];
        for (int r = 0; r < READER_COUNT; r++) {
            int readerId = r;
            readers[r] = Thread.ofVirtual().name("vt-reader-" + r).start(() -> {
                try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                    PragmaConfig.apply(conn);
                    int queryIndex = 0;

                    while (running) {
                        try {
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "SELECT * FROM events WHERE entity_ref = ?"
                                            + " ORDER BY entity_sequence")) {
                                ps.setBytes(1, entityRefs[
                                        (readerId + queryIndex) % ENTITY_COUNT]);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        // consume results
                                    }
                                }
                            }
                            totalReads.incrementAndGet();
                        } catch (SQLException e) {
                            if (isBusyError(e)) {
                                readBusyErrors.incrementAndGet();
                            } else {
                                exceptions.incrementAndGet();
                                System.err.println("vt-reader-" + readerId
                                        + " error: " + e.getMessage());
                            }
                        }
                        queryIndex++;
                        LockSupport.parkNanos(1_000_000L); // 1ms between queries
                    }
                } catch (SQLException e) {
                    exceptions.incrementAndGet();
                    System.err.println("vt-reader-" + readerId
                            + " connection error: " + e.getMessage());
                }
            });
        }

        // Run for the specified duration
        Thread.sleep(DURATION_SEC * 1000L);
        running = false;

        // Join with deadlock detection timeout
        int deadlocks = 0;

        writer.join(JOIN_TIMEOUT_MS);
        if (writer.isAlive()) {
            deadlocks++;
        }
        for (Thread reader : readers) {
            reader.join(JOIN_TIMEOUT_MS);
            if (reader.isAlive()) {
                deadlocks++;
            }
        }

        // --- Results ---
        System.out.println("=== C4: Virtual Thread Compatibility ===");
        System.out.printf(Locale.US, "Duration: %d seconds%n", DURATION_SEC);
        System.out.printf(Locale.US, "Writer: %,d events written%n",
                totalWrites.get());
        System.out.printf(Locale.US, "Readers: %,d total queries across %d VTs%n",
                totalReads.get(), READER_COUNT);
        System.out.printf(Locale.US, "SQLITE_BUSY errors (read): %d%n",
                readBusyErrors.get());
        System.out.printf(Locale.US, "SQLITE_BUSY errors (write): %d%n",
                writeBusyErrors.get());
        System.out.printf(Locale.US, "Exceptions: %d%n", exceptions.get());
        System.out.printf(Locale.US, "Deadlocks: %d%n", deadlocks);

        boolean pass = readBusyErrors.get() == 0
                && deadlocks == 0
                && exceptions.get() == 0;
        System.out.printf("RESULT: %s (threshold: zero SQLITE_BUSY on readers,"
                        + " zero deadlocks, zero crashes)%n",
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
