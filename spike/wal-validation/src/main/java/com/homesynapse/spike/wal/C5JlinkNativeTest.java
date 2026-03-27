/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.spike.wal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Locale;

/**
 * C5: Native Library Extraction — spike plan §4 C5.
 *
 * <p>Simulates systemd {@code PrivateTmp=true} by setting {@code java.io.tmpdir} to a
 * non-standard directory before loading sqlite-jdbc. This validates that the native
 * library extraction works in restricted tmp environments typical of production
 * systemd service deployments.
 *
 * <p>The test:
 * <ol>
 *   <li>Creates a custom temporary directory under {@code /tmp}
 *   <li>Sets {@code java.io.tmpdir} to it <b>before</b> any sqlite-jdbc class loads
 *   <li>Opens a database connection (forces native library extraction)
 *   <li>Applies PRAGMAs, creates schema, inserts 10 events, reads them back
 *   <li>Checks if the native library was extracted to the custom tmpdir
 * </ol>
 *
 * <p>Usage: {@code java -cp ... com.homesynapse.spike.wal.C5JlinkNativeTest <db-path>}
 */
public final class C5JlinkNativeTest {

    private static final int EVENT_COUNT = 10;
    private static final int ENTITY_COUNT = 10;
    private static final String TMPDIR_PREFIX = "homesynapse-spike-";

    private static final String[] EVENT_TYPES = {
        "state_reported", "state_changed", "command_executed", "error_occurred"
    };

    private C5JlinkNativeTest() {
        // runnable class — use main()
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: C5JlinkNativeTest <db-file-path>");
            System.exit(1);
        }
        String dbPath = args[0];

        // --- CRITICAL: set tmpdir BEFORE any sqlite-jdbc class loads ---
        Path customTmpDir = Files.createTempDirectory(
                Path.of("/tmp"), TMPDIR_PREFIX);
        String previousTmpDir = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", customTmpDir.toString());

        System.out.println("C5: Custom tmpdir set to: " + customTmpDir);
        System.out.println("C5: Previous tmpdir was: " + previousTmpDir);

        deleteIfExists(dbPath);
        deleteIfExists(dbPath + "-wal");
        deleteIfExists(dbPath + "-shm");

        String jdbcUrl = "jdbc:sqlite:" + dbPath;
        long startNanos = System.nanoTime();

        boolean dbOpened = false;
        int eventsInserted = 0;
        long eventsReadBack = 0;

        // Open connection — this triggers native library extraction into custom tmpdir
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            dbOpened = true;
            PragmaConfig.apply(conn);
            PragmaConfig.verify(conn);
            SpikeSchema.create(conn);

            // Insert 10 events
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

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < EVENT_COUNT; i++) {
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
                    eventsInserted++;
                }
                conn.commit();
            }

            // Read back
            eventsReadBack = SpikeSchema.eventCount(conn);
        }

        long startupMs = (System.nanoTime() - startNanos) / 1_000_000;

        // --- Search for native library in custom tmpdir (recursive 2-level) ---
        boolean nativeExtracted = false;
        String extractionPath = "not found";

        File[] tmpContents = customTmpDir.toFile().listFiles();
        if (tmpContents != null) {
            nativeExtracted = findNativeLib(tmpContents);
            if (nativeExtracted) {
                extractionPath = findNativeLibPath(tmpContents);
            } else {
                // Check one level of subdirectories (sqlite-jdbc typically extracts
                // into a versioned subdirectory)
                for (File dir : tmpContents) {
                    if (dir.isDirectory()) {
                        File[] subContents = dir.listFiles();
                        if (subContents != null && findNativeLib(subContents)) {
                            nativeExtracted = true;
                            extractionPath = findNativeLibPath(subContents);
                            break;
                        }
                    }
                }
            }
        }

        // --- Structured output ---
        System.out.println();
        System.out.println("=== C5: Native Library Extraction ===");
        System.out.println("Custom tmpdir: " + customTmpDir);
        System.out.println("Native library extracted: " + nativeExtracted);
        System.out.println("Extraction path: " + extractionPath);
        System.out.println("DB opened: " + dbOpened);
        System.out.printf(Locale.US, "Events inserted: %d%n", eventsInserted);
        System.out.printf(Locale.US, "Events read back: %d%n", eventsReadBack);
        System.out.printf(Locale.US, "Startup time: %d ms%n", startupMs);

        boolean pass = dbOpened && eventsReadBack == EVENT_COUNT;
        System.out.printf("RESULT: %s (threshold: DB opens and responds to queries)%n",
                pass ? "PASS" : "FAIL");
    }

    /**
     * Checks whether any file in the array looks like a sqlite native library.
     */
    private static boolean findNativeLib(File[] files) {
        for (File f : files) {
            if (isNativeLib(f.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the path of the first sqlite native library found.
     */
    private static String findNativeLibPath(File[] files) {
        for (File f : files) {
            if (isNativeLib(f.getName())) {
                return f.getAbsolutePath();
            }
        }
        return "not found";
    }

    private static boolean isNativeLib(String name) {
        return name.contains("sqlite")
                && (name.endsWith(".so") || name.endsWith(".dll")
                    || name.endsWith(".dylib") || name.endsWith(".jnilib"));
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
