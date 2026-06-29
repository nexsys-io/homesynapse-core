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
import com.homesynapse.platform.identity.HomeId;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * {@code NOT NULL DEFAULT x'00...00'} (AMD-37). Inserts bind a 32-byte
 * zero vector. Actual hash computation is deferred to the crypto
 * milestone.</p>
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
     * Separator in the {@code dek_ref} TEXT column's {@code scope_id:key_version}
     * form (Doc 15 §4.1). Parsed with a last-colon split on read so a scope-id
     * could in principle contain a colon (the current scope-ids do not);
     * pinned by {@code AtRestEncryptionWritePathTest}.
     */
    private static final String DEK_REF_DELIMITER = ":";

    /**
     * Persistence-side scope-id constant mirroring config's
     * {@code EncryptionScope.PRESENCE_PERSONAL}. The persistence module must
     * not name a {@code config} type (zero-new-edge, Doc 15 §3.8), so the one
     * MVP event-category → scope edge is mirrored here as a {@code String} —
     * the same discipline that keeps {@link EncryptedPayload} distinct from
     * config's {@code ScopeCipherResult}. Pinned by
     * {@code AtRestEncryptionWritePathTest}.
     */
    private static final String PRESENCE_PERSONAL_SCOPE_ID = "presence_personal";

    /**
     * AB-4 / F1 — the at-rest AEAD envelope format version (Doc 15 §4.1,
     * AMD-94). {@code v1} = AES-256-GCM, 96-bit per-scope counter nonce,
     * per-scope DEK — the M6.3 envelope. For an encrypted row the byte is the
     * FIRST byte of the stored {@code payload} BLOB (prepended on write,
     * strictly parsed on read) AND is bound as GCM AAD (the
     * {@link PayloadCipher#encrypt}/{@link PayloadCipher#decrypt} {@code aad}),
     * so a tampered or stripped version byte fails closed
     * ({@link PayloadDecryptionException.FailureKind#UNKNOWN_ENVELOPE_VERSION}
     * on the strict parse, or {@code GCM_AUTH_FAILED} via the auth tag) — never
     * an implicit-{@code v1} fallback. Zero-DDL: the byte rides the existing
     * {@code payload} BLOB; there is no {@code envelope_version} column.
     *
     * <p>The AAD tamper-evidence is real now (the tag covers the byte);
     * <em>chain-coverage</em> tamper-evidence stays inert until chain
     * activation ({@code chain_hash} is the 32-byte ZERO vector today,
     * Doc 15 §2.3) — no over-claim here.</p>
     */
    private static final byte ENVELOPE_VERSION_V1 = 1;

    /**
     * 32-byte zero vector for the {@code chain_hash} column (AMD-37).
     * The chain hash column is {@code NOT NULL DEFAULT x'00...00'} in V001;
     * actual hash computation is deferred to the crypto milestone.
     */
    private static final byte[] ZERO_HASH = new byte[32];

    /**
     * INSERT statement for a new event row. The {@code global_position} column
     * is not listed — SQLite assigns it via AUTOINCREMENT and we retrieve the
     * generated rowid via {@link Statement#getGeneratedKeys()}.
     *
     * <p>Binds all 26 data columns in schema column order (AMD-34 through
     * AMD-37, Tier 2 addendum, and the V005 at-rest-encryption columns
     * {@code payload_iv} / {@code dek_ref} appended last). Reservation columns
     * ({@code batch_id}, {@code external_ref}, {@code intent_kind},
     * {@code logical_time}, {@code node_id}) are bound to their default
     * values until behavioral wiring in M3. {@code payload_iv} / {@code dek_ref}
     * are bound non-null only for encrypted sensitive-PII scopes (M6.3).</p>
     */
    private static final String INSERT_SQL = """
            INSERT INTO events (
                event_id, home_id, event_type, schema_version,
                ingest_time, event_time, subject_ref, subject_type,
                subject_sequence, priority, origin, actor_ref,
                idempotency_key, correlation_id, causation_id, event_category,
                payload_size, batch_id, external_ref, intent_kind,
                logical_time, node_id, payload, chain_hash,
                payload_iv, dek_ref
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    + "correlation_id, causation_id, event_category, payload, "
                    + "payload_iv, dek_ref "
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
    private final HomeId homeId;

    /**
     * At-rest payload cipher (Doc 15 §3.8 / M6.3). Nullable: {@code null} is
     * the M6.2 production state (no crypto wired) and every test that does not
     * exercise encryption — the store then writes plaintext for all scopes,
     * and {@link #encryptedScopes} is empty so no event is treated as
     * encryptable. Injected through {@link PersistenceFactory#start}.
     */
    private final PayloadCipher payloadCipher;

    /**
     * The enabled at-rest encryption scope-ids. Empty ⇒ at-rest encryption is
     * disabled (the M6.2 state / no-crypto tests). Non-empty ⇒ events whose
     * resolved scope-id is a member are encrypted before the INSERT. The
     * production wiring populates this iff a cipher is present
     * (cipher-presence is the M6.3 master switch for {@code at_rest_enabled},
     * since live {@code ConfigModel} wiring is app-bootstrap scope).
     */
    private final Set<String> encryptedScopes;

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
     * @param homeId     the home identity for this installation (AMD-34); written
     *                   to the {@code home_id} column of every persisted event;
     *                   never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    SqliteEventStore(
            DatabaseExecutor dbExecutor,
            EventPayloadCodec codec,
            EventTypeRegistry registry,
            Clock clock,
            HomeId homeId) {
        this(dbExecutor, codec, registry, clock, homeId, null, Set.of());
    }

    /**
     * Constructs a SQLite-backed event store with the M6.3 at-rest
     * payload-encryption gate wired (Doc 15 §3.4 / §3.8).
     *
     * @param dbExecutor      the database executor; never {@code null}, must
     *                        be started
     * @param codec           the payload codec; never {@code null}
     * @param registry        the event type registry; never {@code null}
     * @param clock           the clock for {@code ingestTime}; never {@code null}
     * @param homeId          the home identity (AMD-34); never {@code null}
     * @param payloadCipher   the at-rest cipher (Doc 15 §3.8 seam), or
     *                        {@code null} when at-rest payload encryption is
     *                        unavailable (the M6.2 state). When {@code null},
     *                        {@code encryptedScopes} MUST be empty in the
     *                        production wiring; a non-empty set with a
     *                        {@code null} cipher is the fail-closed
     *                        misconfiguration that throws at write time.
     * @param encryptedScopes the enabled encryption scope-ids; never
     *                        {@code null}, may be empty (⇒ at-rest disabled)
     * @throws NullPointerException if any non-cipher argument is {@code null}
     */
    SqliteEventStore(
            DatabaseExecutor dbExecutor,
            EventPayloadCodec codec,
            EventTypeRegistry registry,
            Clock clock,
            HomeId homeId,
            PayloadCipher payloadCipher,
            Set<String> encryptedScopes) {
        this.dbExecutor = Objects.requireNonNull(dbExecutor, "dbExecutor must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.homeId = Objects.requireNonNull(homeId, "homeId must not be null");
        this.payloadCipher = payloadCipher; // nullable by design (M6.2 state)
        this.encryptedScopes = Set.copyOf(
                Objects.requireNonNull(encryptedScopes, "encryptedScopes must not be null"));
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

        // At-rest encryption gate (Doc 15 §3.4, M6.3). Resolve the event's
        // encryption scope from its categories; if that scope is enabled, the
        // sensitive-PII payload is encrypted here — on the single write thread,
        // before the INSERT. Doc 15 §3.2 prefers the publishing virtual thread,
        // but the sensitive-PII scopes are low-volume and the cleanest seam is
        // at this serialize point; the OR-M6-NONCE counter durability (the
        // correctness gate) is owned inside the injected cipher and is
        // independent of thread placement. Non-sensitive events stay
        // plaintext-at-rest (NULL payload_iv/dek_ref) — today's behavior.
        String scopeId = encryptionScopeId(categories);
        byte[] storedBytes;
        byte[] payloadIv;
        String dekRef;
        if (scopeId != null && encryptedScopes.contains(scopeId)) {
            if (payloadCipher == null) {
                // Fail-closed: no silent plaintext write of a sensitive-PII
                // scope when at-rest encryption is enabled (Doc 15 §6 / LTD-14).
                throw new IllegalStateException(
                        "at-rest encryption enabled for scope " + scopeId
                                + " but no PayloadCipher is wired");
            }
            // F1 (AB-4): bind the envelope version byte as GCM AAD (downgrade
            // resistance) AND prepend it to the stored envelope (chain-coverable
            // once the chain is live). The cipher binds the AAD into the tag; the
            // version byte itself is framed here — this is the single assemble
            // site, matched by the single parse site in decryptStoredPayload.
            byte[] aad = {ENVELOPE_VERSION_V1};
            EncryptedPayload encrypted =
                    payloadCipher.encrypt(scopeId, payloadBytes, aad);
            storedBytes = prependEnvelopeVersion(ENVELOPE_VERSION_V1,
                    encrypted.ciphertext());
            payloadIv = encrypted.iv();
            dekRef = scopeId + DEK_REF_DELIMITER + encrypted.keyVersion();
        } else {
            storedBytes = payloadBytes;
            payloadIv = null;
            dekRef = null;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            // Bind positions 1–26 match the schema column order (minus
            // global_position): 1–24 V001/Tier-2, 25–26 the V005 encryption columns.
            ps.setBytes(1, eventId.value().toBytes());                  // event_id
            ps.setBytes(2, homeId.value().toBytes());                   // home_id (AMD-34)
            ps.setString(3, draft.eventType());                         // event_type
            ps.setInt(4, draft.schemaVersion());                        // schema_version
            ps.setLong(5, TimeConversion.toMicros(ingestTime));         // ingest_time
            Long eventTimeMicros = TimeConversion.toMicrosOrNull(draft.eventTime());
            if (eventTimeMicros == null) {
                ps.setNull(6, Types.INTEGER);                           // event_time
            } else {
                ps.setLong(6, eventTimeMicros);
            }
            ps.setBytes(7, subject.id().toBytes());                     // subject_ref
            ps.setString(8, subject.type().name());                     // subject_type
            ps.setLong(9, nextSequence);                                // subject_sequence
            ps.setString(10, draft.priority().name());                  // priority
            ps.setString(11, draft.origin().name());                    // origin
            if (draft.actorRef() == null) {
                ps.setNull(12, Types.BLOB);                             // actor_ref
            } else {
                ps.setBytes(12, draft.actorRef().toBytes());
            }
            if (draft.idempotencyKey() == null) {
                ps.setNull(13, Types.VARCHAR);                          // idempotency_key (AMD-35)
            } else {
                ps.setString(13, draft.idempotencyKey());
            }
            ps.setBytes(14, causalContext.correlationId().toBytes());    // correlation_id
            if (causalContext.causationId() == null) {
                ps.setNull(15, Types.BLOB);                             // causation_id
            } else {
                ps.setBytes(15, causalContext.causationId().toBytes());
            }
            ps.setString(16, encodeCategories(categories));             // event_category
            ps.setInt(17, storedBytes.length);                          // payload_size (Tier 2 — size of the stored BLOB: ciphertext when encrypted)
            ps.setNull(18, Types.BLOB);                                 // batch_id (reserved)
            ps.setNull(19, Types.VARCHAR);                              // external_ref (reserved)
            ps.setString(20, "UNSPECIFIED");                            // intent_kind (reserved)
            ps.setLong(21, 0L);                                         // logical_time (reserved)
            ps.setInt(22, 0);                                           // node_id (reserved)
            ps.setBytes(23, storedBytes);                               // payload (ciphertext for encrypted scopes, else plaintext JSON)
            ps.setBytes(24, ZERO_HASH);                                 // chain_hash (AMD-37)
            if (payloadIv == null) {
                ps.setNull(25, Types.BLOB);                             // payload_iv (V005) — NULL when unencrypted
            } else {
                ps.setBytes(25, payloadIv);
            }
            if (dekRef == null) {
                ps.setNull(26, Types.VARCHAR);                          // dek_ref (V005) — NULL when unencrypted
            } else {
                ps.setString(26, dekRef);
            }

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

        byte[] storedBytes = rs.getBytes("payload");
        String dekRef = rs.getString("dek_ref");
        byte[] payloadBytes = (dekRef == null)
                ? storedBytes                       // plaintext-at-rest (today's path)
                : decryptStoredPayload(globalPosition, dekRef,
                        rs.getBytes("payload_iv"), storedBytes);
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
    // At-rest encryption scope resolution + read-path decryption (M6.3)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Resolves an event's at-rest encryption scope-id from its consent-scope
     * categories. Persistence-side mirror of the canonical mapping owned by
     * config's {@code EncryptionScope} (Doc 15 §3.4): at MVP only the
     * {@link EventCategory#PRESENCE} category resolves, to
     * {@code "presence_personal"}; the {@code "identity"} scope has no core
     * event type yet (reserved for future person-linked identity records).
     *
     * <p>Mirrored as a {@code String} rather than imported from {@code config}
     * so persistence gains no {@code config} module edge (Doc 15 §3.8) — the
     * same boundary discipline that keeps {@link EncryptedPayload} distinct
     * from config's {@code ScopeCipherResult}. The mirror is pinned by
     * {@code AtRestEncryptionWritePathTest}.</p>
     *
     * @return the scope-id to encrypt under, or {@code null} for a
     *         plaintext-at-rest event
     */
    private static String encryptionScopeId(List<EventCategory> categories) {
        return categories.contains(EventCategory.PRESENCE)
                ? PRESENCE_PERSONAL_SCOPE_ID
                : null;
    }

    /**
     * Frames the at-rest AEAD envelope (F1, AB-4): the 1-byte version
     * discriminator followed by the GCM ciphertext. The single assemble site,
     * matched by the single parse in {@link #decryptStoredPayload}.
     */
    private static byte[] prependEnvelopeVersion(byte version, byte[] ciphertext) {
        byte[] envelope = new byte[ciphertext.length + 1];
        envelope[0] = version;
        System.arraycopy(ciphertext, 0, envelope, 1, ciphertext.length);
        return envelope;
    }

    /**
     * Decrypts a stored ciphertext payload using the injected cipher,
     * parsing {@code scope_id:key_version} from {@code dek_ref} with a
     * last-colon split (Doc 15 §4.1).
     *
     * <p><strong>Fail-closed read contract (AB-2 / OR-RF-DECRYPT).</strong> Any
     * decrypt failure throws a typed {@link PayloadDecryptionException} carrying
     * the {@code globalPosition} (and the {@code scopeId}/{@code keyVersion} when
     * parseable) and a {@link PayloadDecryptionException.FailureKind}. Because
     * {@link #readRows} has no per-row catch, the exception aborts the
     * <em>entire read batch / replay segment</em> loudly — this is the intended
     * MVP failure domain (A3): a lost or corrupt root key making the store
     * unreadable is a visible failure, never a silent plaintext fallback
     * (Doc 15 §6) and never a quiet skip. The {@code IllegalArgumentException}
     * (CASE-a, key absent/destroyed) and {@code IllegalStateException} (CASE-b,
     * GCM authentication failure) raised by {@link PayloadCipher#decrypt} are
     * classified here so callers can distinguish an intended crypto-shred from
     * possible tampering.</p>
     *
     * <p><strong>Future degrade seam (design-only — NOT wired; F4-gated).</strong>
     * A later milestone may map a CASE-a failure ({@code KEY_ABSENT_OR_DESTROYED})
     * to a {@link com.homesynapse.event.DegradedEvent} when the row's
     * {@code chain_hash} validates — i.e. an intended crypto-shred whose tombstone
     * the chain proves — via a {@code (scope, key_version)}-keyed cause lookup plus
     * a chain-validity check, surfaced through a new additive {@code failureReason}
     * on {@code DegradedEvent}. That degrade behaviour and the chain-validity check
     * MUST stay disabled until {@code chain_hash} computation and mandatory
     * startup verification are live ({@code chain_hash} is the 32-byte ZERO vector
     * today). CASE-b ({@code GCM_AUTH_FAILED}) never degrades — masking it would
     * hide tampering. The MVP fail-closed half below needs no chain. A companion
     * boot invariant (R-α REC-235) rides AB-4 + the backup/restore WU: refuse to
     * encrypt in a scope until a fresh DEK is installed or the persisted counter
     * is proven ≥ all prior nonces. {@code DegradedEvent} is unchanged here.</p>
     *
     * @throws PayloadDecryptionException always, on any decrypt failure — see the
     *         contract above; the failure domain is the whole read batch
     */
    private byte[] decryptStoredPayload(
            long globalPosition, String dekRef, byte[] payloadIv, byte[] storedEnvelope) {
        if (payloadCipher == null) {
            throw new PayloadDecryptionException(
                    PayloadDecryptionException.FailureKind.NO_CIPHER_WIRED,
                    globalPosition, null, null,
                    "event at global_position " + globalPosition
                            + " is encrypted (dek_ref=" + dekRef + ") but no"
                            + " PayloadCipher is wired to decrypt it; restore the"
                            + " at-rest root key before reading this store — no silent"
                            + " plaintext fallback (Doc 15 §6)");
        }
        int split = dekRef.lastIndexOf(DEK_REF_DELIMITER);
        if (split <= 0 || split == dekRef.length() - 1) {
            throw new PayloadDecryptionException(
                    PayloadDecryptionException.FailureKind.MALFORMED_DEK_REF,
                    globalPosition, null, null,
                    "malformed dek_ref '" + dekRef + "' at global_position "
                            + globalPosition + "; expected scope_id:key_version");
        }
        String scopeId = dekRef.substring(0, split);
        int keyVersion;
        try {
            keyVersion = Integer.parseInt(dekRef.substring(split + 1));
        } catch (NumberFormatException e) {
            throw new PayloadDecryptionException(
                    PayloadDecryptionException.FailureKind.MALFORMED_DEK_REF,
                    globalPosition, scopeId, null,
                    "malformed dek_ref '" + dekRef + "' at global_position "
                            + globalPosition + "; key_version is not an integer", e);
        }
        // F1 (AB-4): strict envelope-version parse — never implicit-v1. An
        // empty envelope or an unrecognized leading byte is a hard, fail-closed
        // decrypt failure (the byte is also GCM-AAD-bound below, so a tampered
        // byte that somehow matched v1 would still fail the auth tag).
        if (storedEnvelope.length < 1) {
            throw new PayloadDecryptionException(
                    PayloadDecryptionException.FailureKind.UNKNOWN_ENVELOPE_VERSION,
                    globalPosition, scopeId, keyVersion,
                    "empty at-rest envelope at global_position " + globalPosition
                            + " (scope=" + scopeId + ", key_version=" + keyVersion
                            + "); expected a leading v1 version byte — no implicit-v1"
                            + " fallback (Doc 15 §4.1, AB-4 F1)");
        }
        byte version = storedEnvelope[0];
        if (version != ENVELOPE_VERSION_V1) {
            throw new PayloadDecryptionException(
                    PayloadDecryptionException.FailureKind.UNKNOWN_ENVELOPE_VERSION,
                    globalPosition, scopeId, keyVersion,
                    "unrecognized at-rest envelope version 0x"
                            + Integer.toHexString(version & 0xFF) + " at global_position "
                            + globalPosition + " (scope=" + scopeId + ", key_version="
                            + keyVersion + "); this build supports only v1 (AES-256-GCM)"
                            + " — an unknown or stripped version byte is a hard decrypt"
                            + " failure, never implicit-v1 (Doc 15 §4.1, AB-4 F1)");
        }
        byte[] aad = {version};
        byte[] ciphertext = Arrays.copyOfRange(storedEnvelope, 1, storedEnvelope.length);
        try {
            return payloadCipher.decrypt(scopeId, keyVersion, ciphertext, payloadIv, aad);
        } catch (IllegalArgumentException e) {
            // CASE-a — the (scope, key_version) key is absent or destroyed
            // (crypto-shred, Doc 15 §3.6): the ciphertext is permanently
            // unreadable. The concrete scope-key store path + required read
            // perms live config-side (the key manager logs them, INV-HO-04);
            // persistence names the scope/version/position it could not read.
            throw new PayloadDecryptionException(
                    PayloadDecryptionException.FailureKind.KEY_ABSENT_OR_DESTROYED,
                    globalPosition, scopeId, keyVersion,
                    "decryption key (scope=" + scopeId + ", key_version=" + keyVersion
                            + ") for the event at global_position " + globalPosition
                            + " is absent or destroyed; the at-rest root key is"
                            + " unavailable — restore it and ensure read access to the"
                            + " scope-key store in the config directory to read this"
                            + " store (INV-HO-04, Doc 15 §6)", e);
        } catch (IllegalStateException e) {
            // CASE-b — GCM authentication failed: corrupt or tampered ciphertext.
            throw new PayloadDecryptionException(
                    PayloadDecryptionException.FailureKind.GCM_AUTH_FAILED,
                    globalPosition, scopeId, keyVersion,
                    "GCM authentication failed decrypting the event at global_position "
                            + globalPosition + " (scope=" + scopeId + ", key_version="
                            + keyVersion + "); the ciphertext or its key may be corrupt"
                            + " or tampered — no silent plaintext fallback (Doc 15 §6)", e);
        }
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
