-- HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
--
-- V004 — Operational indices on subscriber_dead_letters for admin query
-- efficiency (AMD-36).
--
-- The V002 migration created the table with a UNIQUE(subscriber_id,
-- event_position) constraint — that constraint's implicit autoindex
-- already covers the position-lookup query path (findByPosition). These
-- additional indices accelerate the two admin paths that operate
-- per-subscriber rather than per-event:
--   - findBySubscriber: SELECT ... WHERE subscriber_id = ? ORDER BY last_attempt_at
--   - countBySubscriber: SELECT COUNT(*) WHERE subscriber_id = ?
--
-- Additive-only: no table or column changes. Safe to apply on databases
-- created by V002 with existing rows.

CREATE INDEX IF NOT EXISTS idx_sdl_subscriber
    ON subscriber_dead_letters(subscriber_id);

CREATE INDEX IF NOT EXISTS idx_sdl_sub_last_attempt
    ON subscriber_dead_letters(subscriber_id, last_attempt_at);
