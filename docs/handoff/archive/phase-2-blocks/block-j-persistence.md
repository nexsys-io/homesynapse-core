# Block J — Persistence Layer

**Module:** `core/persistence`
**Package:** `com.homesynapse.persistence`
**Design Doc:** Doc 04 — Persistence Layer (§3, §4, §8) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :core:persistence:compileJava`

---

## Strategic Context

The Persistence Layer is the durable foundation beneath the event-sourced architecture. It owns SQLite management, WAL tuning, the telemetry ring store, backup/restore, retention enforcement, and checkpoint storage. Every other subsystem depends on it — EventStore writes events, State Store reads checkpoints, Integration Adapters write telemetry samples, the Automation Engine tracks pending command checkpoints, and the Startup/Lifecycle subsystem controls persistence initialization and shutdown.

The persistence module contains **public API interfaces** consumed by other modules. The **implementation** (SQLite JDBC operations, WAL management, retention scheduler, vacuum strategy) is Phase 3. This block defines the contracts that other modules compile against.

The most critical deliverable is **TelemetryWriter** — IntegrationContext in the integration-api module currently uses `@Nullable Object` as a placeholder for this interface. Once Block J produces TelemetryWriter, IntegrationContext can be updated from `Object` to the proper type. This update should happen as part of this block's execution.

## Scope

**IN:** All adapter-facing and core-facing interfaces, records, and enums from Doc 04 §8 that external modules consume. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references. `module-info.java` with correct exports and requires.

**OUT:** Implementation code. Tests. SQLite JDBC operations. WAL tuning PRAGMAs. Retention scheduler logic (§3.5). Aggregation cursor logic (§3.4). Storage pressure monitoring (§3.5). Backup creation/restore mechanics (§3.9). Schema migration DDL (§3.12). Incremental vacuum scheduling (§3.7).

---

## Locked Decisions

1. **ViewCheckpointStore already exists in state-store.** `com.homesynapse.state.ViewCheckpointStore` was created in Block H. The persistence module **implements** this interface — it does NOT redefine it. The persistence module should `requires com.homesynapse.state;` to access ViewCheckpointStore and CheckpointRecord. Do NOT create a duplicate CheckpointStore interface in persistence.

2. **TelemetryWriter is the primary cross-module deliverable.** This interface is currently a placeholder (`@Nullable Object`) in `IntegrationContext`. After creating TelemetryWriter, update IntegrationContext's `telemetryWriter` field from `Object` to `TelemetryWriter` and update the corresponding Javadoc. This requires adding `requires com.homesynapse.persistence;` to integration-api's module-info.

3. **TelemetrySample is a record in the persistence module.** Fields per Doc 04 §8.3: `entityRef` (EntityId), `attributeKey` (String), `value` (double), `timestamp` (Instant). This is the data unit that TelemetryWriter accepts and TelemetryQueryService returns.

4. **PersistenceLifecycle controls startup/shutdown and backup.** Methods: `start()` returns `CompletableFuture<Void>` (same pattern as StateStoreLifecycle), `stop()`, `createBackup(BackupOptions)` returns `BackupResult`, `restoreFromBackup(Path)`. Consumed by Startup/Lifecycle subsystem (Doc 12).

5. **TelemetryQueryService provides read access to telemetry data.** Methods: `querySamples(EntityId, String, Instant, Instant, int)` returns `List<TelemetrySample>`, `getRingStats()` returns `RingBufferStats`. Consumed by REST API (Doc 09) for telemetry chart endpoints.

6. **MaintenanceService exposes storage maintenance operations.** Methods: `runRetention()` returns `RetentionResult`, `runVacuum()` returns `VacuumResult`, `getStorageHealth()` returns `StorageHealth`. Consumed by the Observability subsystem (Doc 11) and REST API admin endpoints.

7. **BackupResult and BackupOptions are records.** BackupResult: `backupDirectory` (Path), `createdAt` (Instant), `eventsGlobalPosition` (long), `telemetryIncluded` (boolean), `integrityVerified` (boolean). BackupOptions: `includeTelemetry` (boolean), `preUpgrade` (boolean).

8. **RingBufferStats is a record.** Fields: `maxRows` (int), `currentSeq` (long), `oldestSeqInRing` (long), `distinctEntities` (int), `oldestTimestamp` (Instant), `newestTimestamp` (Instant).

9. **RetentionResult and VacuumResult are records.** RetentionResult: `eventsDeleted` (long), `diagnosticEventsDeleted` (long), `normalEventsDeleted` (long), `criticalEventsDeleted` (long), `durationMs` (long). VacuumResult: `pagesFreed` (long), `databaseSizeBeforeBytes` (long), `databaseSizeAfterBytes` (long), `durationMs` (long).

10. **StorageHealth is a record.** Fields: `eventsDatabaseSizeBytes` (long), `telemetryDatabaseSizeBytes` (long), `totalSizeBytes` (long), `budgetBytes` (long — 0 means no budget), `usagePercent` (double), `walSizeBytes` (long), `healthy` (boolean).

11. **Module requires:** `com.homesynapse.platform` (for EntityId, Ulid), `com.homesynapse.state` (for ViewCheckpointStore, CheckpointRecord — persistence implements ViewCheckpointStore). Use `requires transitive com.homesynapse.platform;` since downstream consumers need EntityId for TelemetrySample.

12. **Collections in records must be unmodifiable.** Same pattern as all previous modules.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Data Records (no inter-dependencies beyond platform-api)

| File | Type | Notes |
|------|------|-------|
| `TelemetrySample.java` | record | Fields per Doc 04 §8.3: `entityRef` (EntityId), `attributeKey` (String), `value` (double), `timestamp` (Instant). Javadoc: the atomic unit of telemetry data — a single numeric measurement from an integration adapter. Carried by TelemetryWriter.writeSamples() and returned by TelemetryQueryService.querySamples(). |
| `RingBufferStats.java` | record | Fields per Doc 04 §8.5: `maxRows` (int — configured ring buffer capacity), `currentSeq` (long — latest monotonic sequence), `oldestSeqInRing` (long — oldest sequence still in ring), `distinctEntities` (int — entities with telemetry data), `oldestTimestamp` (Instant), `newestTimestamp` (Instant). Javadoc: diagnostic snapshot of the telemetry ring store state. |
| `BackupOptions.java` | record | Fields per Doc 04 §8.4: `includeTelemetry` (boolean — whether to include homesynapse-telemetry.db), `preUpgrade` (boolean — whether this backup is a pre-upgrade safety snapshot). Javadoc: configures backup creation behavior. |
| `BackupResult.java` | record | Fields per Doc 04 §8.4: `backupDirectory` (Path — location of the backup), `createdAt` (Instant), `eventsGlobalPosition` (long — event log position at backup time), `telemetryIncluded` (boolean), `integrityVerified` (boolean — whether PRAGMA integrity_check passed). Javadoc: result of a backup creation operation. |
| `RetentionResult.java` | record | Fields per Doc 04 §8.6: `eventsDeleted` (long), `diagnosticEventsDeleted` (long), `normalEventsDeleted` (long), `criticalEventsDeleted` (long), `durationMs` (long). Javadoc: result of a retention sweep — documents how many events were purged by priority tier. |
| `VacuumResult.java` | record | Fields per Doc 04 §8.6: `pagesFreed` (long), `databaseSizeBeforeBytes` (long), `databaseSizeAfterBytes` (long), `durationMs` (long). Javadoc: result of an incremental vacuum operation. |
| `StorageHealth.java` | record | Fields per Doc 04 §8.6: `eventsDatabaseSizeBytes` (long), `telemetryDatabaseSizeBytes` (long), `totalSizeBytes` (long), `budgetBytes` (long — 0 means no storage budget configured), `usagePercent` (double), `walSizeBytes` (long), `healthy` (boolean). Javadoc: snapshot of storage utilization for health monitoring. |

### Group 2: Service Interfaces (depends on records)

| File | Type | Notes |
|------|------|-------|
| `TelemetryWriter.java` | interface | Doc 04 §8.3. Single method: `void writeSamples(List<TelemetrySample> samples)` — batch write of telemetry samples to the ring store. Javadoc: thread-safe, may be called from any integration adapter's thread. Samples are written in monotonic sequence order. The ring store overwrites oldest samples when capacity is reached. Only available to integrations that declare `RequiredService.TELEMETRY_WRITER` and `DataPath.TELEMETRY`. |
| `TelemetryQueryService.java` | interface | Doc 04 §8.5. Two methods: `List<TelemetrySample> querySamples(EntityId entityRef, String attributeKey, Instant from, Instant to, int maxResults)` — query raw telemetry samples within a time range; `RingBufferStats getRingStats()` — diagnostic snapshot. Javadoc: consumed by REST API for telemetry chart endpoints. Thread-safe read-only interface. |
| `PersistenceLifecycle.java` | interface | Doc 04 §8.4. Four methods: `CompletableFuture<Void> start()` — initialize databases, run schema migrations, start background tasks; `void stop()` — flush WAL, finalize checkpoints, close connections; `BackupResult createBackup(BackupOptions options)` — create a timestamped backup directory; `void restoreFromBackup(Path backupDirectory)` — restore databases from a backup. Javadoc: consumed by Startup/Lifecycle subsystem (Doc 12). start() returns a future that completes when databases are ready for use. |
| `MaintenanceService.java` | interface | Doc 04 §8.6. Three methods: `RetentionResult runRetention()` — execute priority-based event retention sweep; `VacuumResult runVacuum()` — execute incremental vacuum; `StorageHealth getStorageHealth()` — return current storage utilization snapshot. Javadoc: consumed by Observability subsystem for health checks and by REST API for admin endpoints. Thread-safe. |

### Group 3: Module Info + Dependency Updates

| File | Notes |
|------|-------|
| `module-info.java` | `exports com.homesynapse.persistence;`. `requires transitive com.homesynapse.platform;` (for EntityId in TelemetrySample). `requires com.homesynapse.state;` (for ViewCheckpointStore that persistence implements). |
| `build.gradle.kts` | Verify current dependencies. May need `api(project(":platform:platform-api"))` if not transitively available through event-model. |

### Group 4: IntegrationContext Update (cross-module)

| File | Notes |
|------|-------|
| `IntegrationContext.java` (in integration-api) | Update the `telemetryWriter` field from `Object` to `TelemetryWriter`. Update Javadoc to reference the proper type instead of "placeholder". |
| `module-info.java` (in integration-api) | Add `requires com.homesynapse.persistence;` for the TelemetryWriter type. |
| `build.gradle.kts` (in integration-api) | Add `api(project(":core:persistence"))` dependency. |

---

## File Placement

All files go in: `core/persistence/src/main/java/com/homesynapse/persistence/`
Module info: `core/persistence/src/main/java/module-info.java` (create new)

Delete the existing `package-info.java` file at `core/persistence/src/main/java/com/homesynapse/persistence/package-info.java` — it's a scaffold placeholder that will be replaced by real types.

---

## Cross-Module Type Dependencies

The persistence module imports types from two existing modules:

**From `com.homesynapse.platform.identity` (platform-api):**
- `EntityId` — used in TelemetrySample.entityRef

**From `com.homesynapse.state` (state-store):**
- `ViewCheckpointStore` — the persistence module implements this interface
- `CheckpointRecord` — the return type of ViewCheckpointStore.readLatestCheckpoint()

**Exported to (downstream consumers):**
- `com.homesynapse.integration` (integration-api) — IntegrationContext uses TelemetryWriter
- Future: `com.homesynapse.rest` (rest-api) — TelemetryQueryService for chart endpoints
- Future: `com.homesynapse.observability` — MaintenanceService.getStorageHealth() for health indicators

---

## Javadoc Standards

Per Sprint 1, Block G, Block H, and Block I lessons:
1. Every `@param` documents nullability
2. Every type has `@see` cross-references to related types
3. Thread-safety explicitly stated on interfaces (TelemetryWriter: thread-safe; TelemetryQueryService: thread-safe read-only)
4. Class-level Javadoc explains the "why" — what role this type plays in HomeSynapse
5. Reference Doc 04 sections in class-level Javadoc
6. TelemetryWriter Javadoc should document the ring store overwrite behavior and batch write semantics
7. PersistenceLifecycle Javadoc should document the startup sequence (schema migration → WAL mode → checkpoint recovery → ready)
8. MaintenanceService Javadoc should document that retention is priority-based (DIAGNOSTIC expires first, then NORMAL, CRITICAL last)
9. TelemetrySample Javadoc should document that `value` is always a double — non-numeric telemetry is not supported (Doc 04 §3.3)

---

## Key Design Details for Javadoc Accuracy

1. **TelemetryWriter.writeSamples() is a batch operation.** Adapters accumulate samples and write in batches (configurable, default 100). The ring store assigns monotonic sequences. Do not assume single-sample writes.

2. **The telemetry ring store uses slot-based overwrite.** `slot = seq % max_rows`. When the ring is full, the oldest slot is overwritten. This is NOT an append log — it is a circular buffer with fixed capacity (default: 100,000 rows per Doc 04 §9).

3. **PersistenceLifecycle.start() is sequenced early.** The Persistence Layer starts before the Event Bus, State Store, and all other subscribers (Doc 12 §3.1). It must be ready before any events can be published or checkpoints can be read.

4. **BackupResult.integrityVerified** indicates whether `PRAGMA integrity_check` passed on the backup copy, not on the live database. The backup process runs integrity check on the copied file to catch any corruption that occurred during the hot backup.

5. **Retention is priority-based.** DIAGNOSTIC events (7-day default) expire first. NORMAL events (30-day default) expire next. CRITICAL events (90-day default) expire last. The retention sweep is interruptible (yields every batch_size deletions per Doc 04 §9).

6. **ViewCheckpointStore is implemented by the Persistence Layer.** The `view_checkpoints` table in homesynapse-events.db stores checkpoint data. The persistence module provides the implementation; the state-store module provides the interface. This is the same consumed-in-one-module-implemented-in-another pattern used throughout HomeSynapse.

---

## Compile Gate

```bash
./gradlew :core:persistence:compileJava
```

Must pass with `-Xlint:all -Werror`. After the IntegrationContext update, run full project gate:

```bash
./gradlew compileJava
```

All 19 modules must still compile (no regressions from module-info changes or the IntegrationContext type change).

---

## Estimated Size

~11 files (7 records + 4 interfaces + module-info), ~400-600 lines. This is a medium-small block. The primary complexity is getting the TelemetryWriter contract right (ring store semantics, batch writes) and correctly wiring the IntegrationContext update across modules. Expect 1.5–2 hours.

---

## Notes

- `ViewCheckpointStore` is NOT created in this block — it already exists in state-store (Block H). Do not duplicate.
- `CheckpointRecord` is NOT created in this block — it already exists in state-store (Block H). Do not duplicate.
- The `TelemetryWriter` interface is the key deliverable that unblocks the IntegrationContext placeholder.
- After creating TelemetryWriter, the Coder MUST update IntegrationContext in integration-api to replace `Object` with `TelemetryWriter`. This is a cross-module edit that requires updating module-info and build.gradle in both modules.
- The existing `build.gradle.kts` has `implementation(libs.sqlite.jdbc)` — this is correct for the implementation (Phase 3) but not needed for Phase 2 interface specification. Leave it in place; it doesn't affect interface compilation.
- The `package-info.java` scaffold should be deleted before creating real types.
- `Path` type used in BackupResult and PersistenceLifecycle comes from `java.nio.file` — no additional module requires needed (java.base).
