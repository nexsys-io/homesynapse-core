-- HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
--
-- V002 — Subscriber Dead-Letter Queue
-- Provides durable parking for poison events that a subscriber cannot process
-- after exhausting retry attempts. See AMD-36.
--
-- Sync scope: LOCAL-ONLY. This table does not participate in cross-instance
-- CRDT sync (INV-LF-05). DLQ state is specific to a hub's processing
-- history and has no meaning in another instance.

CREATE TABLE IF NOT EXISTS subscriber_dead_letters (
    dlq_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    subscriber_id     TEXT    NOT NULL,
    sequence_key      TEXT    NOT NULL,
    event_position    INTEGER NOT NULL,
    event_id          BLOB(16) NOT NULL,
    cause_class       TEXT    NOT NULL,
    cause_message     TEXT    NOT NULL,
    attempt_count     INTEGER NOT NULL DEFAULT 1,
    first_seen_at     INTEGER NOT NULL,
    last_attempt_at   INTEGER NOT NULL,
    diagnostics       TEXT,
    UNIQUE(subscriber_id, event_position)
);
