/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventTypes;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REGISTRY-COLD-1 (IR-148) — the PLAN of the type read ({@code SqliteEventStore.readByType} and
 * its hasMore probe), read through the shipped sqlite-jdbc driver on a database the real
 * V001–V005 migrations built. The registry's boot rebuild reads its four event types through
 * these two statements up to the log head; a planner choice that walked the INTEGER PRIMARY KEY
 * instead of ranging {@code idx_events_type} would return the same few hundred rows at the cost
 * of the whole log — the IR-148 cold boot in another guise, invisible to any count of returned
 * rows. {@code INDEXED BY idx_events_type} pins the plan (the IR-40 form); this test pins the pin.
 *
 * <p>P-1 and P-2 read the plan on an empty table AND on {@value #LOADED_ROWS} rows where the
 * queried type is {@code 1 / QUERIED_TYPE_EVERY} of the table, at every bound page size and
 * unbound (IR-40 recorded that the row count and the absence of {@code sqlite_stat1} did not move
 * the planner — this test does not assume it). P-3 is the loud failure that is the point of
 * {@code INDEXED BY} over a rewrite: without the index the statements fail at prepare, never
 * degrade to a walk. P-1 and P-2 also run the UNHINTED text once on the loaded table and print
 * the shipped planner's own choice — the register's fact, quoted and never asserted.</p>
 *
 * <p>The clock is injected fixed (self-enforced {@code NO_DIRECT_TIME_ACCESS} for this module's
 * test sources); the plan does not depend on it. The loaded rows go in by direct SQL on the
 * write connection, never through {@code append}: the plan is what is under test, and a payload
 * that is never decoded needs no codec.</p>
 */
@DisplayName("SqliteEventStore — readByType and its hasMore probe are planned on idx_events_type"
        + " (REGISTRY-COLD-1, IR-148)")
final class SqliteEventStoreTypeReadPlanTest {

    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql",
            "V002__subscriber_dead_letter_queue.sql",
            "V003__add_snapshots_and_drop_redundant_index.sql",
            "V004__dlq_operational_indices.sql",
            "V005__at_rest_payload_encryption_columns.sql");
    private static final DeploymentProfile PROFILE = DeploymentProfile.HOME;

    /** The index the migration names (V001 line 58); {@code INDEXED BY} binds to this NAME. */
    private static final String INDEX_NAME = "idx_events_type";
    private static final String HINT = " INDEXED BY " + INDEX_NAME;
    private static final String INDEX_PLAN = "USING INDEX " + INDEX_NAME;
    private static final String COVERING_INDEX_PLAN = "USING COVERING INDEX " + INDEX_NAME;
    private static final String ROWID_WALK_PLAN = "USING INTEGER PRIMARY KEY";
    private static final String FULL_SCAN_PLAN = "SCAN events";

    /**
     * The bound {@code maxCount} values the plan is read under: the registry rebuild's and the
     * bus's page (500), the smallest, and a page far above any production caller's.
     */
    private static final int[] BOUND_MAX_COUNTS = {1, 500, 10_000};
    /** Leaves the {@code LIMIT ?} placeholder unbound (SQLite plans it as NULL → no limit). */
    private static final int UNBOUND = -1;

    /** The loaded table: {@value} rows, every {@value #QUERIED_TYPE_EVERY}th of the queried type. */
    private static final int LOADED_ROWS = 100_000;
    private static final int QUERIED_TYPE_EVERY = 100;
    private static final String QUERIED_TYPE = EventTypes.DEVICE_REGISTERED;
    private static final String FILLER_TYPE = EventTypes.STATE_REPORTED;
    private static final int INSERT_BATCH = 10_000;

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));
    private static final Ulid SUBJECT = Ulid.parse("01JBBBBBBBBBBBBBBBBBBBBBBB");
    private static final byte[] PAYLOAD = "{}".getBytes(StandardCharsets.UTF_8);

    /** Every NOT NULL column without a default (V001 lines 28–55); the rest take their defaults. */
    private static final String INSERT_ROW_SQL =
            "INSERT INTO events (event_id, home_id, event_type, ingest_time, subject_ref, "
                    + "subject_type, subject_sequence, correlation_id, event_category, "
                    + "payload_size, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    @TempDir
    Path tempDir;

    private DatabaseExecutor dbExecutor;
    private SqliteEventStore store;

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    SqliteEventStoreTypeReadPlanTest() {
    }

    @BeforeEach
    void openMigratedStore() {
        dbExecutor = new DatabaseExecutor(PROFILE, FIXED_CLOCK);
        dbExecutor.start(tempDir.resolve("events.db"), EVENTS_MIGRATION_PATH,
                EVENTS_MIGRATION_FILES, MigrationConfig.freshInstall());
        List<Class<? extends DomainEvent>> classes = new ArrayList<>(AllEventClasses.ALL_EVENTS);
        EventTypeRegistry registry = new EventTypeRegistry(classes);
        ObjectMapper mapper = PersistenceObjectMapper.create();
        JacksonWarmup warmup = JacksonWarmup.warmup(mapper, registry);
        EventPayloadCodec codec = new EventPayloadCodec(registry, warmup);
        store = new SqliteEventStore(dbExecutor, codec, registry, FIXED_CLOCK, TEST_HOME_ID);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.clearThreadLocalForTesting();
            store = null;
        }
        if (dbExecutor != null) {
            try {
                dbExecutor.shutdown();
            } catch (RuntimeException ignore) {
                // tear-down failure must not mask the primary assertion
            }
            dbExecutor = null;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // P-1 — the page read
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P-1: SELECT_BY_TYPE_SQL carries INDEXED BY idx_events_type, and EXPLAIN QUERY PLAN"
            + " through the shipped driver reads the type index — never the INTEGER PRIMARY KEY,"
            + " never a scan — bound at 1, 500 and 10,000 and unbound, on an empty table and on"
            + " 100,000 rows where the queried type is 1 %; the unhinted plans are printed once")
    void p1_selectByType_pinnedToTheTypeIndex() {
        assertThat(SqliteEventStore.SELECT_BY_TYPE_SQL)
                .as("the hint sits between FROM events and WHERE — a dropped index then fails loudly")
                .contains("FROM events" + HINT + " WHERE event_type = ?");

        assertTypeReadPlannedOnTheIndex("the empty table", SqliteEventStore.SELECT_BY_TYPE_SQL);

        loadRows(LOADED_ROWS);
        assertTypeReadPlannedOnTheIndex(LOADED_ROWS + " rows", SqliteEventStore.SELECT_BY_TYPE_SQL);

        // The once-only unhinted run (IR-148's register row): the shipped planner's own choice at
        // every bound, quoted in the return and asserted nowhere.
        String unhinted = SqliteEventStore.SELECT_BY_TYPE_SQL.replace(HINT, "");
        assertThat(unhinted).doesNotContain("INDEXED BY");
        for (int maxCount : BOUND_MAX_COUNTS) {
            System.out.println("type-read-plan.unhinted: statement=SELECT_BY_TYPE_SQL rows="
                    + LOADED_ROWS + " maxCount=" + maxCount + " plan="
                    + explainTypeRead(unhinted, maxCount));
        }
        System.out.println("type-read-plan.unhinted: statement=SELECT_BY_TYPE_SQL rows="
                + LOADED_ROWS + " maxCount=unbound plan=" + explainTypeRead(unhinted, UNBOUND));
    }

    private void assertTypeReadPlannedOnTheIndex(String table, String sql) {
        for (int maxCount : BOUND_MAX_COUNTS) {
            assertPlannedOnTheIndex("SELECT_BY_TYPE_SQL on " + table + " at maxCount=" + maxCount,
                    explainTypeRead(sql, maxCount));
        }
        assertPlannedOnTheIndex("SELECT_BY_TYPE_SQL on " + table + " at maxCount unbound",
                explainTypeRead(sql, UNBOUND));
    }

    // ──────────────────────────────────────────────────────────────────
    // P-2 — the hasMore probe
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P-2: HAS_MORE_FOR_TYPE_SQL carries INDEXED BY idx_events_type, and its plan"
            + " reads the type index on an empty table and on 100,000 rows; the unhinted plan"
            + " is printed once")
    void p2_hasMoreProbe_pinnedToTheTypeIndex() {
        assertThat(SqliteEventStore.HAS_MORE_FOR_TYPE_SQL)
                .as("the probe's hint, between FROM events and WHERE")
                .contains("FROM events" + HINT + " WHERE event_type = ?")
                .endsWith("LIMIT 1");

        assertPlannedOnTheIndex("HAS_MORE_FOR_TYPE_SQL on the empty table",
                explainProbe(SqliteEventStore.HAS_MORE_FOR_TYPE_SQL));

        loadRows(LOADED_ROWS);
        assertPlannedOnTheIndex("HAS_MORE_FOR_TYPE_SQL on " + LOADED_ROWS + " rows",
                explainProbe(SqliteEventStore.HAS_MORE_FOR_TYPE_SQL));

        String unhinted = SqliteEventStore.HAS_MORE_FOR_TYPE_SQL.replace(HINT, "");
        assertThat(unhinted).doesNotContain("INDEXED BY");
        System.out.println("type-read-plan.unhinted: statement=HAS_MORE_FOR_TYPE_SQL rows="
                + LOADED_ROWS + " plan=" + explainProbe(unhinted));
    }

    // ──────────────────────────────────────────────────────────────────
    // P-3 — the loud failure
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P-3: with idx_events_type dropped, readByType and the hasMore probe both fail"
            + " with SQLite's 'no such index' — the loud failure is the point of INDEXED BY")
    void p3_withoutTheIndex_bothStatementsFailLoudly() {
        dbExecutor.writeCoordinator().submit(WritePriority.EVENT_PUBLISH, () -> {
            try (Statement stmt = dbExecutor.writeConnection().createStatement()) {
                stmt.executeUpdate("DROP INDEX " + INDEX_NAME);
            }
            return null;
        });

        assertThatThrownBy(() -> store.readByType(QUERIED_TYPE, 0L, 7))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("no such index: " + INDEX_NAME);
        assertThatThrownBy(() -> explainProbe(SqliteEventStore.HAS_MORE_FOR_TYPE_SQL))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("no such index: " + INDEX_NAME);
    }

    // ──────────────────────────────────────────────────────────────────
    // Harness
    // ──────────────────────────────────────────────────────────────────

    private static void assertPlannedOnTheIndex(String binding, List<String> plan) {
        assertThat(plan)
                .as("EXPLAIN QUERY PLAN detail rows for %s: %s", binding, plan)
                .isNotEmpty()
                .anySatisfy(detail -> assertThat(detail).containsAnyOf(INDEX_PLAN, COVERING_INDEX_PLAN))
                .allSatisfy(detail -> assertThat(detail)
                        .doesNotContain(ROWID_WALK_PLAN)
                        .doesNotContain(FULL_SCAN_PLAN));
    }

    /**
     * {@code EXPLAIN QUERY PLAN} of the page read on the store's own read connection (the shipped
     * driver, the production PRAGMAs), the placeholders bound as {@code readByType} binds them
     * (type, position, maxCount); returns the {@code detail} column of every plan row.
     */
    private List<String> explainTypeRead(String sql, int maxCount) {
        return explain(sql, ps -> {
            ps.setString(1, QUERIED_TYPE);
            ps.setLong(2, 0L);
            if (maxCount != UNBOUND) {
                ps.setInt(3, maxCount);
            }
        });
    }

    /** {@code EXPLAIN QUERY PLAN} of the probe (type, position; its {@code LIMIT 1} is literal). */
    private List<String> explainProbe(String sql) {
        return explain(sql, ps -> {
            ps.setString(1, QUERIED_TYPE);
            ps.setLong(2, 0L);
        });
    }

    private List<String> explain(String sql, Binder binder) {
        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = dbExecutor.readConnections().get(0);
            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
                binder.bind(ps);
                List<String> details = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        details.add(rs.getString("detail"));
                    }
                }
                return details;
            }
        });
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    /**
     * Loads {@code rows} rows by direct SQL on the write connection in one transaction — every
     * {@value #QUERIED_TYPE_EVERY}th row of the queried type, the rest filler — then asserts the
     * table's shape so the ≤ 1 % premise is a fact of the fixture, not of the comment.
     */
    private void loadRows(int rows) {
        byte[] home = TEST_HOME_ID.value().toBytes();
        byte[] subject = SUBJECT.toBytes();
        long ingestMicros = TimeConversion.toMicros(FIXED_CLOCK.instant());
        dbExecutor.writeCoordinator().submit(WritePriority.EVENT_PUBLISH, () -> {
            Connection conn = dbExecutor.writeConnection();
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(INSERT_ROW_SQL)) {
                for (int i = 1; i <= rows; i++) {
                    byte[] id = rowId(i);
                    ps.setBytes(1, id);
                    ps.setBytes(2, home);
                    ps.setString(3, i % QUERIED_TYPE_EVERY == 0 ? QUERIED_TYPE : FILLER_TYPE);
                    ps.setLong(4, ingestMicros + i);
                    ps.setBytes(5, subject);
                    ps.setString(6, "ENTITY");
                    ps.setLong(7, i);
                    ps.setBytes(8, id);
                    ps.setString(9, "[\"device_state\"]");
                    ps.setInt(10, PAYLOAD.length);
                    ps.setBytes(11, PAYLOAD);
                    ps.addBatch();
                    if (i % INSERT_BATCH == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
            return null;
        });

        assertThat(store.latestPosition()).as("the loaded table's head").isEqualTo(rows);
        assertThat(countOfType(QUERIED_TYPE))
                .as("the queried type's share of the table")
                .isEqualTo(rows / QUERIED_TYPE_EVERY);
    }

    private long countOfType(String eventType) {
        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = dbExecutor.readConnections().get(0);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM events WHERE event_type = ?")) {
                ps.setString(1, eventType);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** A distinct 16-byte id per row (no uniqueness is enforced on {@code event_id}; distinct anyway). */
    private static byte[] rowId(int i) {
        return ByteBuffer.allocate(16).putLong(0L).putLong(i).array();
    }
}
