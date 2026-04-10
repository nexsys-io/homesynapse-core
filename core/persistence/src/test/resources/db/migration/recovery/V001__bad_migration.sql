-- Corrected version of the bad-migration fixture. Used to verify that
-- MigrationConfig.recovery() re-attempts a previously failed migration
-- (same version, same description, different content).
CREATE TABLE IF NOT EXISTS test_table (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);
