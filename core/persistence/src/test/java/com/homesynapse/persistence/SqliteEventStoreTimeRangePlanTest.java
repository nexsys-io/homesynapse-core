/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.HomeId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IR-40 — the PLAN of {@code SqliteEventStore.readByTimeRange}, read through the shipped
 * sqlite-jdbc driver on a database the real V001–V005 migrations built (never a hand-written
 * schema). MEASURE-2b F-2 measured {@code explainRun}'s hint read as O(N): the shipped SQLite
 * planned {@code SELECT_BY_TIME_RANGE_SQL} as {@code SEARCH events USING INTEGER PRIMARY KEY
 * (rowid>?)} — a walk from {@code afterPosition} upward testing the time predicate on every row
 * until {@code LIMIT} fills — never as a range on {@code idx_events_event_time}, whose key
 * ({@code COALESCE(event_time, ingest_time)}, V001 line 61) the predicate already matches. The
 * fix is {@code INDEXED BY idx_events_event_time} in the SQL: this test pins the plan (T1),
 * proves the {@code [from, to)} / ascending-position / {@code afterPosition} / {@code maxCount}
 * contract unchanged against a list computed from the inserted data (T2), and shows the loud
 * failure that is the point of {@code INDEXED BY} over a rewrite — a dropped index breaks the
 * query, never the plan silently (T3).
 *
 * <p>The clock is injected fixed (self-enforced {@code NO_DIRECT_TIME_ACCESS} for this
 * module's test sources); the plan does not depend on it. The fixed instant sits INSIDE T2's
 * window so a row with {@code event_time IS NULL} falls back to an in-window
 * {@code ingest_time} — the {@code COALESCE} half of the index key is exercised, not assumed.
 */
@DisplayName("SqliteEventStore — readByTimeRange is planned on idx_events_event_time (IR-40)")
final class SqliteEventStoreTimeRangePlanTest {

    private static final String EVENTS_MIGRATION_PATH = "db/migration/events";
    private static final List<String> EVENTS_MIGRATION_FILES = List.of(
            "V001__initial_event_store_schema.sql",
            "V002__subscriber_dead_letter_queue.sql",
            "V003__add_snapshots_and_drop_redundant_index.sql",
            "V004__dlq_operational_indices.sql",
            "V005__at_rest_payload_encryption_columns.sql");
    private static final DeploymentProfile PROFILE = DeploymentProfile.HOME;

    /** The index the migration names (V001 line 61); {@code INDEXED BY} binds to this NAME. */
    private static final String INDEX_NAME = "idx_events_event_time";
    private static final String INDEX_PLAN = "USING INDEX " + INDEX_NAME;
    private static final String ROWID_WALK_PLAN = "USING INTEGER PRIMARY KEY";

    /** T2's data: 1,000 rows whose {@code event_time} steps 3.6 s from T0 — an hour. */
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final int ROWS = 1_000;
    private static final long STEP_MILLIS = 3_600L;
    /** Every tenth row carries no {@code event_time}: its effective instant is the clock's. */
    private static final int NULL_EVENT_TIME_EVERY = 10;
    /** The 5-minute window {@code [T0 + 30 min, T0 + 35 min)}. */
    private static final Instant WINDOW_FROM = T0.plus(Duration.ofMinutes(30));
    private static final Instant WINDOW_TO = T0.plus(Duration.ofMinutes(35));
    private static final int MAX_COUNT = 7;

    /** Inside the window, so the {@code COALESCE} fallback rows qualify by {@code ingest_time}. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(T0.plus(Duration.ofMinutes(32).plusSeconds(30)), ZoneOffset.UTC);
    private static final HomeId TEST_HOME_ID =
            HomeId.of(Ulid.parse("01JAAAAAAAAAAAAAAAAAAAAAAA"));
    private static final EntityId ENTITY =
            new EntityId(Ulid.parse("01JBBBBBBBBBBBBBBBBBBBBBBB"));

    @TempDir
    Path tempDir;

    private DatabaseExecutor dbExecutor;
    private SqliteEventStore store;

    /** Explicit constructor per {@code -Xlint:all -Werror}. */
    SqliteEventStoreTimeRangePlanTest() {
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
    // T1 — the plan
    // ──────────────────────────────────────────────────────────────────

    /**
     * The bound {@code maxCount} values the plan is read under. The shipped planner reads a
     * bound {@code LIMIT ?} on re-prepare and treats it as a fixed limit: at {@code d2cddb1} it
     * chose the index for a bound limit of at most 400 and the rowid walk from 500 upward —
     * {@code SCAN_BATCH} (500), the production caller's value, sits on the walking side; the
     * table's row count (0 or 100k) and the absence of {@code sqlite_stat1} did not move the
     * choice (the IR-40 probe). {@code INDEXED BY} makes the plan invariant under the value.
     */
    private static final int[] BOUND_MAX_COUNTS = {1, 7, 400, 500, 1_000};

    @Test
    @DisplayName("T1: EXPLAIN QUERY PLAN of SELECT_BY_TIME_RANGE_SQL through the shipped driver"
            + " reads idx_events_event_time and never walks the INTEGER PRIMARY KEY — at every"
            + " bound maxCount, 500 (the production SCAN_BATCH) included, and unbound")
    void plan_usesTheEventTimeIndex() {
        for (int maxCount : BOUND_MAX_COUNTS) {
            assertPlannedOnTheIndex("maxCount=" + maxCount,
                    explain(SqliteEventStore.SELECT_BY_TIME_RANGE_SQL, maxCount));
        }
        assertPlannedOnTheIndex("maxCount unbound",
                explain(SqliteEventStore.SELECT_BY_TIME_RANGE_SQL, UNBOUND));
    }

    private static void assertPlannedOnTheIndex(String binding, List<String> plan) {
        assertThat(plan)
                .as("EXPLAIN QUERY PLAN detail rows at %s: %s", binding, plan)
                .anySatisfy(detail -> assertThat(detail).contains(INDEX_PLAN))
                .noneSatisfy(detail -> assertThat(detail).contains(ROWID_WALK_PLAN));
    }

    // ──────────────────────────────────────────────────────────────────
    // T2 — the contract, unchanged
    // ──────────────────────────────────────────────────────────────────

    /** The production caller's page size ({@code StandardExplanationService.SCAN_BATCH}). */
    private static final int SCAN_BATCH = 500;

    @Test
    @DisplayName("T2: 1,000 rows over an hour, a 5-minute window read after the window's midpoint"
            + " with maxCount 7 → exactly the 7 rows after that position inside [from, to),"
            + " ascending — the list computed from the inserted data, never from the old SQL;"
            + " the same window at maxCount 500 → every remaining window row, hasMore false")
    void plan_semanticsUnchanged() throws SequenceConflictException {
        List<EventEnvelope> inserted = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            Instant eventTime = i % NULL_EVENT_TIME_EVERY == 0 ? null : T0.plusMillis(STEP_MILLIS * i);
            inserted.add(store.publishRoot(powerDraft(eventTime, "row-" + i)));
        }
        // The contract's own words: COALESCE(event_time, ingest_time) in [from, to), then
        // globalPosition > afterPosition, ascending by position, at most maxCount.
        List<EventEnvelope> inWindow = inserted.stream()
                .filter(e -> {
                    Instant effective = e.eventTime() != null ? e.eventTime() : e.ingestTime();
                    return !effective.isBefore(WINDOW_FROM) && effective.isBefore(WINDOW_TO);
                })
                .sorted(Comparator.comparingLong(EventEnvelope::globalPosition))
                .toList();
        assertThat(inWindow).as("the fixture must fill more than one page").hasSizeGreaterThan(
                2 * MAX_COUNT);
        long afterPosition = inWindow.get(inWindow.size() / 2).globalPosition();
        List<EventId> expected = inWindow.stream()
                .filter(e -> e.globalPosition() > afterPosition)
                .limit(MAX_COUNT)
                .map(EventEnvelope::eventId)
                .toList();

        EventPage page = store.readByTimeRange(WINDOW_FROM, WINDOW_TO, afterPosition, MAX_COUNT);

        assertThat(page.events()).extracting(EventEnvelope::eventId).containsExactlyElementsOf(expected);
        assertThat(page.events()).extracting(EventEnvelope::globalPosition).isSorted();
        assertThat(page.events()).extracting(EventEnvelope::eventTime)
                .as("the COALESCE fallback rows (event_time NULL) ride the window by ingest_time")
                .containsNull();
        assertThat(page.hasMore()).as("more window rows follow the page").isTrue();
        assertThat(page.nextPosition()).isEqualTo(page.events().get(MAX_COUNT - 1).globalPosition());

        // The production page size — the bound value that walked the rowid at d2cddb1.
        List<EventId> allAfter = inWindow.stream()
                .filter(e -> e.globalPosition() > afterPosition)
                .map(EventEnvelope::eventId)
                .toList();
        EventPage wide = store.readByTimeRange(WINDOW_FROM, WINDOW_TO, afterPosition, SCAN_BATCH);

        assertThat(allAfter).hasSizeLessThan(SCAN_BATCH);
        assertThat(wide.events()).extracting(EventEnvelope::eventId).containsExactlyElementsOf(allAfter);
        assertThat(wide.hasMore()).as("the window is exhausted inside one page").isFalse();
    }

    // ──────────────────────────────────────────────────────────────────
    // T3 — the loud failure
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T3: with idx_events_event_time dropped, readByTimeRange throws SQLite's"
            + " 'no such index' — the loud failure is the point of INDEXED BY")
    void plan_failsLoudlyWithoutTheIndex() {
        dbExecutor.writeCoordinator().submit(WritePriority.EVENT_PUBLISH, () -> {
            try (Statement stmt = dbExecutor.writeConnection().createStatement()) {
                stmt.executeUpdate("DROP INDEX " + INDEX_NAME);
            }
            return null;
        });

        assertThatThrownBy(() -> store.readByTimeRange(WINDOW_FROM, WINDOW_TO, 0L, MAX_COUNT))
                .isInstanceOf(RuntimeException.class)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("no such index: " + INDEX_NAME);
    }

    // ──────────────────────────────────────────────────────────────────
    // Harness
    // ──────────────────────────────────────────────────────────────────

    /** Leaves the {@code LIMIT ?} placeholder unbound (SQLite plans it as NULL → no limit). */
    private static final int UNBOUND = -1;

    /**
     * {@code EXPLAIN QUERY PLAN} of {@code sql} on the store's own read connection (the shipped
     * driver, the production PRAGMAs), the placeholders bound as the store binds them
     * (position, from, to, maxCount); returns the {@code detail} column of every plan row.
     */
    private List<String> explain(String sql, int maxCount) {
        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = dbExecutor.readConnections().get(0);
            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
                ps.setLong(1, 0L);
                ps.setLong(2, TimeConversion.toMicros(WINDOW_FROM));
                ps.setLong(3, TimeConversion.toMicros(WINDOW_TO));
                if (maxCount != UNBOUND) {
                    ps.setInt(4, maxCount);
                }
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

    private static EventDraft powerDraft(Instant eventTime, String value) {
        return new EventDraft(EventTypes.STATE_REPORTED, 1, eventTime,
                SubjectRef.entity(ENTITY), EventPriority.DIAGNOSTIC,
                EventOrigin.DEVICE_AUTONOMOUS,
                new StateReportedEvent("power_w", value, null, null, null), null, null);
    }
}
