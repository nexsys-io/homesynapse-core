# state-store

## Purpose

The state-store module defines the materialized view layer of HomeSynapse's event-sourced architecture. It provides the types and contracts for querying current entity state, managing view lifecycle (startup replay and shutdown checkpointing), and persisting view checkpoints for crash recovery. Every downstream consumer — REST API, WebSocket API, Automation Engine, Web UI — queries the `StateQueryService` for current entity state instead of scanning event streams. The `EntityState` record is the data unit that flows through dashboards, automation evaluation, and API responses.

## Design Doc Reference

**Doc 03 — State Store & State Projection** is the governing design document:
- §4.1: EntityState record definition, attribute map, three-timestamp model, staleness
- §4.2: StateSnapshot record definition, disabled entities, replaying flag
- §8.1: StateQueryService interface specification, consistency model
- §8.2: StateStoreLifecycle interface specification, startup/shutdown sequence
- §8.3: ViewCheckpointStore interface specification (renamed from CheckpointStore to avoid confusion with event-bus CheckpointStore)

## JPMS Module

```
module com.homesynapse.state {
    requires transitive com.homesynapse.platform;
    requires transitive com.homesynapse.device;
    requires com.homesynapse.event;

    exports com.homesynapse.state;
}
```

Both `requires transitive` declarations mean any module that reads `com.homesynapse.state` automatically gets access to all identity types (`EntityId`, etc.) and all device model types (`AttributeValue`, etc.) without needing to declare those dependencies themselves. The non-transitive `requires com.homesynapse.event` provides access to event types referenced in Javadoc (`@see` tags) but not exposed in the public API signatures.

## Package Structure

- **`com.homesynapse.state`** — All types in a single flat package. Contains: the `EntityState` record (primary state data unit), `StateSnapshot` (bulk state copy), `CheckpointRecord` (checkpoint persistence), `Availability` enum (runtime reachability), and the three service interfaces (`StateQueryService`, `StateStoreLifecycle`, `ViewCheckpointStore`).

## Complete Type Inventory

### Enum

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Availability` | enum (3 values) | Runtime availability status of an entity's backing device | Values: `AVAILABLE` (reachable), `UNAVAILABLE` (unreachable), `UNKNOWN` (initial/indeterminate). Initialized to `UNKNOWN` at entity adoption. Updated by `availability_changed` events. Orthogonal to entity enabled/disabled status. |

### Data Records

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `EntityState` | record (9 fields) | Immutable materialized state of a single entity at a point in time | Fields: `entityId` (EntityId), `attributes` (Map\<String, AttributeValue\>, unmodifiable), `availability` (Availability), `stateVersion` (long), `lastChanged` (Instant), `lastUpdated` (Instant), `lastReported` (Instant), `staleAfter` (Instant, **nullable**), `stale` (boolean, derived at read time). |
| `StateSnapshot` | record (5 fields) | Point-in-time immutable copy of the entire materialized state view | Fields: `states` (Map\<EntityId, EntityState\>, unmodifiable), `viewPosition` (long), `snapshotTime` (Instant), `replaying` (boolean), `disabledEntities` (Set\<EntityId\>, unmodifiable). |
| `CheckpointRecord` | record (5 fields) | Stored checkpoint for a materialized view | Fields: `viewName` (String), `position` (long), `data` (byte[], opaque), `writtenAt` (Instant), `projectionVersion` (int). |

### Service Interfaces

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `StateQueryService` | interface | Read-only query interface for materialized entity state | `getState(EntityId)` → `Optional<EntityState>`, `getStates(Set<EntityId>)` → `Map<EntityId, EntityState>`, `getSnapshot()` → `StateSnapshot`, `getViewPosition()` → `long`, `isReady()` → `boolean`. |
| `StateStoreLifecycle` | interface | Lifecycle management — startup replay and shutdown checkpointing | `start()` → `CompletableFuture<Void>`, `stop()`. |
| `ViewCheckpointStore` | interface | Durable storage for materialized view checkpoints | `writeCheckpoint(String viewName, long position, byte[] data)`, `readLatestCheckpoint(String viewName)` → `Optional<CheckpointRecord>`. |

**Total: 7 public types + 1 module-info.java = 8 Java files.**

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types for state map keys and query parameters | `EntityId` (fields on EntityState, StateSnapshot; parameters on StateQueryService). |
| **device-model** (`com.homesynapse.device`) | `requires transitive` — AttributeValue hierarchy for entity attribute storage | `AttributeValue` (values in EntityState.attributes map). |
| **event-model** (`com.homesynapse.event`) | `requires` (non-transitive) — Event types referenced in Javadoc only | `EventEnvelope`, `EventTypes` (referenced in `@see` tags and prose Javadoc). Not used in public API signatures. |

## Consumers

### Planned consumers (from design doc dependency graph):
- **integration-runtime** — Will use `StateQueryService` (read-only) via `IntegrationContext` for integrations that need to read current entity state.
- **automation** — Will use `StateQueryService` for trigger evaluation against current entity state.
- **rest-api** — Will use `StateQueryService` for REST endpoints that return entity state. Will check `isReady()` to return 503 during replay.
- **websocket-api** — Will use `StateQueryService.getSnapshot()` for initial sync on client connect, then stream individual state changes.
- **persistence** — Will implement `ViewCheckpointStore` backed by SQLite.
- **lifecycle** — Will call `StateStoreLifecycle.start()` during ordered startup and `stop()` during shutdown.

## Cross-Module Contracts

- **`StateQueryService` methods are lock-free reads from ConcurrentHashMap.** All methods are safe for concurrent use from any thread including virtual threads. No locking, no blocking.
- **Consistency model has three tiers.** Per-entity reads (`getState`) are consistent. Cross-entity batch reads (`getStates`) are weakly consistent (individual entities consistent, batch may span projection ticks). Snapshot reads (`getSnapshot`) are fully consistent (all entity states correspond to the same view position).
- **`EntityState.attributes()` returns an unmodifiable map.** Values may be `null` for attributes that exist in the capability schema but have never received a report. This is not an error condition.
- **`EntityState.stateVersion` advances on every processed event, not just mutations.** A `state_reported` event that matches canonical state still advances `stateVersion`. This is the idempotency cursor.
- **`EntityState.stale` is derived at read time.** When `staleAfter` is non-null, `stale = Instant.now().isAfter(staleAfter)`. When `staleAfter` is null, `stale` is always `false`. The Phase 3 implementation must compute this at query time, not at projection time.
- **`StateSnapshot.disabledEntities` enables client-side distinction.** Consumers can distinguish disabled-with-frozen-state from enabled-with-current-state without consulting EntityRegistry.
- **`StateSnapshot.replaying` gates downstream behavior.** REST API should return 503 when `replaying` is true (unless caller accepts stale data). Automations should not fire during replay.
- **`StateStoreLifecycle.start()` returns a future that gates dependent startup.** The Startup & Lifecycle subsystem (Doc 12) blocks dependent subsystems until this future completes.
- **`ViewCheckpointStore` is NOT `com.homesynapse.event.bus.CheckpointStore`.** The event-bus CheckpointStore stores a single `long` position per subscriber. ViewCheckpointStore stores opaque serialized view state (byte[]) keyed by view name. They serve different purposes and live in different modules.
- **`StateQueryService` does NOT support filtered queries.** No "get all entities in area X" or "get all entities with capability Y." Filtered queries combine state data with EntityRegistry structural metadata at the API Layer.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-04** | EntityId (typed ULID wrapper from platform-api) used as state map key and query parameter. |
| **LTD-08** | Jackson JSON for checkpoint serialization (Phase 3). CheckpointRecord.data is opaque byte[] in Phase 2. |
| **LTD-11** | No `synchronized` — Phase 3 implementation must use ConcurrentHashMap for lock-free reads and ReentrantLock/StampedLock for write serialization if needed. |
| **INV-ES-02** | State is always derivable from events. The state store is a materialized view that can be rebuilt by replaying the event log from a checkpoint. |
| **INV-ES-05** | At-least-once delivery with subscriber idempotency. The state projection must handle duplicate events during recovery (stateVersion provides the idempotency cursor). |

## Sealed Hierarchies

None. This module contains no sealed types.

## Key Design Decisions

1. **`EntityId` from platform-api, not a separate `EntityRef` type.** Doc 03 §4.1 describes "EntityRef" as a ULID wrapper — this is exactly what `EntityId` already is. No duplicate wrapper created. The state map is keyed by `EntityId`.

2. **`Availability` is a new enum in this module, not in device-model.** Availability is a runtime state concept (is the device reachable right now?) owned by the State Store. It is not a structural property of the device model. Orthogonal to enabled/disabled status.

3. **`ViewCheckpointStore` renamed from Doc 03's `CheckpointStore`.** The event-bus module already has `com.homesynapse.event.bus.CheckpointStore` for subscriber position checkpoints. To avoid developer confusion, the view checkpoint interface is named `ViewCheckpointStore`. Method signatures match Doc 03 §8.3 exactly.

4. **Three timestamps on EntityState (lastChanged, lastUpdated, lastReported).** `lastChanged` tracks meaningful state changes. `lastUpdated` tracks projection currency. `lastReported` tracks adapter communication freshness — needed because a sensor reporting the same temperature every 30s is not stale, even if `lastChanged` is hours old.

5. **`EntityState.stale` is a derived field.** Computed from `staleAfter` and wall clock at read time, not stored. This avoids the state store needing a background timer to update staleness — the query caller always gets a current answer.

6. **`StateQueryService` does not support filtered queries.** Filtering by area, label, or capability requires joining state data with EntityRegistry structural metadata. This is explicitly the API Layer's responsibility, keeping the State Store focused on fast key-based lookups.

## Gotchas

**GOTCHA: `EntityState.staleAfter` is nullable.** When `null`, the entity is never considered stale (actuators, event-driven reporters). When non-null, compare against `Instant.now()` to derive `stale`. Do not assume non-null.

**GOTCHA: `EntityState.attributes` values may be `null`.** A `null` value for a key in the attributes map means the attribute exists in the capability schema but has never received a report. This is NOT an error — it's the initial state after entity adoption. Do not filter out null values.

**GOTCHA: `EntityState.stateVersion` advances on EVERY event, not just mutations.** A `state_reported` that matches canonical state still advances stateVersion. Do not use stateVersion to detect "something changed" — use `lastChanged` timestamp comparison for that. stateVersion is the idempotency/currency cursor.

**GOTCHA: `StateSnapshot.states` and `StateSnapshot.disabledEntities` must be unmodifiable.** Phase 3 implementation must use `Map.copyOf()` / `Set.copyOf()` or `Collections.unmodifiableMap()` / `Collections.unmodifiableSet()`. Same pattern as device-model records.

**GOTCHA: `ViewCheckpointStore` ≠ `CheckpointStore`.** They are different interfaces in different modules for different purposes. ViewCheckpointStore stores serialized view state (byte[]). CheckpointStore (event-bus) stores subscriber positions (long). Javadoc cross-references both to help developers distinguish them.

**GOTCHA: `CheckpointRecord.data` is opaque `byte[]`.** The Persistence Layer stores and retrieves this data without interpreting it. In Phase 3, the State Store serializes/deserializes this as JSON via Jackson. The ViewCheckpointStore implementation must not attempt to parse or validate the content.

**GOTCHA: `StateStoreLifecycle.start()` blocks dependent subsystems.** The returned `CompletableFuture<Void>` is the readiness signal. Dependent subsystems (REST API, WebSocket API, Automation Engine) must not start serving until this future completes. The lifecycle module coordinates this ordering.

## Phase 3 Notes

- **StateQueryService implementation needed:** `ConcurrentHashMapStateStore` or similar. Uses `ConcurrentHashMap<EntityId, EntityState>` for lock-free reads. `getSnapshot()` takes a read-consistent copy under a `StampedLock` or similar. Implements both `StateQueryService` and an internal `StateProjection` interface for write-side updates from event subscribers.
- **State projection subscriber needed:** Subscribes to the event bus, processes `state_reported`, `state_changed`, `availability_changed`, `entity_enabled`, `entity_disabled` events. Maintains the ConcurrentHashMap. Checks `ProcessingMode` for side-effect suppression during REPLAY.
- **ViewCheckpointStore implementation needed:** `SqliteViewCheckpointStore` in the persistence module. Simple key-value store with `viewName` as key. Stores `position`, `data` (BLOB), `writtenAt`, `projectionVersion`.
- **Checkpoint serialization:** EntityState → JSON → byte[] via Jackson. The `CheckpointRecord.data` field contains the serialized Map<EntityId, EntityState>. Must handle nullable `staleAfter` fields in serialization.
- **Staleness computation:** `EntityState.stale` must be computed at read time in `StateQueryService.getState()` and `getSnapshot()`. The projection stores `staleAfter` but the `stale` boolean is derived from `Instant.now().isAfter(staleAfter)` at query time.
- **Testing strategy:** Unit tests for EntityState/StateSnapshot record construction and field validation. Integration tests for StateQueryService round-trip (write via projection, read via query). Concurrency tests for lock-free read safety. Checkpoint round-trip tests (serialize → store → retrieve → deserialize).
- **Performance targets (from Doc 03 §8):** `StateQueryService.getState()` must complete within 100μs. `getSnapshot()` O(N) but acceptable up to 10,000 entities within 10ms. Projection throughput: 50,000 events/second sustained during replay.
