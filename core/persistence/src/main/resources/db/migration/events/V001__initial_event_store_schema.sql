-- HomeSynapse Core / V001 — Initial Event Store Schema
-- Creates the domain event store, subscriber checkpoints, and view checkpoints.

-- === Domain Event Store ===

CREATE TABLE IF NOT EXISTS events (
    global_position   INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id          BLOB(16) NOT NULL,
    event_type        TEXT     NOT NULL,
    schema_version    INTEGER  NOT NULL DEFAULT 1,
    ingest_time       INTEGER  NOT NULL,
    event_time        INTEGER,
    subject_ref       BLOB(16) NOT NULL,
    subject_sequence  INTEGER  NOT NULL,
    priority          TEXT     NOT NULL DEFAULT 'NORMAL',
    origin            TEXT     NOT NULL DEFAULT 'UNKNOWN',
    actor_ref         BLOB(16),
    correlation_id    BLOB(16) NOT NULL,
    causation_id      BLOB(16),
    event_category    TEXT     NOT NULL,
    payload           BLOB     NOT NULL,
    chain_hash        BLOB(32),
    UNIQUE(subject_ref, subject_sequence)
);

CREATE INDEX IF NOT EXISTS idx_events_subject     ON events(subject_ref, subject_sequence);
CREATE INDEX IF NOT EXISTS idx_events_type        ON events(event_type, global_position);
CREATE INDEX IF NOT EXISTS idx_events_correlation ON events(correlation_id, global_position);
CREATE INDEX IF NOT EXISTS idx_events_ingest_time ON events(ingest_time);
CREATE INDEX IF NOT EXISTS idx_events_event_time  ON events(COALESCE(event_time, ingest_time));
CREATE INDEX IF NOT EXISTS idx_events_actor       ON events(actor_ref) WHERE actor_ref IS NOT NULL;

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
