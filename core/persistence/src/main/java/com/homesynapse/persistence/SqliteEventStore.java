/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.persistence;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed implementation of {@link EventPublisher} and {@link EventStore}
 * against the V001 {@code events} table (Doc 01 §4.2, §8.1).
 *
 * <p>{@code SqliteEventStore} is the production hand on the event log: it is the
 * sole write path (LTD-03 single-writer model) and the primary read path for
 * every subscriber, the REST API, and diagnostic tools. It implements both
 * interfaces against the same underlying store so that the causality contract
 * (Doc 01 §3.8) holds — an event returned from {@code publish} is readable via
 * {@code EventStore} on the very next query, because the WAL commit happens
 * before {@code publish} returns.</p>
 *
 * <p><strong>Thread model.</strong> Every JDBC call routes through one of two
 * executors owned by {@link DatabaseExecutor}:</p>
 * <ul>
 *   <li>{@link WriteCoordinator} — single platform thread executing every
 *       {@link #publish(EventDraft, CausalContext) publish} /
 *       {@link #publishRoot(EventDraft) publishRoot}. Writes are serialized and
 *       no {@code UNIQUE(subject_ref, subject_sequence)} race can occur.</li>
 *   <li>{@link ReadExecutor} — bounded pool of platform threads
 *       ({@code hs-read-*}) executing every query. Each read thread owns its
 *       own {@link Connection}, populated lazily from the read-connection list
 *       via round-robin through a {@link ThreadLocal}.</li>
 * </ul>
 *
 * <p>The {@code ThreadLocal} pattern is the AMD-26/AMD-27 mitigation for
 * sqlite-jdbc JNI carrier pinning: each JDBC call happens on the same platform
 * thread for the lifetime of the read executor, and each thread's connection is
 * confined to that thread. Virtual thread callers submit work and park until
 * the result comes back — they never touch a JDBC object directly.</p>
 *
 * <p><strong>Parameter validation.</strong> All 6 {@link EventStore} methods
 * validate their arguments <em>before</em> submitting work to the read executor.
 * This keeps the {@code IllegalArgumentException} contract visible on the
 * caller's thread rather than wrapped in a {@code RuntimeException} by the
 * executor's {@code Future.get()} unwrap.</p>
 *
 * <p><strong>Serialization boundary.</strong> Event payloads are JSON-encoded by
 * {@link EventPayloadCodec} (Jackson) and stored in the {@code payload} BLOB
 * column. Categories are stored in the {@code event_category} TEXT column as a
 * comma-separated list of {@link EventCategory#wireValue() wire values} — the
 * wire values contain only lowercase letters and underscores, so the comma
 * delimiter is unambiguous. On the decode path, an unknown event type or a
 * parse failure produces a {@link com.homesynapse.event.DegradedEvent} so the
 * read always succeeds (DECIDE-M2-06 / DECIDE-M2-07).</p>
 *
 * <p><strong>Chain hash.</strong> The {@code chain_hash} column is declared
 * but not yet populated — it is reserved for the future crypto milestone.
 * Inserts bind {@code NULL} for this column.</p>
 *
 * <p>Package-private — external modules construct and consume this store
 * through the higher-level {@code PersistenceLifecycle} facade, which returns
 * the public {@link EventPublisher} and {@link EventStore} interfaces only.</p>
 *
 * @see DatabaseExecutor
 * @see EventPayloadCodec
 * @see EventCategoryMapping
 * @see TimeConversion
 */
final class SqliteEventStore implements EventPublisher, EventStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteEventStore.class);

    /** Delimiter for the comma-separated {@code event_category} TEXT column. */
    private static final String CATEGORY_DELIMITER = ",";

    /**
     * INSERT statement for a new event row. The {@code global_position} column
     * is not listed — SQLite assigns it via AUTOINCREMENT and we retrieve the
     * generated rowid via {@link Statement#getGeneratedKeys()}.
     */
    private static final String INSERT_SQL = """
            INSERT INTO events (
                event_id, event_type, schema_version, ingest_time, event_time,
                subject_ref, subject_type, subject_sequence, priority, origin,
                actor_ref, correlation_id, causation_id, event_category, payload,
                chain_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Selects the current maximum {@code subject_sequence} for a subject.
     * Returns {@code null} if the subject has no events.
     */
    private static final String MAX_SUBJECT_SEQUENCE_SQL =
            "SELECT MAX(subject_sequence) FROM events WHERE subject_ref = ?";

    /** Projects every event column in the canonical envelope field order. */
    private static final String SELECT_COLS =
            "SELECT global_position, event_id, event_type, schema_version, "
                    + "ingest_time, event_time, subject_ref, subject_type, "
                    + "subject_sequence, priority, origin, actor_ref, "
                    + "correlation_id, causation_id, event_category, payload "
                    + "FROM events";

    private static final String SELECT_FROM_SQL =
            SELECT_COLS + " WHERE global_position > ? "
                    + "ORDER BY global_position ASC LIMIT ?";

    private static final String SELECT_BY_SUBJECT_SQL =
            SELECT_COLS + " WHERE subject_ref = ? AND subject_sequence > ? "
                    + "ORDER BY subject_sequence ASC LIMIT ?";

    private static final String SELECT_BY_CORRELATION_SQL =
            SELECT_COLS + " WHERE correlation_id = ? "
                    + "ORDER BY global_position ASC";

    private static final String SELECT_BY_TYPE_SQL =
            SELECT_COLS + " WHERE event_type = ? AND global_position > ? "
                    + "ORDER BY global_position ASC LIMIT ?";

    /**
     * Time range is evaluated against {@code COALESCE(event_time, ingest_time)}
     * to match the {@code idx_events_event_time} index key (Doc 01 §4.2).
     * Semantics are {@code [from, to)} — inclusive start, exclusive end.
     */
    private static final String SELECT_BY_TIME_RANGE_SQL =
            SELECT_COLS
                    + " WHERE global_position > ? "
                    + "AND COALESCE(event_time, ingest_time) >= ? "
                    + "AND COALESCE(event_time, ingest_time) < ? "
                    + "ORDER BY global_position ASC LIMIT ?";

    private static final String LATEST_POSITION_SQL =
            "SELECT COALESCE(MAX(global_position), 0) FROM events";

    private final DatabaseExecutor dbExecutor;
    private final EventPayloadCodec codec;
    private final Clock clock;

    /**
     * Round-robin index used to assign a read {@link Connection} to each
     * read-pool thread on first use. Incremented atomically from any read
     * thread. Because the read pool size matches the read-connection list
     * size, every thread ends up with a unique connection.
     */
    private final AtomicInteger readConnectionCursor = new AtomicInteger();

    /**
     * Thread-confined read connection — each {@code hs-read-*} thread is
     * assigned a single {@link Connection} on its first read and reuses it
     * thereafter for the lifetime of the store.
     */
    private final ThreadLocal<Connection> readConnection = new ThreadLocal<>();

    /**
     * Constructs a SQLite-backed event store over the given database executor
     * and payload codec.
     *
     * <p>The {@code registry} parameter is accepted for symmetry with the
     * design doc and for forward compatibility: at steady state the codec
     * already holds the registry reference, but a future refactor may move
     * category and event-type metadata lookups from the codec to the registry
     * and we want the constructor signature to be stable across that change.</p>
     *
     * @param dbExecutor the database executor providing write/read coordination
     *                   and the write connection; never {@code null} and must
     *                   be started
     * @param codec      the payload codec for JSON encode/decode; never {@code null}
     * @param registry   the event type registry (currently unused at the store
     *                   level — the codec already holds a reference); accepted
     *                   for API stability; never {@code null}
     * @param clock      the clock for {@code ingestTime} assignment; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    SqliteEventStore(
            DatabaseExecutor dbExecutor,
            EventPayloadCodec codec,
            EventTypeRegistry registry,
            Clock clock) {
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ──────────────────────────────────────────────────────────────────
    // EventPublisher
    // ──────────────────────────────────────────────────────────────────

    @Override
    public EventEnvelope publish(EventDraft draft, CausalContext cause)
            throws SequenceConflictException {
        Objects.requireNonNull(draft, "draft must not be null");
        Objects.requireNonNull(cause, "cause must not be null");

        EventId eventId = EventId.of(UlidFactory.generate(clock));
        return appendOnWriteThread(draft, cause, eventId);
    }

    @Override
    public EventEnvelope publishRoot(EventDraft draft)
            throws SequenceConflictException {
        Objects.requireNonNull(draft, "draft must not be null");

        Ulid newEventUlid = UlidFactory.generate(clock);
        CausalContext rootContext = CausalContext.root(newEventUlid);
        EventId eventId = EventId.of(newEventUlid);
        return appendOnWriteThread(draft, rootContext, eventId);
    }

    /**
     * Submits an append operation to the write coordinator and unwraps the
     * {@link SequenceConflictException} the coordinator wraps when a write
     * operation throws a checked exception.
     */
    private EventEnvelope appendOnWriteThread(
            EventDraft draft, CausalContext cause, EventId eventId)
            throws SequenceConflictException {
        Callable<EventEnvelope> op = () -> doAppend(draft, cause, eventId);
        try {
            return dbExecutor.writeCoordinator().submit(WritePriority.EVENT_PUBLISH, op);
        } catch (RuntimeException e) {
            // The WriteCoordinator contract wraps checked exceptions in a
            // RuntimeException whose cause is the checked exception. Unwrap
            // SequenceConflictException so callers see it via the interface's
            // declared `throws`.
            Throwable cause2 = e.getCause();
            if (cause2 instanceof SequenceConflictException sce) {
                throw sce;
            }
            throw e;
        }
    }

    /**
     * Executes the actual row insert on the write thread. All JDBC work
     * happens on the single platform write thread owned by the write
     * coordinator — there is no concurrent access to the connection and no
     * sequence-number race. A {@code UNIQUE(subject_ref, subject_sequence)}
     * violation is translated to {@link SequenceConflictException}.
     */
    private EventEnvelope doAppend(
            EventDraft draft, CausalContext causalContext, EventId eventId)
            throws SQLException, IOException, SequenceConflictException {
        Connection conn = dbExecutor.writeConnection();

        SubjectRef subject = draft.subjectRef();
        long nextSequence = nextSubjectSequence(conn, subject);

        Instant ingestTime = clock.instant();
        List<EventCategory> categories = EventCategoryMapping.categoriesFor(draft.eventType());
        byte[] payloadBytes = codec.encode(draft.payload());

        try (PreparedStatement ps = conn.prepareStatement(
                INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setBytes(1, eventId.value().toBytes());
            ps.setString(2, draft.eventType());
            ps.setInt(3, draft.schemaVersion());
            ps.setLong(4, TimeConversion.toMicros(ingestTime));
            Long eventTimeMicros = TimeConversion.toMicrosOrNull(draft.eventTime());
            if (eventTimeMicros == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setLong(5, eventTimeMicros);
            }
            ps.setBytes(6, subject.id().toBytes());
            ps.setString(7, subject.type().name());
            ps.setLong(8, nextSequence);
            ps.setString(9, draft.priority().name());
            ps.setString(10, draft.origin().name());
            if (draft.actorRef() == null) {
                ps.setNull(11, Types.BLOB);
            } else {
                ps.setBytes(11, draft.actorRef().toBytes());
            }
            ps.setBytes(12, causalContext.correlationId().toBytes());
            if (causalContext.causationId() == null) {
                ps.setNull(13, Types.BLOB);
            } else {
                ps.setBytes(13, causalContext.causationId().toBytes());
            }
            ps.setString(14, encodeCategories(categories));
            ps.setBytes(15, payloadBytes);
            ps.setNull(16, Types.BLOB); // chain_hash deferred to crypto milestone

            try {
                ps.executeUpdate();
            } catch (SQLException e) {
                if (isUniqueConstraintViolation(e)) {
                    throw new SequenceConflictException(subject, nextSequence);
                }
                throw e;
            }

            long globalPosition;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException(
                            "INSERT INTO events did not return a generated key");
                }
                globalPosition = keys.getLong(1);
            }

            return new EventEnvelope(
                    eventId,
                    draft.eventType(),
                    draft.schemaVersion(),
                    ingestTime,
                    draft.eventTime(),
                    subject,
                    nextSequence,
                    globalPosition,
                    draft.priority(),
                    draft.origin(),
                    categories,
                    causalContext,
                    draft.actorRef(),
                    draft.payload()
            );
        }
    }

    private static long nextSubjectSequence(Connection conn, SubjectRef subject)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(MAX_SUBJECT_SEQUENCE_SQL)) {
            ps.setBytes(1, subject.id().toBytes());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long current = rs.getLong(1);
                    if (rs.wasNull()) {
                        return 1L;
                    }
                    return current + 1L;
                }
                return 1L;
            }
        }
    }

    /**
     * Detects a SQLite {@code UNIQUE} constraint failure. The sqlite-jdbc
     * driver reports SQLITE_CONSTRAINT as SQLSTATE {@code 23000} and extended
     * result code {@code 19} (or {@code 2067} for UNIQUE specifically). We
     * test both the SQLSTATE and the substring of the message so the check
     * is robust across driver versions.
     */
    private static boolean isUniqueConstraintViolation(SQLException e) {
        String sqlState = e.getSQLState();
        if (sqlState != null && sqlState.startsWith("23")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("unique constraint");
    }

    // ──────────────────────────────────────────────────────────────────
    // EventStore
    // ──────────────────────────────────────────────────────────────────

    @Override
    public EventPage readFrom(long afterPosition, int maxCount) {
        validatePagination(afterPosition, maxCount);

        List<EventEnvelope> events = executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_FROM_SQL)) {
                ps.setLong(1, afterPosition);
                ps.setInt(2, maxCount);
                return readRows(ps);
            }
        });

        boolean hasMore = events.size() == maxCount
                ? hasMoreAfterGlobalPosition(lastGlobalPosition(events, afterPosition))
                : false;
        long nextPos = events.isEmpty()
                ? afterPosition
                : events.get(events.size() - 1).globalPosition();
        return new EventPage(events, nextPos, hasMore);
    }

    @Override
    public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
        if (subject == null) {
            throw new IllegalArgumentException("subject must not be null");
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException(
                    "afterSequence must be >= 0, got " + afterSequence);
        }
        if (maxCount < 1) {
            throw new IllegalArgumentException(
                    "maxCount must be >= 1, got " + maxCount);
        }

        List<EventEnvelope> events = executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_SUBJECT_SQL)) {
                ps.setBytes(1, subject.id().toBytes());
                ps.setLong(2, afterSequence);
                ps.setInt(3, maxCount);
                return readRows(ps);
            }
        });

        boolean hasMore = events.size() == maxCount
                && hasMoreForSubjectAfterSequence(subject,
                        events.get(events.size() - 1).subjectSequence());
        long nextPos = events.isEmpty()
                ? afterSequence
                : events.get(events.size() - 1).subjectSequence();
        return new EventPage(events, nextPos, hasMore);
    }

    @Override
    public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId must not be null");
        }

        List<EventEnvelope> events = executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_CORRELATION_SQL)) {
                ps.setBytes(1, correlationId.toBytes());
                return readRows(ps);
            }
        });
        return List.copyOf(events);
    }

    @Override
    public EventPage readByType(String eventType, long afterPosition, int maxCount) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        validatePagination(afterPosition, maxCount);

        List<EventEnvelope> events = executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_TYPE_SQL)) {
                ps.setString(1, eventType);
                ps.setLong(2, afterPosition);
                ps.setInt(3, maxCount);
                return readRows(ps);
            }
        });

        boolean hasMore = events.size() == maxCount
                && hasMoreForTypeAfterPosition(eventType,
                        events.get(events.size() - 1).globalPosition());
        long nextPos = events.isEmpty()
                ? afterPosition
                : events.get(events.size() - 1).globalPosition();
        return new EventPage(events, nextPos, hasMore);
    }

    @Override
    public EventPage readByTimeRange(
            Instant from, Instant to, long afterPosition, int maxCount) {
        if (from == null) {
            throw new IllegalArgumentException("from must not be null");
        }
        if (to == null) {
            throw new IllegalArgumentException("to must not be null");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "from must be before to: from=" + from + ", to=" + to);
        }
        validatePagination(afterPosition, maxCount);

        long fromMicros = TimeConversion.toMicros(from);
        long toMicros = TimeConversion.toMicros(to);

        List<EventEnvelope> events = executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_TIME_RANGE_SQL)) {
                ps.setLong(1, afterPosition);
                ps.setLong(2, fromMicros);
                ps.setLong(3, toMicros);
                ps.setInt(4, maxCount);
                return readRows(ps);
            }
        });

        boolean hasMore = events.size() == maxCount
                && hasMoreForTimeRangeAfterPosition(
                        fromMicros, toMicros,
                        events.get(events.size() - 1).globalPosition());
        long nextPos = events.isEmpty()
                ? afterPosition
                : events.get(events.size() - 1).globalPosition();
        return new EventPage(events, nextPos, hasMore);
    }

    @Override
    public long latestPosition() {
        return executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(LATEST_POSITION_SQL);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // hasMore helpers — run a compact "any row beyond" probe so that the
    // page's hasMore flag is authoritative when the page filled up
    // ──────────────────────────────────────────────────────────────────

    private boolean hasMoreAfterGlobalPosition(long lastGlobal) {
        return executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM events WHERE global_position > ? LIMIT 1")) {
                ps.setLong(1, lastGlobal);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private boolean hasMoreForSubjectAfterSequence(SubjectRef subject, long lastSequence) {
        return executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM events "
                            + "WHERE subject_ref = ? AND subject_sequence > ? LIMIT 1")) {
                ps.setBytes(1, subject.id().toBytes());
                ps.setLong(2, lastSequence);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private boolean hasMoreForTypeAfterPosition(String eventType, long lastGlobal) {
        return executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM events "
                            + "WHERE event_type = ? AND global_position > ? LIMIT 1")) {
                ps.setString(1, eventType);
                ps.setLong(2, lastGlobal);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private boolean hasMoreForTimeRangeAfterPosition(
            long fromMicros, long toMicros, long lastGlobal) {
        return executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM events "
                            + "WHERE global_position > ? "
                            + "AND COALESCE(event_time, ingest_time) >= ? "
                            + "AND COALESCE(event_time, ingest_time) < ? LIMIT 1")) {
                ps.setLong(1, lastGlobal);
                ps.setLong(2, fromMicros);
                ps.setLong(3, toMicros);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    private static long lastGlobalPosition(List<EventEnvelope> events, long fallback) {
        return events.isEmpty() ? fallback : events.get(events.size() - 1).globalPosition();
    }

    // ──────────────────────────────────────────────────────────────────
    // Row → envelope mapping
    // ──────────────────────────────────────────────────────────────────

    private List<EventEnvelope> readRows(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<EventEnvelope> out = new ArrayList<>();
            while (rs.next()) {
                out.add(fromRow(rs));
            }
            return out;
        }
    }

    private EventEnvelope fromRow(ResultSet rs) throws SQLException {
        long globalPosition = rs.getLong("global_position");
        EventId eventId = EventId.of(Ulid.fromBytes(rs.getBytes("event_id")));
        String eventType = rs.getString("event_type");
        int schemaVersion = rs.getInt("schema_version");
        Instant ingestTime = TimeConversion.fromMicros(rs.getLong("ingest_time"));

        long eventTimeRaw = rs.getLong("event_time");
        Instant eventTime = rs.wasNull() ? null : TimeConversion.fromMicros(eventTimeRaw);

        Ulid subjectUlid = Ulid.fromBytes(rs.getBytes("subject_ref"));
        SubjectType subjectType = SubjectType.valueOf(rs.getString("subject_type"));
        SubjectRef subject = new SubjectRef(subjectUlid, subjectType);

        long subjectSequence = rs.getLong("subject_sequence");
        EventPriority priority = EventPriority.valueOf(rs.getString("priority"));
        EventOrigin origin = EventOrigin.valueOf(rs.getString("origin"));

        byte[] actorBytes = rs.getBytes("actor_ref");
        Ulid actorRef = actorBytes == null ? null : Ulid.fromBytes(actorBytes);

        Ulid correlationId = Ulid.fromBytes(rs.getBytes("correlation_id"));
        byte[] causationBytes = rs.getBytes("causation_id");
        Ulid causationId = causationBytes == null ? null : Ulid.fromBytes(causationBytes);
        CausalContext causalContext = causationId == null
                ? CausalContext.root(correlationId)
                : CausalContext.chain(correlationId, causationId);

        List<EventCategory> categories = decodeCategories(rs.getString("event_category"));

        byte[] payloadBytes = rs.getBytes("payload");
        DomainEvent payload = codec.decode(eventType, schemaVersion, payloadBytes);

        return new EventEnvelope(
                eventId,
                eventType,
                schemaVersion,
                ingestTime,
                eventTime,
                subject,
                subjectSequence,
                globalPosition,
                priority,
                origin,
                categories,
                causalContext,
                actorRef,
                payload
        );
    }

    // ──────────────────────────────────────────────────────────────────
    // Category codec (comma-separated wire values)
    // ──────────────────────────────────────────────────────────────────

    private static String encodeCategories(List<EventCategory> categories) {
        StringJoiner joiner = new StringJoiner(CATEGORY_DELIMITER);
        for (EventCategory c : categories) {
            joiner.add(c.wireValue());
        }
        return joiner.toString();
    }

    private static List<EventCategory> decodeCategories(String raw) {
        if (raw == null || raw.isEmpty()) {
            // The envelope constructor rejects empty categories — a stored row
            // with an empty value would indicate corruption. Fall back to
            // SYSTEM so the read still succeeds (matches DegradedEvent posture).
            return List.of(EventCategory.SYSTEM);
        }
        String[] parts = raw.split(CATEGORY_DELIMITER);
        List<EventCategory> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            out.add(EventCategory.fromWireValue(part));
        }
        return out.isEmpty() ? List.of(EventCategory.SYSTEM) : out;
    }

    // ──────────────────────────────────────────────────────────────────
    // Read-thread machinery
    // ──────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface JdbcCall<T> {
        T run(Connection conn) throws SQLException;
    }

    /**
     * Runs a JDBC read on a pool thread via {@link ReadExecutor#execute}, with
     * a {@link ThreadLocal}-owned {@link Connection} drawn from the
     * round-robin cursor on first use.
     */
    private <T> T executeRead(JdbcCall<T> call) {
        return dbExecutor.readExecutor().execute(() -> {
            Connection conn = readConnection.get();
            if (conn == null) {
                List<Connection> pool = dbExecutor.readConnections();
                int index = readConnectionCursor.getAndIncrement() % pool.size();
                if (index < 0) {
                    // Integer overflow — rare, but keep the index in range.
                    index = Math.abs(index % pool.size());
                }
                conn = pool.get(index);
                readConnection.set(conn);
                LOG.debug("Bound read connection #{} to thread {}",
                        index, Thread.currentThread().getName());
            }
            return call.run(conn);
        });
    }

    // ──────────────────────────────────────────────────────────────────
    // Parameter validation
    // ──────────────────────────────────────────────────────────────────

    private static void validatePagination(long afterPosition, int maxCount) {
        if (afterPosition < 0) {
            throw new IllegalArgumentException(
                    "afterPosition must be >= 0, got " + afterPosition);
        }
        if (maxCount < 1) {
            throw new IllegalArgumentException(
                    "maxCount must be >= 1, got " + maxCount);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test-support utilities (package-private)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Deletes every row from the {@code events} table. Used by contract tests'
     * {@code resetStore()} implementation and never by production code. Runs
     * on the write thread for serialization.
     */
    void truncateForTesting() {
        dbExecutor.writeCoordinator().submit(WritePriority.EVENT_PUBLISH, () -> {
            Connection conn = dbExecutor.writeConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM events");
                // Reset the AUTOINCREMENT counter so global_position restarts
                // at 1 — test assertions compare positions across isolated
                // runs and expect the counter to be deterministic.
                try {
                    stmt.executeUpdate("DELETE FROM sqlite_sequence WHERE name = 'events'");
                } catch (SQLException ignored) {
                    // sqlite_sequence only exists if an AUTOINCREMENT table
                    // has ever had a row. If the test deleted rows before
                    // any were inserted, the row won't exist — not an error.
                }
            }
            // Clear the store's per-thread ThreadLocal so that any future
            // read against a transient reset-wrapper connection is re-bound.
            // Note: this runs on the write thread, so only the write-thread's
            // read-connection slot could be affected — in practice none,
            // because write-thread never reads. Left here for defensive
            // cleanup consistency.
            return null;
        });
    }

    /**
     * Clears the current thread's {@link ThreadLocal} read-connection binding.
     * Exposed for the rare test scenario where the read executor is restarted
     * mid-test and the stale binding would point at a closed connection.
     * Not used by production code.
     */
    void clearThreadLocalForTesting() {
        readConnection.remove();
    }
}
