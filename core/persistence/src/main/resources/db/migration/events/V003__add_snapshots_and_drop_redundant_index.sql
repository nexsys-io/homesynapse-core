-- HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
--
-- V003 — Add snapshots table for State Projection rebuild performance;
--        drop redundant idx_events_subject (duplicates sqlite_autoindex_events_1
--        from UNIQUE(subject_ref, subject_sequence) constraint).
--
-- Context: M2→M3 bridge. The State Projection (M3) needs aggregate snapshots
-- to bound restart replay cost. Without snapshots, a Typical Home (50 devices)
-- crosses 1M events in ~12 days, making cold-start replay take 10-20 seconds
-- on Pi 5 NVMe. With snapshots every 200 events per aggregate, replay is
-- bounded to <1 second.
--
-- Index redundancy: SQLite generates an implicit autoindex
-- (sqlite_autoindex_events_1) for the UNIQUE(subject_ref, subject_sequence)
-- constraint on the events table. The explicit idx_events_subject created
-- in V001 indexes the same column pair and provides identical lookup and
-- range-scan capability. The redundant explicit index wastes ~25 bytes/row —
-- approximately 725 MB/year at Typical Home event rates (29.2M events/year).
-- See design/v003_snapshots_design_note.md for full rationale.

-- === Snapshots Table ===
-- Sync scope: LOCAL-ONLY. Snapshots are derived from the event log and
-- rebuilt per-instance from the replicated event stream (INV-LF-05).

CREATE TABLE IF NOT EXISTS snapshots (
    snapshot_id      BLOB(16)  NOT NULL PRIMARY KEY,
    subject_ref      BLOB(16)  NOT NULL,
    subject_type     TEXT      NOT NULL,
    last_position    INTEGER   NOT NULL,
    last_subject_seq INTEGER   NOT NULL,
    schema_version   INTEGER   NOT NULL DEFAULT 1,
    taken_at         INTEGER   NOT NULL,
    payload_size     INTEGER   NOT NULL,
    payload          BLOB      NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_snapshots_subject
    ON snapshots(subject_ref, subject_type, last_subject_seq DESC);

-- === Drop Redundant Index ===
-- sqlite_autoindex_events_1 (created implicitly by V001's
-- UNIQUE(subject_ref, subject_sequence) constraint) already indexes
-- (subject_ref, subject_sequence) and serves all queries currently using
-- idx_events_subject. Dropping the explicit index reclaims ~25 bytes/row
-- (~725 MB/year at Typical Home scale) with zero query-plan impact.

DROP INDEX IF EXISTS idx_events_subject;
