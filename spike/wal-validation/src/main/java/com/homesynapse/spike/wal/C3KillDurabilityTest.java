/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.spike.wal;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * C3: Kill -9 Durability — spike plan §4 C3.
 *
 * <p>Two-mode test designed to be orchestrated by {@code kill-driver.sh}.
 *
 * <ul>
 *   <li><b>Writer mode:</b> Sustained inserts at 100/sec. After each batch commit,
 *       writes the cumulative acknowledged event count to a sidecar file
 *       ({@code <dbpath>.count}). Runs indefinitely until killed.
 *   <li><b>Verify mode:</b> Opens the database, counts events via
 *       {@link SpikeSchema#eventCount}, reads the sidecar count, compares.
 *       Reports delta and PASS/FAIL.
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   java -cp ... com.homesynapse.spike.wal.C3KillDurabilityTest &lt;db-path&gt; writer
 *   java -cp ... com.homesynapse.spike.wal.C3KillDurabilityTest &lt;db-path&gt; verify [trial]
 * </pre>
 */
public final class C3KillDurabilityTest {

    private static final int COMMIT_INTERVAL = 100;      // events per commit (1 sec at 100/sec)
    private static final int ENTITY_COUNT = 10;
    private static final long WRITE_INTERVAL_NANOS = 10_000_000L; // 10ms = 100/sec

    private static final String[] EVENT_TYPES = {
        "state_reported", "state_changed", "command_executed", "error_occurred"
    };

    private C3KillDurabilityTest() {
        // runnable class — use main()
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println(
                    "Usage: C3KillDurabilityTest <db-file-path> <writer|verify> [trial]");
            System.exit(1);
        }
        String dbPath = args[0];
        String mode = args[1];

        switch (mode) {
            case "writer" -> runWriter(dbPath);
            case "verify" -> {
                int trial = args.length >= 3 ? Integer.parseInt(args[2]) : 0;
                runVerify(dbPath, trial);
            }
            default -> {
                System.err.println(
                        "Unknown mode: " + mode + ". Use 'writer' or 'verify'.");
                System.exit(1);
            }
        }
    }

    /**
     * Writer mode: sustained inserts at 100/sec, updating sidecar after each commit.
     * Runs indefinitely until the process is killed (SIGKILL expected).
     */
    private static void runWriter(String dbPath) throws SQLException, IOException {
        // Clean for fresh trial (kill-driver.sh also cleans, but this supports standalone use)
        deleteIfExists(dbPath);
        deleteIfExists(dbPath + "-wal");
        deleteIfExists(dbPath + "-shm");
        deleteIfExists(dbPath + ".count");

        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        Path sidecarPath = Path.of(dbPath + ".count");

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            PragmaConfig.apply(conn);
            SpikeSchema.create(conn);
            conn.setAutoCommit(false);

            byte[][] entityRefs = new byte[ENTITY_COUNT][];
            for (int i = 0; i < ENTITY_COUNT; i++) {
                entityRefs[i] = SpikeUlidGenerator.generate();
            }
            int[] entitySequences = new int[ENTITY_COUNT];

            String sql = "INSERT INTO events "
                    + "(event_id, entity_ref, entity_sequence, event_type,"
                    + " event_time, ingest_time, payload)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";

            long totalCommitted = 0;
            int batchCount = 0;

            System.out.println("C3 writer started. PID: "
                    + ProcessHandle.current().pid());

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                //noinspection InfiniteLoopStatement — intentional: killed by SIGKILL
                while (true) {
                    int entityIdx = batchCount % ENTITY_COUNT;
                    entitySequences[entityIdx]++;
                    String eventType = EVENT_TYPES[batchCount % EVENT_TYPES.length];
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
                    batchCount++;

                    if (batchCount % COMMIT_INTERVAL == 0) {
                        conn.commit();
                        totalCommitted += COMMIT_INTERVAL;
                        // Sidecar update AFTER commit — safe ordering:
                        // if killed between commit and sidecar write, DB has more
                        // than sidecar reports (delta >= 0, still PASS).
                        Files.writeString(sidecarPath,
                                Long.toString(totalCommitted));
                    }

                    LockSupport.parkNanos(WRITE_INTERVAL_NANOS);
                }
            }
        }
    }

    /**
     * Verify mode: count events in DB, compare to sidecar, report result.
     *
     * @param trial optional trial number for display (0 = omit)
     */
    private static void runVerify(String dbPath, int trial)
            throws SQLException, IOException {
        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        Path sidecarPath = Path.of(dbPath + ".count");

        // Read sidecar count (last acknowledged commit)
        long sidecarCount = 0;
        if (Files.exists(sidecarPath)) {
            String content = Files.readString(sidecarPath).trim();
            if (!content.isEmpty()) {
                sidecarCount = Long.parseLong(content);
            }
        }

        // Read actual DB count
        long dbCount;
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            PragmaConfig.apply(conn);
            dbCount = SpikeSchema.eventCount(conn);
        }

        // Delta >= 0 means DB has at least as many as acknowledged — no loss
        long delta = dbCount - sidecarCount;
        boolean pass = delta >= 0;

        if (trial > 0) {
            System.out.printf("=== C3: Kill -9 Durability (Trial %d) ===%n", trial);
        } else {
            System.out.println("=== C3: Kill -9 Durability ===");
        }
        System.out.printf(Locale.US, "Events in sidecar file: %,d%n", sidecarCount);
        System.out.printf(Locale.US, "Events in database: %,d%n", dbCount);
        System.out.printf(Locale.US, "Delta: %d%n", delta);
        System.out.printf("RESULT: %s (threshold: zero event loss)%n",
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

    private static void deleteIfExists(String path) {
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            System.err.println("Warning: could not delete " + path);
        }
    }
}
