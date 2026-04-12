# Block H — State Store & State Projection

**Module:** `core/state-store`
**Package:** `com.homesynapse.state`
**Design Doc:** Doc 03 — State Store & State Projection (§4, §8)
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :core:state-store:compileJava`

---

## Strategic Context

The State Store is the primary materialized view of the event-sourced architecture. Every downstream consumer — REST API, WebSocket API, Automation Engine, Web UI — queries the State Store for current entity state instead of scanning event streams. The `StateQueryService` is the single most frequently called interface in the system. The `EntityState` record is the data unit that flows through dashboards, automation evaluation, and API responses. Getting these types right now means every consumer implements against a stable query contract.

## Scope

**IN:** All public interfaces, records, and enums defined in Doc 03 §4 and §8. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references. `module-info.java` with correct exports and requires.

**OUT:** Implementation code. Tests. Projection subscriber logic (§3.2 event processing dispatch is Phase 3). Checkpoint serialization/deserialization. Staleness scanning logic. Reconciliation pass (§3.2.1 AMD-02). Configuration parsing (§9). Metrics and structured logging implementation (§11). Health indicator implementation (§11.3).

---

## Locked Decisions

1. **Use `EntityId` from platform-api, not a separate `EntityRef` type.** Doc 03 §4.1 describes `EntityRef` as a "ULID wrapper" — this is exactly what `EntityId` already is in `com.homesynapse.platform.identity`. The state map is keyed by `EntityId`. Do NOT create a duplicate `EntityRef` wrapper. Import via `requires transitive com.homesynapse.platform;`.

2. **`Availability` is a new enum in this module.** Three values: `AVAILABLE`, `UNAVAILABLE`, `UNKNOWN`. Initialized to `UNKNOWN` at entity adoption. Updated by `availability_changed` events. This is NOT in device-model — availability is a runtime state concept owned by the State Store (Doc 03 §4.1).

3. **`EntityState` is an immutable record.** All fields as specified in Doc 03 §4.1. The `attributes` map uses `AttributeValue` from device-model (`com.homesynapse.device.AttributeValue`). The `staleAfter` field is `@Nullable Instant`. The `stale` field is `boolean` (derived at read time from `staleAfter` and wall clock per §3.8 AMD-11).

4. **`StateSnapshot` is an immutable record.** Fields as specified in Doc 03 §4.2. The `states` map is `Map<EntityId, EntityState>` (unmodifiable). The `disabledEntities` set is `Set<EntityId>` (unmodifiable).

5. **Name the view checkpoint interface `ViewCheckpointStore`.** Doc 03 §8.3 calls it `CheckpointStore`, but `com.homesynapse.event.bus.CheckpointStore` already exists (subscriber checkpoint positions from Sprint 1 Block E). To avoid developer confusion, name the view checkpoint interface `ViewCheckpointStore`. Same methods as Doc 03 §8.3: `writeCheckpoint(String viewName, long position, byte[] data)`, `readLatestCheckpoint(String viewName) → Optional<CheckpointRecord>`. This interface is consumed by state-store, implemented by the Persistence Layer (Doc 04).

6. **`CheckpointRecord` is a record in this module.** Fields: `viewName` (String), `position` (long), `data` (byte[]), `writtenAt` (Instant), `projectionVersion` (int). This is the return type of `ViewCheckpointStore.readLatestCheckpoint()`.

7. **Module requires:** `com.homesynapse.event` (for EventEnvelope, EventPublisher, DomainEvent, SubjectRef, EventPriority referenced in Javadoc), `com.homesynapse.device` (for AttributeValue), and `com.homesynapse.platform` (for EntityId, Ulid). Use `requires transitive` for platform and device since downstream consumers of state-store types will need EntityId and AttributeValue.

8. **`StateQueryService` is non-blocking.** All methods documented as lock-free reads from ConcurrentHashMap. Thread-safety is explicit in Javadoc. `getSnapshot()` documented as O(N) copy, suitable for infrequent use (initial page loads, bulk API requests), not for hot-path polling.

9. **`StateStoreLifecycle` uses `CompletableFuture<Void>`.** The `start()` method returns a `CompletableFuture<Void>` that completes when replay finishes and the view is current. Requires `java.util.concurrent` import.

10. **Collections in records must be unmodifiable.** `EntityState.attributes()` returns `Map<String, AttributeValue>`. `StateSnapshot.states()` returns `Map<EntityId, EntityState>`. `StateSnapshot.disabledEntities()` returns `Set<EntityId>`. All documented as unmodifiable in Javadoc contracts — same pattern as device-model records.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Foundation Enum

| File | Type | Notes |
|------|------|-------|
| `Availability.java` | enum | AVAILABLE, UNAVAILABLE, UNKNOWN. §4.1. Javadoc: documents each value's meaning (reachable, unreachable, initial/indeterminate). Initialized to UNKNOWN at entity adoption. Updated by availability_changed events. Independent from entity enabled/disabled status (they are orthogonal states). |

### Group 2: Data Records (depends on Availability, imports from device-model and platform-api)

| File | Type | Notes |
|------|------|-------|
| `EntityState.java` | record | Fields per Doc 03 §4.1: `entityId` (EntityId), `attributes` (Map\<String, AttributeValue\>), `availability` (Availability), `stateVersion` (long), `lastChanged` (Instant), `lastUpdated` (Instant), `lastReported` (Instant), `staleAfter` (Instant, nullable), `stale` (boolean). Javadoc documents each field's derivation source (which event type updates it), the stateVersion idempotency semantics, and the distinction between lastChanged/lastUpdated/lastReported. |
| `StateSnapshot.java` | record | Fields per Doc 03 §4.2: `states` (Map\<EntityId, EntityState\>), `viewPosition` (long), `snapshotTime` (Instant), `replaying` (boolean), `disabledEntities` (Set\<EntityId\>). Javadoc: documents that this is a point-in-time immutable copy, O(N) creation cost, and intended use cases (bulk API endpoints, WebSocket initial sync, checkpoint serialization). |
| `CheckpointRecord.java` | record | Fields per Doc 03 §8.3: `viewName` (String), `position` (long), `data` (byte[]), `writtenAt` (Instant), `projectionVersion` (int). Javadoc: documents that data is opaque serialized checkpoint content, viewName supports multiple materialized views, and projectionVersion enables version-aware checkpoint invalidation. |

### Group 3: Service Interfaces (depends on records)

| File | Type | Notes |
|------|------|-------|
| `StateQueryService.java` | interface | Methods per Doc 03 §8.1: `Optional<EntityState> getState(EntityId entityId)`, `Map<EntityId, EntityState> getStates(Set<EntityId> entityIds)`, `StateSnapshot getSnapshot()`, `long getViewPosition()`, `boolean isReady()`. Javadoc documents: lock-free reads, weak cross-entity consistency on getStates(), full consistency on getSnapshot(), viewPosition monotonicity, replaying flag semantics. No filtered queries — doc explicitly states that filtered queries (by area, label, type) are the API Layer's responsibility by joining with EntityRegistry. |
| `StateStoreLifecycle.java` | interface | Methods per Doc 03 §8.2: `CompletableFuture<Void> start()`, `void stop()`. Javadoc: start() loads checkpoint, begins replay, returns future that completes when view is current. stop() writes final checkpoint and stops projection subscriber. Consumed by Startup & Lifecycle subsystem (Doc 12). |
| `ViewCheckpointStore.java` | interface | Methods per Doc 03 §8.3: `void writeCheckpoint(String viewName, long position, byte[] data)`, `Optional<CheckpointRecord> readLatestCheckpoint(String viewName)`. Javadoc: viewName supports multiple materialized views sharing same checkpoint infrastructure, state-store uses "entity_state" as its view name. Consumed by state-store, implemented by Persistence Layer (Doc 04). NOT the same as com.homesynapse.event.bus.CheckpointStore (which stores subscriber position checkpoints). |

### Group 4: Module Info

| File | Notes |
|------|-------|
| `module-info.java` | `exports com.homesynapse.state;`. `requires transitive com.homesynapse.platform;` (for EntityId). `requires transitive com.homesynapse.device;` (for AttributeValue). `requires com.homesynapse.event;` (for Javadoc references to EventEnvelope, EventPublisher, SubjectRef, etc.). |

---

## File Placement

All files go in: `core/state-store/src/main/java/com/homesynapse/state/`
Module info: `core/state-store/src/main/java/module-info.java`

Delete the existing `package-info.java` file at `core/state-store/src/main/java/com/homesynapse/state/package-info.java` — it's a scaffold placeholder that will be replaced by real types.

---

## Cross-Module Type Dependencies

The state-store module imports types from three existing modules:

**From `com.homesynapse.platform.identity` (platform-api):**
- `EntityId` — map key type for state view, parameter type for queries

**From `com.homesynapse.device` (device-model):**
- `AttributeValue` (sealed interface) — values stored in EntityState.attributes map

**From `com.homesynapse.event` (event-model):**
- Referenced in Javadoc only (EventEnvelope, EventPublisher, SubjectRef, EventPriority, DomainEvent). Not imported as compile-time dependencies of the public API types — only referenced in `@see` tags and prose Javadoc.

**From `com.homesynapse.event.bus` (event-bus):**
- Referenced in Javadoc only. The event-bus `CheckpointStore` is a DIFFERENT interface (subscriber position checkpoints). ViewCheckpointStore Javadoc should `@see` it to distinguish the two.

---

## Javadoc Standards

Per Sprint 1 and Block G lessons:
1. Every `@param` documents nullability
2. Every type has `@see` cross-references to related types
3. Thread-safety explicitly stated on interfaces (StateQueryService: all methods lock-free; ViewCheckpointStore: thread-safe for concurrent use)
4. Class-level Javadoc explains the "why" — what role this type plays in HomeSynapse
5. Reference Doc 03 sections in class-level Javadoc: `@see` or inline `{@code Doc 03 §X.Y}`
6. Collections documented as unmodifiable in their contracts
7. `EntityState` Javadoc should document the three-timestamp model (lastChanged vs lastUpdated vs lastReported) from Doc 03 §4.1 including the "Why lastReported?" rationale
8. `StateQueryService` Javadoc should document the consistency model: per-entity reads consistent, cross-entity batch weakly consistent, snapshot fully consistent (Doc 03 §3.5)
9. `ViewCheckpointStore` Javadoc must clearly distinguish itself from the event-bus `CheckpointStore`

---

## Key Design Details for Javadoc Accuracy

These details from Doc 03 MUST be reflected accurately in Javadoc. The Coder should verify each against the design doc after writing.

1. **EntityState.stateVersion** advances on EVERY processed event, not just mutations. A `state_reported` that matches canonical state still advances stateVersion. This is the idempotency cursor (Doc 03 §4.1, §5).

2. **EntityState.attributes** — null values indicate an attribute that exists in the capability schema but has never received a report. This is NOT an error condition (Doc 03 §4.1).

3. **EntityState.staleAfter** — nullable. When null, the entity is never considered stale (default for actuators and event-driven reporters). When non-null, `stale` is true if `Instant.now().isAfter(staleAfter)` (Doc 03 §3.8 AMD-11).

4. **StateSnapshot.disabledEntities** — maintained so query consumers can distinguish disabled-with-frozen-state from enabled-with-current-state WITHOUT consulting the entity registry (Doc 03 §4.2).

5. **StateSnapshot.replaying** — true during startup replay, false after ready signal. Consumers use this to detect catching-up state. REST API returns 503 during replay unless caller accepts stale data (Doc 03 §3.6).

6. **ViewCheckpointStore.writeCheckpoint** — data parameter is opaque serialized checkpoint content. The Persistence Layer stores and retrieves it without interpreting the content. viewName "entity_state" is used by the State Store; future projections (energy analytics) use different view names (Doc 03 §8.3).

7. **StateQueryService does NOT support filtered queries.** No "get all entities in area X" or "get all entities with capability Y." Filtered queries combine state data with EntityRegistry structural metadata at the caller (API Layer). This is an explicit design decision (Doc 03 §8.1).

---

## Compile Gate

```bash
./gradlew :core:state-store:compileJava
```

Must pass with `-Xlint:all -Werror`. Run full project gate after:

```bash
./gradlew check
```

All 19 modules must still compile (no regressions from module-info changes).

---

## Estimated Size

~7 files, ~400–600 lines. This is one of the smallest blocks in Phase 2. Expect 1–1.5 hours. The primary complexity is Javadoc quality — the types are simple but the contracts documented in Doc 03 are precise and must be captured accurately.

---

## Notes

- The existing `package-info.java` scaffold file should be removed — it serves no purpose once real types exist in the package.
- `EntityState` uses `EntityId` (not `EntityRef`) as its identity field. The Doc 03 text says "EntityRef (ULID wrapper)" but EntityId IS a ULID wrapper and already exists. No need for a duplicate type.
- `ViewCheckpointStore` is named differently from Doc 03's `CheckpointStore` to avoid confusion with the event-bus `CheckpointStore`. The method signatures match Doc 03 §8.3 exactly.
- The `byte[] data` field on `CheckpointRecord` is the opaque serialized snapshot. In Phase 3, this will be JSON via Jackson (LTD-08). In Phase 2, we only define the contract — the serialization format is documented in Javadoc but not implemented.
- `StateStoreLifecycle.start()` returns `CompletableFuture<Void>` — this is the readiness signal mechanism described in Doc 03 §3.6 Phase 3. The Startup & Lifecycle subsystem (Doc 12) uses this future to gate dependent subsystem initialization.
