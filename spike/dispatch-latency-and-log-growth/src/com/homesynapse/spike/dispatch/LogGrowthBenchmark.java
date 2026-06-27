/*
 * HomeSynapse Core — THROWAWAY SPIKE (disposable; slated for `git rm`).
 * Spike: dispatch-latency-and-log-growth  (sizes §1 D4; honors D2 on the fold).
 *
 * NOT production code. Lives outside the production tree on purpose. Job:
 *
 *   Measure (a) event-log APPEND throughput (events/sec) and on-disk GROWTH
 *   (bytes/event after a WAL checkpoint), and (b) projection-REBUILD (replay)
 *   time as the log grows (N = 1e4, 1e5, 1e6), to SIZE the D4 retention /
 *   snapshot / compaction strategy: at what log size does rebuild get painful?
 *
 * Engine: SQLite in WAL mode (the real persistence engine — the wal-validation
 * spike confirms it), with the LTD-03 production PRAGMAs and a schema mirroring
 * the real `events` table (Doc 04 §4.1 / Doc 01 §4.2).
 *
 * D2 on the fold: replay is a PURE function — it folds events into an in-memory
 * entity-state projection (a Map) with NO external side-effects.
 */
package com.homesynapse.spike.dispatch;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public final class LogGrowthBenchmark {

    static final String CREATE_EVENTS = ""
            + "CREATE TABLE events (\n"
            + "    global_position INTEGER PRIMARY KEY AUTOINCREMENT,\n"
            + "    event_id        BLOB(16) NOT NULL,\n"
            + "    entity_ref      BLOB(16) NOT NULL,\n"
            + "    entity_sequence INTEGER  NOT NULL,\n"
            + "    event_type      TEXT     NOT NULL,\n"
            + "    event_time      TEXT,\n"
            + "    ingest_time     TEXT     NOT NULL,\n"
            + "    payload         BLOB     NOT NULL\n"
            + ")";
    static final String CREATE_IDX =
            "CREATE INDEX idx_events_entity ON events (entity_ref, entity_sequence)";

    // LTD-03 production PRAGMAs (Doc 04 §3.3 + wal-validation spike PragmaConfig).
    static void applyPragmas(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
            s.execute("PRAGMA cache_size = -128000");      // 128 MB page cache
            s.execute("PRAGMA mmap_size = 1073741824");    // 1 GB mmap
            s.execute("PRAGMA temp_store = MEMORY");
            s.execute("PRAGMA journal_size_limit = 6144000"); // 6 MB WAL cap
            s.execute("PRAGMA busy_timeout = 5000");
        }
    }

    // Representative ~250-400 byte JSON-ish payload (a state_changed-shaped
    // envelope), fields varied so it is not trivially compressible.
    static byte[] payloadFor(long entity, long seq, int kind, Random rnd) {
        double val = 18.0 + rnd.nextDouble() * 8.0;
        String cap = (kind == 0 ? "temperature" : kind == 1 ? "switch" : kind == 2 ? "humidity" : "illuminance");
        String unit = (kind == 0 ? "celsius" : kind == 2 ? "percent" : "lux");
        String json = "{"
                + "\"v\":2,"
                + "\"entity\":\"01J9ZQ" + String.format("%010d", entity) + "ABCDEF\","
                + "\"cap\":\"" + cap + "\","
                + "\"old\":{\"t\":3,\"v\":" + String.format("%.3f", val - 0.25) + "},"
                + "\"new\":{\"t\":3,\"v\":" + String.format("%.3f", val) + "},"
                + "\"seq\":" + seq + ","
                + "\"origin\":\"PHYSICAL\","
                + "\"actor\":{\"kind\":\"SYSTEM\",\"id\":\"01J9ZQSYSTEM00000000000000\"},"
                + "\"unit\":\"" + unit + "\","
                + "\"src\":\"zigbee:0x00158d000" + String.format("%06x", (int)(entity & 0xFFFFFF)) + "\","
                + "\"note\":\"representative-state-change-envelope-padding-to-realistic-size\""
                + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] randomId(Random rnd) {
        byte[] id = new byte[16];
        rnd.nextBytes(id);
        return id;
    }

    static byte[] longTo16(long v) {
        byte[] b = new byte[16];
        for (int i = 0; i < 8; i++) b[15 - i] = (byte) (v >>> (8 * i));
        return b;
    }

    static void checkpointTruncate(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    static long fileSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return -1; }
    }

    // Append N events in batches of `batch` (one txn per batch — realistic
    // commit/fsync cadence). Returns events/sec for the append phase.
    static double append(Connection c, long n, int batch, long entityCount, Random rnd) throws Exception {
        c.setAutoCommit(false);
        String ins = "INSERT INTO events "
                + "(event_id, entity_ref, entity_sequence, event_type, event_time, ingest_time, payload) "
                + "VALUES (?,?,?,?,?,?,?)";
        long[] seqByEntity = new long[(int) entityCount];
        long start = System.nanoTime();
        try (PreparedStatement ps = c.prepareStatement(ins)) {
            long inBatch = 0;
            for (long i = 0; i < n; i++) {
                int e = (int) (i % entityCount);
                int kind = e & 3;
                long seq = ++seqByEntity[e];
                String now = "2026-06-26T17:00:" + String.format("%02d", (int) (i % 60)) + "Z";
                ps.setBytes(1, randomId(rnd));
                ps.setBytes(2, longTo16(1000 + e));
                ps.setLong(3, seq);
                ps.setString(4, kind == 1 ? "command_confirmed" : "state_changed");
                ps.setString(5, now);
                ps.setString(6, now);
                ps.setBytes(7, payloadFor(1000 + e, seq, kind, rnd));
                ps.addBatch();
                if (++inBatch == batch) {
                    ps.executeBatch();
                    c.commit();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) {
                ps.executeBatch();
                c.commit();
            }
        }
        long elapsed = System.nanoTime() - start;
        c.setAutoCommit(true);
        return n / (elapsed / 1_000_000_000.0);
    }

    // REPLAY = full ordered scan folding into an in-memory entity projection.
    // PURE: no external side-effects (D2).
    static ReplayResult replay(Connection c) throws Exception {
        Map<Long, String> entityState = new HashMap<>(4096);
        long rows = 0;
        long payloadBytes = 0;
        try (Statement s = c.createStatement()) {
            s.setFetchSize(10_000);
            long start = System.nanoTime();
            try (ResultSet rs = s.executeQuery(
                    "SELECT global_position, entity_ref, event_type, payload "
                    + "FROM events ORDER BY global_position")) {
                while (rs.next()) {
                    rows++;
                    byte[] entityRef = rs.getBytes(2);
                    long entityKey = last8AsLong(entityRef);
                    String et = rs.getString(3);
                    byte[] payload = rs.getBytes(4);
                    payloadBytes += payload.length;
                    int token = (payload.length > 0) ? (payload[payload.length - 1] & 0xFF) : 0;
                    entityState.put(entityKey, et + ":" + token);
                }
            }
            long elapsed = System.nanoTime() - start;
            return new ReplayResult(elapsed / 1_000_000.0, rows, entityState.size(), payloadBytes);
        }
    }

    static long last8AsLong(byte[] b) {
        long v = 0;
        for (int i = b.length - 8; i < b.length; i++) v = (v << 8) | (b[i] & 0xFF);
        return v;
    }

    record ReplayResult(double ms, long rows, int entities, long payloadBytes) {}

    public static void main(String[] args) throws Exception {
        String dbDir = (args.length > 0) ? args[0] : ".";
        long entityCount = (args.length > 1) ? Long.parseLong(args[1]) : 200;
        int batch = (args.length > 2) ? Integer.parseInt(args[2]) : 500;
        long[] Ns;
        if (args.length > 3) {
            Ns = new long[args.length - 3];
            for (int i = 3; i < args.length; i++) Ns[i - 3] = Long.parseLong(args[i]);
        } else {
            Ns = new long[]{10_000L, 100_000L, 1_000_000L};
        }

        System.out.println("=== Benchmark 2: log growth + replay (SQLite WAL) ===");
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("dbDir=" + dbDir + " entityCount=" + entityCount + " batch=" + batch);
        System.out.println("PRAGMAs: WAL, synchronous=NORMAL, cache=-128000, mmap=1GiB, "
                + "temp_store=MEMORY, journal_size_limit=6MB, busy_timeout=5000 (LTD-03)");
        System.out.println();
        System.out.printf("%-10s %-16s %-14s %-16s %-14s %-12s%n",
                "N", "append ev/s", "DB bytes", "bytes/event", "replay ms", "replay ev/s");

        boolean didCadence = false;
        Class.forName("org.sqlite.JDBC");

        for (long n : Ns) {
            Path db = Path.of(dbDir, "spike-loggrowth-N" + n + ".db");
            deleteDb(db);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
                applyPragmas(c);
                try (Statement s = c.createStatement()) {
                    s.execute(CREATE_EVENTS);
                    s.execute(CREATE_IDX);
                }
                Random rnd = new Random(42 + n);
                double evPerSec = append(c, n, batch, entityCount, rnd);
                checkpointTruncate(c);
                long bytes = fileSize(db);
                double bpe = (double) bytes / n;

                ReplayResult rr = replay(c);
                double replayEvPerSec = rr.rows() / (rr.ms() / 1000.0);

                System.out.printf("%-10d %-16.0f %-14d %-16.1f %-14.1f %-12.0f%n",
                        n, evPerSec, bytes, bpe, rr.ms(), replayEvPerSec);
                System.out.printf("RESULT_B2 N=%d appendEvPerSec=%.0f dbBytes=%d bytesPerEvent=%.2f "
                        + "replayMs=%.1f replayEvPerSec=%.0f entities=%d%n",
                        n, evPerSec, bytes, bpe, rr.ms(), replayEvPerSec, rr.entities());
            }

            if (!didCadence) {
                didCadence = true;
                System.out.println();
                System.out.println("--- commit-cadence (fsync) sensitivity at N=" + n + " ---");
                System.out.printf("%-14s %-16s%n", "batch", "append ev/s");
                int[] batches = {1, 10, 50, 100, 500, 2000};
                for (int b : batches) {
                    Path db2 = Path.of(dbDir, "spike-cadence-b" + b + ".db");
                    deleteDb(db2);
                    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db2)) {
                        applyPragmas(c);
                        try (Statement s = c.createStatement()) {
                            s.execute(CREATE_EVENTS);
                            s.execute(CREATE_IDX);
                        }
                        Random rnd = new Random(7 + b);
                        double evs = append(c, n, b, entityCount, rnd);
                        System.out.printf("%-14d %-16.0f%n", b, evs);
                        System.out.printf("RESULT_B2C batch=%d N=%d appendEvPerSec=%.0f%n", b, n, evs);
                    }
                    deleteDb(db2);
                }
                System.out.println();
            }

            deleteDb(db);
        }

        System.out.println();
        System.out.println("(1e7 not run by default — extrapolate replay linearly from the "
                + "1e4->1e6 points; on-disk bytes/event is ~constant so 1e7 ~= 10x the 1e6 file.)");
    }

    static void deleteDb(Path db) {
        for (String suf : new String[]{"", "-wal", "-shm"}) {
            File f = new File(db.toString() + suf);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
