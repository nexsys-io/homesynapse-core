-- HomeSynapse Core / V001 — Initial Event Store Schema
-- Creates the domain event store, subscriber checkpoints, and view checkpoints.
--
-- AMENDED 2026-04-10 (M2.5):
--   Added `subject_type TEXT NOT NULL` column between `subject_ref` and
--   `subject_sequence`. The subject's SubjectType discriminator is stored
--   alongside the ULID so that bus-side subscription filtering (Doc 01 §3.4)
--   can resolve the subject's type category at append time without a
--   registry lookup. This amendment lands in V001 rather than a V00X
--   migration because the schema has not yet shipped to any production
--   database — all existing databases are empty test instances, and the
--   migration framework is still pre-M2.9.
--
-- AMENDED 2026-05-02 (M2-bridge):
--   Added 9 columns to the events table and tightened `chain_hash` from
--   nullable to NOT NULL with 32-byte zero-hash default. Added partial
--   unique index on (home_id, idempotency_key). These changes implement:
--     AMD-34: `home_id BLOB(16) NOT NULL` — home identity schema reservation
--     AMD-35: `idempotency_key TEXT` — persistent idempotency key
--     AMD-37: `chain_hash` NOT NULL with zero-hash default
--     Tier 2 addendum: `payload_size`, `batch_id`, `external_ref`,
--       `intent_kind`, `logical_time`, `node_id` — schema reservations
--   V001 has not shipped to any production database. All existing databases
--   are empty test instances created by @TempDir in test methods.

-- === Domain Event Store ===

CREATE TABLE IF NOT EXISTS events (
    global_position   INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id          BLOB(16) NOT NULL,
    home_id           BLOB(16) NOT NULL,
    event_type        TEXT     NOT NULL,
    schema_version    INTEGER  NOT NULL DEFAULT 1,
    ingest_time       INTEGER  NOT NULL,
    event_time        INTEGER,
    subject_ref       BLOB(16) NOT NULL,
    subject_type      TEXT     NOT NULL,
    subject_sequence  INTEGER  NOT NULL,
    priority          TEXT     NOT NULL DEFAULT 'NORMAL',
    origin            TEXT     NOT NULL DEFAULT 'UNKNOWN',
    actor_ref         BLOB(16),
    idempotency_key   TEXT,
    correlation_id    BLOB(16) NOT NULL,
    causation_id      BLOB(16),
    event_category    TEXT     NOT NULL,
    payload_size      INTEGER  NOT NULL,
    batch_id          BLOB(16),
    external_ref      TEXT,
    intent_kind       TEXT     NOT NULL DEFAULT 'UNSPECIFIED',
    logical_time      INTEGER  NOT NULL DEFAULT 0,
    node_id           INTEGER  NOT NULL DEFAULT 0,
    payload           BLOB     NOT NULL,
    chain_hash        BLOB(32) NOT NULL DEFAULT x'0000000000000000000000000000000000000000000000000000000000000000',
    UNIQUE(subject_ref, subject_sequence)
);

CREATE INDEX IF NOT EXISTS idx_events_subject     ON events(subject_ref, subject_sequence);
CREATE INDEX IF NOT EXISTS idx_events_type        ON events(event_type, global_position);
CREATE INDEX IF NOT EXISTS idx_events_correlation ON events(correlation_id, global_position);
CREATE INDEX IF NOT EXISTS idx_events_ingest_time ON events(ingest_time);
CREATE INDEX IF NOT EXISTS idx_events_event_time  ON events(COALESCE(event_time, ingest_time));
CREATE INDEX IF NOT EXISTS idx_events_actor       ON events(actor_ref) WHERE actor_ref IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_events_idempotency
    ON events(home_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- === Subscriber Checkpoints (per Doc 01 §4.2) ===

CREATE TABLE IF NOT EXISTS subscriber_checkpoints (
    subscriber_id     TEXT    PRIMARY KEY,
    last_position     INTEGER NOT NULL,
    last_updated      INTEGER NOT NULL
);

-- === View Checkpoints (per Doc 04 §3.12) ===

CREATE TABLE IF NOT EXISTS view_checkpoints (
    view_name   TEXT    PRIMARY KEY,
    position    INTEGER NOT NULL,
    data        BLOB    NOT NULL,
    updated_at  INTEGER NOT NULL
);
