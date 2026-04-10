-- Intentionally broken SQL used to verify MigrationRunner records
-- failed migrations with success=0 and re-raises a MigrationException.
CREATE TABLE test_table (
    id INTEGER PRIMARY KEY,
    INVALID NOT NULL SYNTAX HERE
);
