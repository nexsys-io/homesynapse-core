# persistence — `com.homesynapse.persistence` — 20 types — SQLite WAL storage, telemetry ring buffer, WriteCoordinator, migration framework, database executor, maintenance lifecycle

## Purpose

The persistence module defines the Persistence Layer's public API contracts for the HomeSynapse event-sourced core. It provides interfaces and data records for telemetry ring store writes and queries, database lifecycle management (startup, shutdown, backup, restore), storage maintenance (retention, vacuum, health monitoring), and view checkpoint storage. The Persistence Layer is the operational backbone of HomeSynapse — it manages all SQLite databases, WAL tuning, schema migrations, and storage pressure management. Every subsystem that needs durable storage depends on these types; the implementation (SQLite JDBC operations, WAL management, maintenance scheduling) lives in this module's Phase 3 implementation classes.

## Design Doc Reference

**Doc 04 — Persistence Layer** is the governing design document:
- §3.6: Telemetry ring store — slot-based circular buffer, `INSERT OR REPLACE` with `slot = seq % max_rows`
- §3.4: Retention execution — priority-based (DIAGNOSTIC 7d, NORMAL 90d, CRITICAL 365d), batch deletes with subscriber safety check
- §3.5: Storage pressure management — three-threshold escalation (WARNING/CRITICAL/EMERGENCY)
- §3.10: Backup and restore lifecycle — SQLite backup API, pre-upgrade mandatory, integrity check on backup copy
- §3.12: View checkpoint store — `view_checkpoints` table, same-transaction semantics with subscriber checkpoints
- §4: Data model — table definitions for `view_checkpoints`, `telemetry_samples`, `aggregation_cursors`
- §8.1: CheckpointStore interface (already exists as `ViewCheckpointStore` in state-store module)
- §8.3: TelemetryWriter interface and TelemetrySample record
- §8.4: PersistenceLifecycle interface, BackupResult and BackupOptions records
- §8.5: TelemetryQueryService interface and RingBufferStats record
- §8.6: MaintenanceService interface

## JPMS Module

```
module com.homesynapse.persistence {
    requires transitive com.homesynapse.platform;
    requires com.homesynapse.state;
    requires com.homesynapse.event;

    requires java.sql;
    requires org.slf4j;

    exports com.homesynapse.persistence;
}
```

`requires java.sql;` and `requires org.slf4j;` were added for M2.2. `java.sql` is required because `MigrationRunner` uses JDBC (`Connection`, `PreparedStatement`, `ResultSet`, `Statement`, `SQLException`). SLF4J is required for `MigrationRunner` logging per LTD-15. All subsequent Phase 3 implementation classes in this module should use SLF4J as the single logging API.

The `requires transitive com.homesynapse.platform` declaration is required because `EntityId` (from platform-api) appears in `TelemetrySample`'s public API signature. Any module that reads `com.homesynapse.persistence` automatically gets access to all identity types without needing to declare the dependency. The non-transitive `requires com.homesynapse.state` provides access to `ViewCheckpointStore` which this module implements. The non-transitive `requires com.homesynapse.event` provides access to event types referenced in Javadoc.

## Package Structure

- **`com.homesynapse.persistence`** — All types in a single flat package. Contains: telemetry data records (`TelemetrySample`, `RingBufferStats`), backup/restore records (`BackupOptions`, `BackupResult`), maintenance result records (`RetentionResult`, `VacuumResult`, `StorageHealth`), and four service interfaces (`TelemetryWriter`, `TelemetryQueryService`, `PersistenceLifecycle`, `MaintenanceService`).

## Complete Type Inventory

### Data Records

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `TelemetrySample` | record (4 fields) | Atomic unit of telemetry data — a single numeric measurement | Fields: `entityRef` (EntityId), `attributeKey` (String), `value` (double), `timestamp` (Instant). Compact constructor validates non-null for entityRef, attributeKey, timestamp. Numeric values only — non-numeric data uses the event model path. |
| `RingBufferStats` | record (6 fields) | Diagnostic snapshot of telemetry ring store state | Fields: `maxRows` (int), `currentSeq` (long), `oldestSeqInRing` (long), `distinctEntities` (int), `oldestTimestamp` (Instant), `newestTimestamp` (Instant). Compact constructor validates non-null for timestamps. |
| `BackupOptions` | record (2 fields) | Configuration for backup creation | Fields: `includeTelemetry` (boolean), `preUpgrade` (boolean). All primitives — no validation needed. `preUpgrade = true` means the backup is a pre-migration safety snapshot that aborts on any failure. |
| `BackupResult` | record (5 fields) | Outcome of a backup creation operation | Fields: `backupDirectory` (Path), `createdAt` (Instant), `eventsGlobalPosition` (long), `telemetryIncluded` (boolean), `integrityVerified` (boolean). `integrityVerified` refers to `PRAGMA integrity_check` on the backup COPY, not the live database. |
| `RetentionResult` | record (5 fields) | Outcome of a retention sweep | Fields: `eventsDeleted` (long), `diagnosticEventsDeleted` (long), `normalEventsDeleted` (long), `criticalEventsDeleted` (long), `durationMs` (long). All primitives — no validation. |
| `VacuumResult` | record (4 fields) | Outcome of an incremental vacuum operation | Fields: `pagesFreed` (long), `databaseSizeBeforeBytes` (long), `databaseSizeAfterBytes` (long), `durationMs` (long). All primitives — no validation. |
| `StorageHealth` | record (7 fields) | Point-in-time storage utilization snapshot | Fields: `eventsDatabaseSizeBytes` (long), `telemetryDatabaseSizeBytes` (long), `totalSizeBytes` (long), `budgetBytes` (long, 0 = no budget), `usagePercent` (double), `walSizeBytes` (long), `healthy` (boolean). All primitives — no validation. |

### Service Interfaces

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `TelemetryWriter` | interface | Batch write interface for telemetry samples to ring store | `writeSamples(List<TelemetrySample>)` — atomic batch write, assigns monotonic sequences, overwrites oldest slots when full. |
| `TelemetryQueryService` | interface | Read-only query interface for ring store telemetry data | `querySamples(EntityId, String, Instant, Instant, int)` → `List<TelemetrySample>`, `getRingStats()` → `RingBufferStats`. |
| `PersistenceLifecycle` | interface | Lifecycle management — startup, shutdown, backup, restore | `start()` → `CompletableFuture<Void>`, `stop()`, `createBackup(BackupOptions)` → `BackupResult`, `restoreFromBackup(Path)`. |
| `MaintenanceService` | interface | Storage maintenance — retention, vacuum, health monitoring | `runRetention()` → `RetentionResult`, `runVacuum()` → `VacuumResult`, `getStorageHealth()` → `StorageHealth`. |

**Total: 11 public types + 1 module-info.java = 12 Java files.**

### Internal Types (Package-Private)

In addition to the 11 public types above, the persistence module declares 2 package-private types that govern the internal write path. These are not part of the public API — external modules cannot reference them directly — but they are documented here because all SQLite write paths in the module are required to route through them.

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `WriteCoordinator` | interface (package-private) | Serializes all write operations through a bounded priority queue, ensuring single-writer SQLite semantics and isolating sqlite-jdbc JNI calls from the virtual thread carrier pool | Methods: `<T> T submit(WritePriority priority, Callable<T> operation)` (synchronous, returns the operation's result; throws `IllegalStateException` after `shutdown()`), `void shutdown()`. Thread-safe — `submit` may be called from any thread, including virtual threads. All SQLite write paths (`SqliteEventPublisher`, `SqliteCheckpointStore`, `SqliteViewCheckpointStore`, retention, vacuum, backup) route through this interface. |
| `WritePriority` | enum (package-private, 5 values) | Priority ordering for write operations submitted to the `WriteCoordinator`. Lower rank = higher priority. | Values with rank: `EVENT_PUBLISH(1)`, `STATE_PROJECTION(2)`, `WAL_CHECKPOINT(3)`, `RETENTION(4)`, `BACKUP(5)`. Method: `int rank()` returns the rank value (also package-private). Reflects the operational priorities of Doc 04 §3 (AMD-06): user-facing latency wins over background maintenance. |
| `MigrationRunner` | class (package-private, final) | Applies forward-only SQL migrations to a single SQLite database and tracks applied versions in an `hs_schema_version` table (LTD-07). **M2.2 — Phase 3 complete.** | Constructor: `MigrationRunner(Connection)`. Primary method: `void migrate(String migrationPath, List<String> migrationFiles, MigrationConfig config)`. Creates `hs_schema_version(version PK, checksum, description, applied_at, success)` on first run. Computes SHA-256 (lowercase hex) over each file's UTF-8 content. Halts on: checksum mismatch of an applied version, version gap (union of tracked+parsed must form contiguous [1..N]), database schema ahead of application, SQL execution failure (records `success=0` via explicit commit after rollback), previously-failed version without `forceRetryFailed`, or `backupRequired && !backupVerified` on a non-empty database. Transactional per-migration (auto-commit disabled, rollback on failure). Lightweight `;`-delimited SQL splitter with line-comment awareness. Not thread-safe — concurrent-startup safety is provided by SQLite's own write-lock contention, verified by contract test. |
| `MigrationConfig` | record (package-private, 3 boolean fields) | Configuration for a single `MigrationRunner.migrate` invocation | Fields: `backupRequired`, `backupVerified`, `forceRetryFailed`. Factories: `freshInstall()` (all false), `upgrade(boolean backupVerified)` (backupRequired=true), `recovery()` (forceRetryFailed=true). |
| `MigrationException` | class (package-private, final) extends RuntimeException | Thrown when a migration cannot be applied | Fatal — `PersistenceLifecycle.start()` aborts on any `MigrationException`. `RuntimeException` (not checked) because there is no in-process recovery path during startup. Two constructors: message-only and message+cause. `serialVersionUID = 1L`. |
| `ReadExecutor` | interface (package-private) | Routes read operations to platform threads to prevent sqlite-jdbc JNI calls from pinning virtual thread carriers (AMD-26). Symmetric to `WriteCoordinator` but without priority ordering — all reads are equal priority. **M2.3 — Phase 3 complete.** | Methods: `<T> T execute(Callable<T> operation)` (synchronous, returns the operation's result; wraps checked exceptions; propagates unchecked exceptions directly; throws `IllegalStateException` after `shutdown()`), `void shutdown()`. Thread-safe — `execute` may be called concurrently from any number of virtual threads. |
| `PlatformThreadWriteCoordinator` | class (package-private, final) implementing `WriteCoordinator` | Production write coordinator backed by a single daemon platform thread (`hs-write-0`) servicing a `PriorityBlockingQueue` ordered by `WritePriority.rank()` with an `AtomicLong` FIFO tiebreaker. **M2.3 — Phase 3 complete.** | Caller submits a `WorkItem` carrying a `CompletableFuture`, parks on `future.get()`; the writer thread dequeues, runs the callable, completes the future. Exception unwrapping in `submit()`: `RuntimeException` → propagate directly; `Error` → propagate directly; checked `Exception` → wrap in `RuntimeException`. `shutdown()` sets the shutdown flag, enqueues a max-rank poison pill, joins the writer thread (5 s timeout), and completes any remaining queued futures exceptionally with `IllegalStateException`. Passes the full `WriteCoordinatorContractTest` (11 tests). |
| `PlatformThreadReadExecutor` | class (package-private, final) implementing `ReadExecutor` | Production read executor backed by a bounded pool of daemon platform threads (`hs-read-0`, `hs-read-1`, …) using `Executors.newFixedThreadPool` with a custom `ThreadFactory`. **M2.3 — Phase 3 complete.** | Configurable thread count via constructor (`AMD-27` default is 2; rejects `< 1`). `execute()` submits to the pool via `pool.submit(callable)`, parks the virtual thread on `future.get()`. Exception unwrapping matches `PlatformThreadWriteCoordinator`. `shutdown()` calls `ExecutorService.shutdown()` then `awaitTermination(5 s)`, falling back to `shutdownNow()` on timeout. Passes the full `ReadExecutorContractTest` (5 tests). |
| `DatabaseExecutor` | class (package-private, final) | Per-database lifecycle manager. Owns a single SQLite file, its write connection, N read connections, the `PlatformThreadWriteCoordinator`, and the `PlatformThreadReadExecutor`. **M2.3 — Phase 3 complete.** | Constructor: `DatabaseExecutor(int readThreadCount)`. `start(Path dbPath, String migrationPath, List<String> migrationFiles, MigrationConfig config)` opens the write connection, detects new vs. existing database (`SELECT count(*) FROM sqlite_master WHERE type='table'`), sets creation-time PRAGMAs (`page_size=4096`, `auto_vacuum=INCREMENTAL`) ONLY on a new database, applies the 8 LTD-03 connection PRAGMAs with `journal_mode=WAL` first, runs `MigrationRunner.migrate()`, opens N read connections with the same PRAGMAs, then starts the write coordinator and read executor. Accessors `writeCoordinator()`, `readExecutor()`, `writeConnection()` throw `IllegalStateException` before `start()` and after `shutdown()`. `shutdown()` is idempotent; order: write coordinator → read executor → read connections → write connection. Not yet wired into `PersistenceLifecycle` — that integration ships with M2.4. |

**Total including internal types: 20 types** (11 public + 9 package-private).

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types for telemetry entity references | `EntityId` (field on TelemetrySample; parameter on TelemetryQueryService.querySamples). |
| **state-store** (`com.homesynapse.state`) | `requires` (non-transitive) — Persistence implements `ViewCheckpointStore` | `ViewCheckpointStore`, `CheckpointRecord` (implementation target; not re-exported). |
| **event-model** (`com.homesynapse.event`) | `requires` (non-transitive) — Event types referenced in Javadoc | `EventPriority` (referenced in RetentionResult Javadoc `@see` tag). Not used in public API signatures. |

### Gradle Dependencies

```kotlin
dependencies {
    api(project(":platform:platform-api"))
    implementation(project(":core:event-model"))
    implementation(project(":core:state-store"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)
}
```

Event-model and state-store are `implementation`-only dependencies — their types do not appear in persistence's public API. Platform-api is `api` scope because `EntityId` appears in public signatures (`TelemetrySample`, `TelemetryQueryService`). SQLite JDBC and SLF4J are `implementation` scope because neither is exposed in the public API.

## Consumers

### Current consumers:
- **integration-api** — Imports `TelemetryWriter` for the `IntegrationContext` record. Integration adapters that declare `RequiredService.TELEMETRY_WRITER` and `DataPath.TELEMETRY` receive a `TelemetryWriter` instance for writing high-frequency numeric telemetry.

### Planned consumers (from design doc dependency graph):
- **integration-runtime** — Will instantiate `TelemetryWriter` implementation and inject it into `IntegrationContext`. Will call `PersistenceLifecycle.start()` indirectly via lifecycle ordering.
- **rest-api** — Will use `TelemetryQueryService` for telemetry chart endpoints. Will use `MaintenanceService.getStorageHealth()` for admin dashboards. Will use `PersistenceLifecycle.createBackup()` for admin backup endpoints.
- **lifecycle** — Will call `PersistenceLifecycle.start()` during ordered startup (BEFORE Event Bus and State Store per Doc 12 §3.1) and `stop()` during shutdown (AFTER all subscribers have checkpointed).
- **observability** — Will use `MaintenanceService.getStorageHealth()` and `TelemetryQueryService.getRingStats()` for health indicators.

## Cross-Module Contracts

- **`TelemetryWriter.writeSamples()` is the SOLE write path for telemetry.** No other entry point exists. Integration adapters receive a `TelemetryWriter` through `IntegrationContext`. The implementation must be thread-safe — multiple adapters may call concurrently from different virtual threads.
- **`TelemetrySample.value` is always `double`.** Non-numeric telemetry is not supported by the ring store. Non-numeric attribute changes use the domain event path (`state_reported` events). This is a semantic decision, not a type limitation (Doc 04 §3.8).
- **`TelemetrySample.entityRef` is `EntityId`, not `EntityRef`.** Doc 04 §8.3 uses `EntityRef` as a conceptual name. In the codebase, `EntityId` (from platform-api) is the typed ULID wrapper per LTD-04. The field name `entityRef` is preserved from the design doc for consistency.
- **Ring store uses slot-based overwrite.** `slot = seq % max_rows`. When the ring is full (default 100,000 rows per Doc 04 §9), the oldest slot is overwritten. This is a circular buffer — historical samples beyond the ring capacity are permanently lost.
- **`PersistenceLifecycle.start()` must complete BEFORE Event Bus and State Store.** Doc 12 §3.1 mandates this boot order. Dependent subsystems block on the returned `CompletableFuture<Void>`.
- **`PersistenceLifecycle.stop()` is called AFTER all subscribers checkpoint.** The shutdown sequence is the reverse of startup — all event subscribers write final checkpoints before persistence closes database connections.
- **`BackupResult.integrityVerified` refers to the backup COPY.** The integrity check (`PRAGMA integrity_check`) runs on the copied file, not the live database. This catches corruption that may have occurred during the hot backup.
- **`ViewCheckpointStore` (state-store module) is IMPLEMENTED by persistence.** The persistence module `requires com.homesynapse.state` to access the interface. The SQLite implementation stores checkpoints in `view_checkpoints` table in the domain event store database, sharing the same transaction as subscriber checkpoint updates.
- **Retention is priority-based: DIAGNOSTIC → NORMAL → CRITICAL.** Events are deleted in priority order. Retention periods default to DIAGNOSTIC 7d, NORMAL 90d, CRITICAL 365d (configurable via Event Model §9). CRITICAL events are NEVER subject to emergency retention — the system enters degraded state rather than overwriting committed critical events (INV-ES-01).
- **`MaintenanceService` methods are thread-safe.** `getStorageHealth()` may be called concurrently with a running retention sweep or vacuum operation.
- **`StorageHealth.budgetBytes = 0` means no budget configured.** When no budget is set, `usagePercent` is not meaningful (will be 0.0). The `healthy` field is derived from the configured thresholds.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-03** | SQLite, WAL mode, `synchronous=NORMAL`. Single-writer model. 128 MB page cache. |
| **LTD-04** | EntityId (typed ULID wrapper from platform-api) used for telemetry entity references. Never raw String or Ulid. |
| **LTD-07** | Hand-rolled forward-only SQL migrations. Mandatory backup-before-migrate (BackupOptions.preUpgrade). |
| **LTD-14** | CLI-driven upgrade with mandatory snapshot. BackupOptions.preUpgrade = true triggers abort-on-failure semantics. |
| **LTD-15** | JFR continuous recording for metrics. All persistence metrics exposed via JFR (Doc 04 §11). |
| **INV-ES-01** | Events are immutable facts. Retention only deletes events past configured retention periods, never modifies them. |
| **INV-ES-04** | Write-ahead persistence. Events are durable before delivery. Persistence Layer provides the durability guarantee. |
| **INV-PD-06** | Offline integrity. Write operations are transactional and crash-safe. Power loss is a normal operating condition. |

**JNI carrier pinning and platform thread executor.** The persistence module owns the platform thread executor that isolates sqlite-jdbc's `synchronized native` JNI methods from the virtual thread carrier pool (LTD-03, AMD-27). Executor sizing: 1 write thread (single-writer model), 2–3 read threads (WAL concurrent readers). All other modules access SQLite exclusively through the EventStore and StateStore interfaces — they never interact with the executor directly. The executor is an internal implementation detail; if sqlite-jdbc is ever replaced with a pure-Java driver, the executor requirement changes. See Doc 04 §15 (Design Rationale) for the full justification.

## Migration Resources Layout

SQL migration files live under `src/main/resources/db/migration/` in two parallel tracks — one per SQLite database managed by `PersistenceLifecycle`:

```
src/main/resources/db/migration/
├── events/
│   └── V001__initial_event_store_schema.sql   # events, subscriber_checkpoints, view_checkpoints
└── telemetry/
    └── .gitkeep                               # scaffolding only — first real migration ships with M2.8
```

- **`events/`** — migrations for `homesynapse-events.db`. V001 creates the canonical `events` table (16 columns, AUTOINCREMENT `global_position`), `subscriber_checkpoints`, `view_checkpoints`, and 6 indexes including a partial index on `actor_ref`. Payload column is `BLOB` per DECIDE-M2-06. All table creation uses `IF NOT EXISTS` as a belt-and-braces safety layer (the runner's tracking-table check is the authoritative guard).
- **`telemetry/`** — scaffolded directory, intentionally empty except for `.gitkeep`. First real migration ships with M2.8 (telemetry ring store implementation).

Test-only SQL fixtures live under `src/test/resources/db/migration/` in sibling subdirectories (`test/`, `tampered/`, `bad/`, `recovery/`). The `tampered/` directory intentionally contains a file at the same version number as `test/V001__test_create_table.sql` but with different content — this is how the `checksumMismatch` test produces a mismatch without needing to mutate files on disk.

**PersistenceLifecycle contract (future Phase 3 M2.3+):** the lifecycle implementation will invoke `MigrationRunner` once per database during `start()`, passing an explicit ordered list of filenames (no classpath scanning under JPMS). The list becomes the authoritative manifest — adding a new migration requires adding the filename to the lifecycle's call site, which is a deliberate forcing function for code review.

## Sealed Hierarchies

None. This module contains no sealed types.

## Key Design Decisions

1. **`EntityId` from platform-api, not a separate `EntityRef` type.** Doc 04 §8.3 describes `EntityRef` as a ULID reference to the entity. In the codebase, `EntityId` already fulfills this role per LTD-04. The field name `entityRef` is preserved from the design doc for semantic clarity.

2. **`TelemetryWriter` is the primary cross-module deliverable.** It replaces the `@Nullable Object` placeholder in `IntegrationContext` (Block I). The type change from `Object` to `TelemetryWriter` in `IntegrationContext.telemetryWriter` is the key cross-module integration point.

3. **`ViewCheckpointStore` is NOT duplicated.** The interface and its return type (`CheckpointRecord`) already exist in the state-store module (Block H). The persistence module only `requires` state-store — it does NOT recreate these types. The persistence module IMPLEMENTS the interface in Phase 3.

4. **RetentionResult, VacuumResult, StorageHealth are persistence-owned records.** Doc 04 §8.6 defines the `MaintenanceService` interface methods but not the return type record structures. These records are designed from §3.4, §3.5, §11 (metrics/logging) to capture the fields that maintenance operations need to report.

5. **`BackupOptions.preUpgrade` controls abort-on-failure semantics.** Per Doc 04 §8.4 and LTD-14, when `preUpgrade = true`, the backup creation must abort on ANY failure — the migration runner refuses to execute without a validated backup.

6. **Two databases, one lifecycle.** The Persistence Layer manages two SQLite files: `homesynapse-events.db` (domain events, subscriber checkpoints, view checkpoints) and `homesynapse-telemetry.db` (ring store). `PersistenceLifecycle` manages both through a single start/stop lifecycle. `BackupOptions.includeTelemetry` controls whether the telemetry DB is included in backups.

## Gotchas

**GOTCHA: `TelemetrySample.entityRef` field type is `EntityId`, not `EntityRef`.** Doc 04 uses `EntityRef` as a conceptual name. The codebase type is `EntityId`. Do not create an `EntityRef` type — it does not exist in the type system.

**GOTCHA: `StorageHealth.budgetBytes = 0` is a sentinel.** When zero, no storage budget is configured. `usagePercent` and `healthy` are not meaningful without a budget. Consumers should check for `budgetBytes > 0` before interpreting these fields.

**GOTCHA: `BackupResult.integrityVerified` is about the BACKUP, not the live database.** The integrity check runs on the copied file after backup completion. A `false` value means the backup may be corrupt — the live database may still be healthy.

**GOTCHA: `TelemetryWriter` is `@Nullable` in `IntegrationContext`.** Adapters only receive a `TelemetryWriter` if they declared `RequiredService.TELEMETRY_WRITER` AND `DataPath.TELEMETRY` in their `IntegrationDescriptor`. The supervisor does not provision undeclared services.

**GOTCHA: RetentionResult uses NORMAL 90d and CRITICAL 365d defaults.** Not 30d/90d. The retention periods come from Event Model §9 configuration, not from the persistence layer. The defaults are DIAGNOSTIC 7d, NORMAL 90d, CRITICAL 365d.

**GOTCHA: `ViewCheckpointStore` is in state-store, not persistence.** The interface definition lives in `com.homesynapse.state`. The persistence module IMPLEMENTS it — do not look for `ViewCheckpointStore.java` in this module's source tree.

**GOTCHA: Persistence starts BEFORE Event Bus and State Store.** The boot order is: Persistence → Event Bus → State Store → subscribers. If persistence is not ready, no events can be published and no checkpoints can be read. Shutdown is the reverse.

**GOTCHA: `MaintenanceService.runVacuum()` is INCREMENTAL vacuum, not full VACUUM.** Incremental vacuum frees pages without rebuilding the entire database. Full VACUUM is opt-in quarterly maintenance (Doc 04 §3.3). Do not confuse the two.

<!-- Added 2026-04-10: M2.2 Migration Framework implementation -->

**GOTCHA: `MigrationRunner.recordFailure()` explicitly commits.** When a migration's SQL fails, the runner calls `connection.rollback()` to undo the partial changes, then inserts a `success=0` row into `hs_schema_version` and calls `connection.commit()` on that insert. Without the explicit commit, the failure record would sit in a never-committed implicit transaction (because `setAutoCommit(false)` is still in effect) and disappear when the connection closes. The tests verify this by checking that a failed migration leaves exactly one `success=0` row behind, and that a subsequent run without `forceRetryFailed` halts with the "previously failed" error.

**GOTCHA: `MigrationRunner.enforceVersionAgainstTrackedState` checks ahead BEFORE gap.** When a database has version 5 but the application only knows about V001 and V002, the condition is strictly "schema ahead" — reporting it as a gap would be misleading. The runner checks `maxTracked > maxParsed` first and short-circuits with the "ahead" error; only if that passes does it perform the union-based contiguous-range check. The order matters for the error message.

**GOTCHA: `MigrationRunner` filename parsing uses `^V(\\d+)__(.+)\\.sql$`.** Exactly two underscores separate the version from the description, and the description is stored in the tracking table with underscores converted to spaces. `V001__initial_event_store_schema.sql` becomes description `"initial event store schema"`. Any migration file that doesn't match the pattern triggers a `MigrationException` with the filename quoted.

**GOTCHA: `MigrationRunner` is NOT thread-safe within a single JVM.** The class is documented as single-threaded. Concurrent-startup safety across processes or JVM instances is provided by SQLite's own write-lock contention, not by any synchronization inside the runner. The tests verify this: two threads racing to run the same migration against a shared file-based SQLite database produce either (a) both succeed idempotently, because thread B sees the committed `hs_schema_version` row after A commits, or (b) one succeeds and one fails on a UNIQUE constraint — the test asserts "at least one succeeds, database stays valid" rather than "exactly one succeeds."

**GOTCHA: Migration resources require an explicit filename list — no classpath scanning.** `MigrationRunner.migrate()` takes `List<String> migrationFiles`. Classpath scanning is unreliable under JPMS and would be hostile to code review; the explicit manifest is a deliberate forcing function. The caller (eventually `PersistenceLifecycle.start()`) hardcodes the ordered list, and adding a new migration requires editing that list.

**GOTCHA: SLF4J is required in module-info.** `module-info.java` declares `requires org.slf4j;` (non-transitive) as of M2.2. Any Phase 3 implementation class that uses a logger will find it already available — do not re-add the dependency.

<!-- Added 2026-04-10: M2.3 DatabaseExecutor + write/read executor implementations -->

**GOTCHA: Creation-time PRAGMAs (`page_size`, `auto_vacuum`) must be set on a new database BEFORE any table exists.** `DatabaseExecutor.start()` detects a new database via `SELECT count(*) FROM sqlite_master WHERE type='table'` — zero tables means new. On a new database it issues `PRAGMA page_size = 4096` and `PRAGMA auto_vacuum = INCREMENTAL` before anything else. On an existing database it skips them entirely because SQLite silently ignores both PRAGMAs once any table exists. The contract test `start_existingDatabase_doesNotResetCreationPragmas` verifies this by pre-creating a database with `page_size=8192` and `auto_vacuum=NONE` and asserting those values survive startup.

**GOTCHA: `journal_mode = WAL` must be the FIRST connection PRAGMA applied.** `DatabaseExecutor.CONNECTION_PRAGMAS` is an ordered `List<String>` with WAL at index 0. Other PRAGMAs like `synchronous = NORMAL` and `cache_size = -128000` depend on WAL being active to take effect on their intended semantics. All read connections get the same 8 PRAGMAs — WAL state is database-wide but per-connection settings (`cache_size`, `mmap_size`, `busy_timeout`, `temp_store`) must be explicitly set on every new connection.

**GOTCHA: `PlatformThreadWriteCoordinator` exception unwrapping.** Inside the writer loop, the operation's exception is captured via `future.completeExceptionally(t)`. The calling thread's `future.get()` then throws `ExecutionException`, which must be unwrapped: `RuntimeException` cause → propagate directly; `Error` cause → propagate directly (rethrown inside the `unwrap` helper, not returned); checked `Exception` cause → wrap in `RuntimeException`. Getting this wrong causes the Tier-3 contract tests to fail because the caller sees `ExecutionException` instead of the expected type. `PlatformThreadReadExecutor` uses the identical unwrapping logic for the same reason.

**GOTCHA: `PlatformThreadWriteCoordinator` uses a poison pill, not thread interruption, for shutdown.** The writer thread parks on `PriorityBlockingQueue.take()`. Interrupting the thread to wake it up would race with normal exception-completion paths. Instead, `shutdown()` sets the `shutdown` volatile flag and `offer`s a single poison-pill `WorkItem` (marked with `poison=true` and `Integer.MAX_VALUE` rank so it sorts after any legitimate work). The writer loop checks the `poison` flag, drains any remaining items (completing their futures with `IllegalStateException`), and returns. A 5-second `join()` with fallback interrupt handles the pathological case.

**GOTCHA: `PriorityBlockingQueue` requires an insertion-order tiebreaker or FIFO is lost.** `WritePriority.rank()` alone is not a total order — two `EVENT_PUBLISH` submissions have the same rank, and `PriorityBlockingQueue`'s ordering is unspecified within equal elements. `PlatformThreadWriteCoordinator` uses an `AtomicLong sequence` and includes it as the secondary key in `WorkItem.compareTo`, so items at the same priority dequeue in strict submission order. Tests that submit 100 operations at mixed priorities rely on this to assert "all results collected."

**GOTCHA: `DatabaseExecutorTest` uses file-based SQLite, not `:memory:`.** WAL mode requires a real file — `jdbc:sqlite::memory:` silently refuses to enter WAL mode. The tests use JUnit 5 `@TempDir` to get a fresh filesystem path per test. The "concurrent reads" test explicitly tears down the default `DatabaseExecutor(2)` and reinstantiates as `DatabaseExecutor(3)` before calling `start()` — `start()` is single-shot and the executor is not reusable after `shutdown()`.

**GOTCHA: `DatabaseExecutor.writeConnection()` is exposed but thread-confined.** The accessor returns the raw JDBC `Connection` for use inside a write callable running on the `hs-write-0` thread (e.g., `SqliteEventStore.publish()`). Calling any JDBC method on this connection from a non-writer thread violates the single-writer contract and re-exposes the sqlite-jdbc JNI carrier pinning problem the executor exists to prevent. The Javadoc documents this — reviewers should flag any code that touches `writeConnection()` outside a `writeCoordinator().submit(...)` callable.

<!-- Added 2026-03-21: Architecture benchmark assessment finding R-5 -->

**GOTCHA: The subscriber grace period default is 24 hours, not unbounded.** `subscriber_grace_period_hours: 24` (range 1–168) in Doc 04 §9. A subscriber that hasn't updated its checkpoint in 24 hours has its checkpoint protection stripped and retention proceeds past it. This is by design (INV-RF-05 — bounded storage), but means a module disabled for maintenance beyond 24 hours will lose checkpoint protection. Use the PAUSED subscriber state (Doc 04 §3.4) for intentional maintenance windows.

- **S4-04 (Gradle/JPMS concordance):** `requires com.homesynapse.event` and `requires com.homesynapse.state` are non-transitive in module-info. Gradle scope is `implementation` for both (verified). `api(project(":platform:platform-api"))` is present for EntityId exposure.

## Test Fixtures and Contract Tests

The `testFixtures` source set (`src/testFixtures/java/com/homesynapse/persistence/`) provides one abstract contract test and one in-memory implementation for the package-private `WriteCoordinator` interface.

### testFixtures Type Inventory

| Type | Kind | Package | Purpose |
|---|---|---|---|
| `WriteCoordinatorContractTest` | abstract class (11 `@Test` methods, 4 `@Nested` tiers) | `com.homesynapse.persistence` | Defines the behavioral contract for `WriteCoordinator`. Both `InMemoryWriteCoordinator` (fixture) and `PlatformThreadWriteCoordinator` (production) pass this suite via `InMemoryWriteCoordinatorTest` and `PlatformThreadWriteCoordinatorTest`. |
| `InMemoryWriteCoordinator` | class implementing `WriteCoordinator` | `com.homesynapse.persistence` | `ReentrantLock`-based serializing implementation. Volatile shutdown flag with double-check after lock acquisition (lock-free fast path on the live state, defensive re-check inside the critical section). Executes operations synchronously on the calling thread inside the lock — no background queue, no executor. Used by contract tests and as a stand-in for the SQLite coordinator in upstream module tests. |
| `ReadExecutorContractTest` | abstract class (5 `@Test` methods) | `com.homesynapse.persistence` | Defines the behavioral contract for `ReadExecutor`. Both `InMemoryReadExecutor` (fixture) and `PlatformThreadReadExecutor` (production) pass this suite via `InMemoryReadExecutorTest` and `PlatformThreadReadExecutorTest`. Tests: result return, unchecked exception propagation, checked exception wrapping, shutdown rejection, and 10-thread concurrent read stress. |
| `InMemoryReadExecutor` | class implementing `ReadExecutor` | `com.homesynapse.persistence` | `ReentrantLock` + volatile shutdown flag. Executes callables synchronously on the calling thread **without** holding the lock during `call()` — the lock guards only lifecycle state (shutdown flag). This matches the contract that allows concurrent reads: the fixture must not serialize read operations. Provides a `reset()` method for test isolation. |

### WriteCoordinatorContractTest Coverage — 4 Nested Tiers

The 11 `@Test` methods are organized into four `@Nested` classes:

- **Tier 1 — Per-Priority Submission (5 tests):** one test per `WritePriority` value (`EVENT_PUBLISH`, `STATE_PROJECTION`, `WAL_CHECKPOINT`, `RETENTION`, `BACKUP`). Each verifies that an operation submitted at that priority executes and returns its result.
- **Tier 2 — Generic Return Types (1 test):** verifies the `<T> T submit(...)` generic correctly handles `String`, `Integer`, `Long`, `Boolean`, and `Void` return types in a single test that exercises all five.
- **Tier 3 — Error Handling (3 tests):** `RuntimeException` thrown by the operation propagates to the caller; checked exception thrown by the operation is wrapped (rather than silently swallowed); a failure in one operation does not corrupt the coordinator's state — subsequent submissions succeed (failure isolation).
- **Tier 4 — Lifecycle and Concurrency (2 tests):** after `shutdown()`, subsequent `submit` calls throw `IllegalStateException`; concurrent 4-thread stress test with simultaneous submissions verifies serialization correctness and absence of races.

### Package-Private Access Pattern

`WriteCoordinatorContractTest` and `InMemoryWriteCoordinator` live in the `com.homesynapse.persistence` package — **NOT** in a `.test` subpackage. This is deliberate: `WriteCoordinator` and `WritePriority` are package-private, and Java's package-private visibility rules require any test code that references them to live in the same package. Gradle's `testFixtures` source set shares the same package namespace as the `main` source set within a single module, so placing the testFixtures files at `src/testFixtures/java/com/homesynapse/persistence/` is the established pattern for testing package-private internal types.

This pattern is intentional and should be reused by any future Phase 3 work that needs to test other package-private types in the persistence module.

## Phase 3 Notes

- **M2.3 — DatabaseExecutor + PlatformThreadWriteCoordinator + PlatformThreadReadExecutor — COMPLETE (2026-04-10).** The production write coordinator, production read executor, and the per-database lifecycle manager that owns them are in place. Covered by `PlatformThreadWriteCoordinatorTest` (inherits 11 tests from `WriteCoordinatorContractTest`), `PlatformThreadReadExecutorTest` (inherits 5 tests from the new `ReadExecutorContractTest`), `InMemoryReadExecutorTest` (same 5 contract tests against the fixture), and `DatabaseExecutorTest` (10 file-based SQLite integration tests under `@TempDir` covering creation-time PRAGMAs on new vs. existing databases, the 8 LTD-03 connection PRAGMAs, WAL activation, migration execution, read/write executor wiring with a round-trip write-then-read, concurrent reads with `DatabaseExecutor(3)`, shutdown closing all connections, and accessors throwing `IllegalStateException` before `start()` and after `shutdown()`). `DatabaseExecutor` is not yet wired into `PersistenceLifecycle.start()` — that integration ships with M2.4.
- **M2.2 — Migration Framework + V001 Initial Schema — COMPLETE (2026-04-10).** `MigrationRunner`, `MigrationConfig`, `MigrationException`, and `V001__initial_event_store_schema.sql` are in place. Covered by `MigrationRunnerTest` (14 tests across 7 tiers: fresh install, idempotency, validation/error detection, multi-migration sequence, real V001 verification, backup gating, concurrent startup). The local build gate (`./gradlew :core:persistence:check`) was deferred to Nick — the sandbox ran out of disk space and no JDK 21 was available. `PersistenceLifecycle.start()` (next milestone) will be the sole call site for `MigrationRunner.migrate()`.
- **TelemetryWriter implementation needed:** `SqliteTelemetryWriter` using `INSERT OR REPLACE INTO telemetry_samples (slot, seq, entity_ref, attribute_key, value, timestamp) VALUES (? % max_rows, ?, ?, ?, ?, ?)`. Thread-safe with single-writer serialization via SQLite's write lock. Batch transactions (configurable, default 100 samples per Doc 04 §9).
- **TelemetryQueryService implementation needed:** Read-only queries against `telemetry_samples` table. `querySamples` uses `WHERE entity_ref = ? AND attribute_key = ? AND timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC LIMIT ?`. `getRingStats` queries `SELECT MAX(seq), MIN(seq), COUNT(DISTINCT entity_ref), MIN(timestamp), MAX(timestamp) FROM telemetry_samples`.
- **PersistenceLifecycle implementation needed:** Opens SQLite connections with PRAGMAs (WAL mode, synchronous=NORMAL, cache_size per LTD-03), runs schema migrations, starts maintenance threads. `start()` returns CompletableFuture that completes when databases are ready.
- **MaintenanceService implementation needed:** Retention uses priority-based batch deletes with yield intervals and subscriber checkpoint safety checks. Vacuum uses `PRAGMA incremental_vacuum(N)`. StorageHealth queries file sizes and WAL stats.
- **ViewCheckpointStore implementation needed:** `SqliteViewCheckpointStore` in this module. `INSERT OR REPLACE INTO view_checkpoints (view_name, position, data, written_at, projection_version)`. Same-transaction semantics with subscriber checkpoints.
- **Subscriber PAUSED state (Doc 04 §3.4):** Implement the PAUSED subscriber lifecycle state. A PAUSED subscriber's checkpoint is protected indefinitely — retention will not delete events past its checkpoint regardless of the 24-hour grace period. Operational alerts: 7-day warning, 30-day critical for subscribers that remain PAUSED. This is the correct mechanism for planned maintenance windows (e.g., disabling a module for upgrade) — without PAUSED, a subscriber disabled for >24 hours loses checkpoint protection and may miss events on restart.
- **Aggregation engine (internal):** Not exposed in the public API. Batch-aggregates telemetry samples on a timer (5-min default), produces `telemetry_summary` domain events via `EventPublisher.publish()`. Tracks per-entity cursors in `aggregation_cursors` table.
- **Testing strategy:** Unit tests for record construction and field validation. Integration tests for TelemetryWriter round-trip (write → query), ring buffer wrap-around behavior, backup/restore round-trip, retention with priority-based deletion. Performance tests for 1,000 samples/sec sustained throughput.
- **Performance targets (from Doc 04 §10):** Checkpoint write p99 < 500ms. Telemetry write > 1,000 samples/sec sustained. Retention pass < 10 minutes for 100,000 events. Backup duration < 10 seconds for 2 GB database.
<!-- Added 2026-03-21: Architecture benchmark assessment findings R-1, R-2 -->
- **Write serialization cascade under burst load.** When N sensors report simultaneously, the single write thread serializes all N original events (~N × 1–2ms on NVMe). The State Projection then produces up to N derived `state_changed` events, which re-enter the same write thread. The Automation Engine sees triggering events only after this cascade completes. For 50 simultaneous reports, aggregate pipeline latency can reach 100–210ms on NVMe. This is an inherent property of the single-writer event-sourced model, not a bug. If measured burst latency exceeds the 500ms budget (Doc 01 §10 burst latency note), investigate batched WAL commits (grouping multiple events per transaction). See architecture benchmark assessment §A2.
- **Jackson SerializerCache synchronization under virtual threads.** Jackson's internal `SerializerCache` and `DeserializerCache` use `synchronized` for cache-miss paths. With 7+ subscribers deserializing events concurrently on virtual threads, a cache miss for a rare event type can pin carrier threads during cache population. Mitigation: pre-build all `ObjectReader`/`ObjectWriter` instances at startup for every registered event type (extends the ObjectMapper pre-warm from VT Risk Audit Finding S-08). Use explicit per-type deserialization (`objectReader.readValue(bytes)`) rather than polymorphic `@JsonTypeInfo` dispatch. Validate steady-state deserialization latency during initial throughput testing.
<!-- Added 2026-04-02: V3 spike validation results -->
- **Platform thread executor validated (V3 spike, 2026-04-02).** Executor sizing of 1 write + 2 read platform threads validated on Pi 5 NVMe. Per-submission overhead: p50=0.029 ms, p95=0.068 ms, p99=0.105 ms (well below the 1 ms investigation threshold from Doc 04 §10). Burst throughput through executor: 24,473 events/sec (244× design sustained rate). Concurrency test: zero SQLITE_BUSY errors, zero deadlocks across 21 VTs / 60 seconds. JFR pinning confirmation run (`runV3Jfr`) pending — expected zero `jdk.VirtualThreadPinned` events since all sqlite-jdbc calls are confined to platform threads.
