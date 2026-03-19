# persistence

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

    exports com.homesynapse.persistence;
}
```

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

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types for telemetry entity references | `EntityId` (field on TelemetrySample; parameter on TelemetryQueryService.querySamples). |
| **state-store** (`com.homesynapse.state`) | `requires` (non-transitive) — Persistence implements `ViewCheckpointStore` | `ViewCheckpointStore`, `CheckpointRecord` (implementation target; not re-exported). |
| **event-model** (`com.homesynapse.event`) | `requires` (non-transitive) — Event types referenced in Javadoc | `EventPriority` (referenced in RetentionResult Javadoc `@see` tag). Not used in public API signatures. |

### Gradle Dependencies

```kotlin
dependencies {
    api(project(":core:event-model"))
    api(project(":core:state-store"))
    implementation(libs.sqlite.jdbc)
}
```

The `api` scope for event-model and state-store ensures types are transitively available to consumers. The platform-api types are transitively available through event-model. SQLite JDBC is `implementation` scope because it is not exposed in the public API.

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

## Phase 3 Notes

- **TelemetryWriter implementation needed:** `SqliteTelemetryWriter` using `INSERT OR REPLACE INTO telemetry_samples (slot, seq, entity_ref, attribute_key, value, timestamp) VALUES (? % max_rows, ?, ?, ?, ?, ?)`. Thread-safe with single-writer serialization via SQLite's write lock. Batch transactions (configurable, default 100 samples per Doc 04 §9).
- **TelemetryQueryService implementation needed:** Read-only queries against `telemetry_samples` table. `querySamples` uses `WHERE entity_ref = ? AND attribute_key = ? AND timestamp >= ? AND timestamp < ? ORDER BY timestamp ASC LIMIT ?`. `getRingStats` queries `SELECT MAX(seq), MIN(seq), COUNT(DISTINCT entity_ref), MIN(timestamp), MAX(timestamp) FROM telemetry_samples`.
- **PersistenceLifecycle implementation needed:** Opens SQLite connections with PRAGMAs (WAL mode, synchronous=NORMAL, cache_size per LTD-03), runs schema migrations, starts maintenance threads. `start()` returns CompletableFuture that completes when databases are ready.
- **MaintenanceService implementation needed:** Retention uses priority-based batch deletes with yield intervals and subscriber checkpoint safety checks. Vacuum uses `PRAGMA incremental_vacuum(N)`. StorageHealth queries file sizes and WAL stats.
- **ViewCheckpointStore implementation needed:** `SqliteViewCheckpointStore` in this module. `INSERT OR REPLACE INTO view_checkpoints (view_name, position, data, written_at, projection_version)`. Same-transaction semantics with subscriber checkpoints.
- **Aggregation engine (internal):** Not exposed in the public API. Batch-aggregates telemetry samples on a timer (5-min default), produces `telemetry_summary` domain events via `EventPublisher.publish()`. Tracks per-entity cursors in `aggregation_cursors` table.
- **Testing strategy:** Unit tests for record construction and field validation. Integration tests for TelemetryWriter round-trip (write → query), ring buffer wrap-around behavior, backup/restore round-trip, retention with priority-based deletion. Performance tests for 1,000 samples/sec sustained throughput.
- **Performance targets (from Doc 04 §10):** Checkpoint write p99 < 500ms. Telemetry write > 1,000 samples/sec sustained. Retention pass < 10 minutes for 100,000 events. Backup duration < 10 seconds for 2 GB database.
