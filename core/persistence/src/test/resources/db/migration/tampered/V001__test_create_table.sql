-- Tampered version of V001 — same version number, different content.
-- Used to exercise the checksum mismatch path in MigrationRunner.
CREATE TABLE IF NOT EXISTS test_table (
    id      INTEGER PRIMARY KEY,
    name    TEXT NOT NULL,
    extra   TEXT
);
