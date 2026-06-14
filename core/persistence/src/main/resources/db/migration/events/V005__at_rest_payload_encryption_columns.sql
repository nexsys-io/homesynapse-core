-- HomeSynapse Core / Copyright (c) 2026 NexSys. All rights reserved.
--
-- V005 — At-rest payload encryption columns (Doc 15 §3.4, §4.1, M6.3).
--
-- Adds the two nullable columns the at-rest write path populates for the
-- sensitive-PII encrypted scopes identity / presence_personal (OQ-15-2
-- CONFIRMED 2026-06-12): payload_iv is the 96-bit AES-256-GCM counter
-- nonce (OR-M6-NONCE) and dek_ref is the scope_id:key_version reference.
-- Unencrypted events keep a plaintext payload with NULL payload_iv and
-- NULL dek_ref. Additive and backfill-free: SQLite ALTER TABLE ADD COLUMN
-- appends a nullable column whose value is NULL for every pre-existing row
-- (mirrors AMD-37's zero-cost-activation philosophy). The chain hash covers
-- payload as stored either way (Doc 15 §5), so this migration does not
-- perturb the chain. projectionVersion is unchanged (an event-store schema
-- migration, not a state-projection change).
--
-- NOTE: keep all commentary in this header block. A trailing inline comment
-- after a statement terminator makes MigrationRunner.splitSqlStatements emit
-- a comment-only fragment that sqlite-jdbc rejects, failing the migration
-- (V005 gate-fix round 2, 2026-06-13). Statements stay clean below.

ALTER TABLE events ADD COLUMN payload_iv BLOB;
ALTER TABLE events ADD COLUMN dek_ref TEXT;
