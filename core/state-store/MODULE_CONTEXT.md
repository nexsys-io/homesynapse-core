# state-store — `com.homesynapse.state` — 20 public + 1 package-private types — Materialized view over event stream, EntityState projection, availability tracking, checkpoint policy (sealed), bounded-window projection advancer, M3.5a vertical-slice StateProjection subscriber, M3.5b-wiring checkpoint-source injection seam, M3.6d-a readiness-source seam

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
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.event.bus;

    requires org.slf4j;

    exports com.homesynapse.state;
}
```

All four `requires transitive` declarations mean any module that reads `com.homesynapse.state` automatically gets access to all identity types (`EntityId`, etc.), all device model types (`AttributeValue`, etc.), all event types (`EventEnvelope`, etc.), and the event-bus runtime types (`Subscriber`, `SubscriberMode`, etc.) without needing to declare those dependencies themselves. The transitive event-model dependency is load-bearing: `EventEnvelope` is the parameter type for `ProjectionAdvancer.advance`'s processor callback (AMD-41 §3.2.1), surfacing event-model in state-store's public API. The transitive event-bus dependency (added M3.5a, 2026-05-18) is required because `StateProjection` implements `Subscriber` and exposes `SubscriberMode` on its public `setMode`/`currentMode` API. This is consistent with LD#10 (inter-module `requires` are `requires transitive` by default). The `requires org.slf4j` directive is non-transitive — `StateProjection` uses SLF4J internally for logging, but no public API exposes SLF4J types.

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

### Checkpoint Policy and Projection Cursor (M2→M3 bridge, 2026-05-15)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `CheckpointPolicy` | sealed interface | Determines when a subscriber should flush its checkpoint to durable storage. | Permits: `FixedCheckpointPolicy`, `AdaptiveCheckpointPolicy`. Single method: `shouldCheckpoint(long eventsSinceLastCheckpoint, Duration timeSinceLastCheckpoint, long readerLag) → boolean`. Carries the dual-purpose model: crash-recovery bounds AND WAL release (AMD-38). Subscribers must remain idempotent for up to `eventThreshold` events. |
| `FixedCheckpointPolicy` | record (2 fields) implements `CheckpointPolicy` | Static max(N, T) checkpoint policy. Ignores `readerLag`. | Fields: `eventThreshold` (int, &gt; 0), `maxInterval` (`Duration`, positive). Compact constructor validates both. Public constant: `HOME_DEFAULT = FixedCheckpointPolicy(200, Duration.ofSeconds(2))` per AMD-38 (provisional pending D1 WAL pathology spike). `shouldCheckpoint` returns true when either threshold is met. |
| `AdaptiveCheckpointPolicy` | record (3 fields) implements `CheckpointPolicy` | Pressure-aware policy: switches between two `FixedCheckpointPolicy` instances based on reader lag. Reserved for post-MVP. | Fields: `normalMode` (`FixedCheckpointPolicy`, non-null), `pressureMode` (`FixedCheckpointPolicy`, non-null), `pressureThreshold` (long, &gt; 0). `shouldCheckpoint` delegates to `normalMode` when `readerLag < pressureThreshold`, else `pressureMode`. M3 ships with `FixedCheckpointPolicy` only — this type exists in the sealed hierarchy to keep the interface stable. |
| `ProjectionAdvancer` | interface | Cursor runner for the State Projection. Reads a bounded chunk of events and applies them to the projection's state model. | Single method: `advance(long fromPosition, int maxRows, Consumer<EventEnvelope> processor) → AdvanceResult`. Constant: `DEFAULT_MAX_ROWS = 500`. Contract: each call opens an independent short-lived read transaction (≤ 2 s, ≤ 500 rows), invokes `processor.accept(envelope)` for each event in `globalPosition` order inside the read tx, then closes the tx before returning. Processor MUST NOT call `EventPublisher.publish` or perform writes (AMD-41 §3.2.1 enforcement point). Derived publishes are buffered by the processor and emitted after `advance` returns (two-phase discipline). No cursors held between calls — bounded-window discipline prevents WAL checkpoint starvation (AMD-38). |
| `AdvanceResult` | record (3 fields) | Result of one `ProjectionAdvancer.advance` call. | Fields: `lastProcessedPosition` (long, ≥ 0), `eventsProcessed` (int, ≥ 0), `hasMore` (boolean). Compact constructor validates non-negativity. `hasMore = false` AND `eventsProcessed = 0` signals "caught up to writer head"; caller may park until next event publishes. |

### M3.5a — Vertical-Slice StateProjection Types (2026-05-18)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ProjectionId` | record (1 field) | Typed wrapper for projection view identifiers | Single field: `value` (String, non-null, non-blank). Used as the view name passed to `ViewCheckpointStore.writeCheckpoint`/`readLatestCheckpoint`. Plain String (not Ulid) because projection names are stable infrastructure identifiers. |
| `StateStore` | interface (4 methods) | Port for materialized entity state storage | Methods: `get(EntityId) → Optional<EntityState>`, `put(EntityId, EntityState)`, `getAll() → Map<EntityId, EntityState>`, `clear()`. The `clear()` method supports the reconciliation pass (AMD-41 §3.2.4) by atomically wiping all entries when the persisted checkpoint's `projectionVersion` mismatches the running code. M3.5a fixture: `InMemoryStateStore` (testFixtures). M3.5b adds `SqliteStateStore`. |
| `DerivationContext` | record (3 fields) | Read-only context passed to `DerivationRule.evaluate` | Fields: `priorState` (`EntityState`, **nullable** — null for first event on a new entity), `envelope` (`EventEnvelope`, the inbound event), `clock` (`Clock`, for time-dependent derivation). Compact constructor validates non-null on `envelope` and `clock`. |
| `DerivationRule` | functional interface | Strategy for deriving downstream events from inbound envelopes (DEC-M3-10) | Single method: `evaluate(DerivationContext) → List<EventDraft>`. Contract: MUST NOT call `EventPublisher.publish()`, MUST NOT mutate `StateStore`, MUST be deterministic per INV-PROJ-01. Returns an empty list when no derivation applies. |
| `DerivedPublishGate` | functional interface | Throttling gate for derived publishes from `StateProjection` (AMD-43 §3.6.4, DEC-M3-08) | Single method: `acquire() throws InterruptedException`. Static factory `unbounded()` returns a no-op gate. **Exists because the event-bus's `DerivedWriteRateLimit` is package-private (M3.3) and cannot be referenced from this module — see Gotchas.** |
| `SelfProducedFilter` | **package-private** final class | Per-subscriber filter suppressing re-entrant delivery of derived events (AMD-41 §3.2.2, INV-SUB-ISO-06) | Constructor `(Clock, Duration)`. Methods: `record(Ulid)`, `isSelfProduced(Ulid, SubscriberMode)`, `size()` (test-only). Static constant: `DEFAULT_TTL = Duration.ofSeconds(60)`. Internal `HashMap<Ulid, Instant>` — single-threaded on subscriber VT. Lazy eviction sweeps O(N) on every `isSelfProduced` call. REPLAY/TRANSITION bypass returns `false` unconditionally. |
| `StateProjection` | public final class implements `Subscriber` | M3.5a core: state-store projection subscriber that materializes EntityState from the event log | Public static factory `create(...)` (M3.5b-wiring: 11 parameters including the new `StateCheckpointSource`). Package-private constructor takes an explicit `SelfProducedFilter` for in-package tests (12 parameters). Public methods: `onEvent(EventEnvelope)`, `onCaughtUp()`, `setMode(SubscriberMode)`, `currentMode()`, `projectionId()`, `projectionVersion()`, `cursorPosition()`, `processBatch(int) → AdvanceResult`. Two-phase discipline (AMD-41 §3.2.1): READ → apply inbound to state → PUBLISH (LIVE only) → apply derived to state → CHECKPOINT. Lazy initialization on first `onEvent` (no I/O in constructor). Reconciliation on `projectionVersion` mismatch (AMD-41 §3.2.4) — version now consulted via `StateCheckpointSource.loadedProjectionVersion()`, NOT `CheckpointRecord.projectionVersion()` (which is a sentinel). System property `homesynapse.projection.allow_stale_snapshots` controls the reconciliation escape hatch. Checkpoint writes call `StateCheckpointSource.serializeCheckpoint(projectionVersion)` to obtain the real serialized payload (replacing the M3.5a `byte[0]` stub). Advisory WARN log fires when serialized payload exceeds `CHECKPOINT_SIZE_WARN_BYTES = 10 * 1024 * 1024` bytes — non-blocking. |

### M3.5b-wiring — Projection-checkpoint injection seam (2026-05-18)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `StateCheckpointSource` | public interface (2 methods + static factory) | Injection seam decoupling `StateProjection` from the persistence module's `SqliteStateStore`. AMD-41 §3.2.3–3.2.4. | Methods: `serializeCheckpoint(int projectionVersion) → byte[]` (called at checkpoint cadence to obtain the opaque data payload for `ViewCheckpointStore.writeCheckpoint`) and `loadedProjectionVersion() → int` (returns the version recovered from the checkpoint data blob — the AUTHORITATIVE source per AMD-41 §3.2.4, since `CheckpointRecord.projectionVersion()` is a sentinel hardcoded to 1 by both store implementations). Static factory `stub()` returns a no-op source that serializes to `new byte[0]` and reports `loadedProjectionVersion() == 0`. Method name `serializeCheckpoint` (not `serialize`) was deliberately chosen to avoid a JPMS visibility-clash when `SqliteStateStore` later promotes its existing package-private `serialize(int)` to public and declares `implements StateCheckpointSource`. **M3.6d-a completed the promotion: `SqliteStateStore` now declares `implements StateCheckpointSource` and `serialize(int)` has been renamed to `serializeCheckpoint(int)` with public visibility.** |

### M3.6d-a — Readiness-source seam (2026-05-20)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ReadinessSource` | public interface (1 method) | Reports the State Projection's lifecycle mode to query-side adapters (M3.6e's `MaterializedStateQueryService`) so REST and WebSocket layers can gate traffic until the projection reaches LIVE. | Method: `mode() → SubscriberMode` (never null). Zero new module dependencies — `SubscriberMode` is already transitively available through state-store's `requires transitive com.homesynapse.event.bus`. The lifecycle module's composition root implements this interface by delegating to `StateProjection.currentMode()`. Distinct from `StateQueryService.isReady()` (which returns boolean) — `ReadinessSource.mode()` exposes the full mode so consumers can distinguish "warming up" (COLD/REPLAY/TRANSITION) from "halted" (SUSPENDED) for nuanced 503 messaging. |

**Total: 20 public types + 1 package-private type (`SelfProducedFilter`) + 1 module-info.java = 22 production Java files** (M3.6d-a added 1 public type — `ReadinessSource` — to the M3.5b-wiring baseline of 19+1).

**testFixtures additions (M3.5a):**
- `InMemoryStateStore` (`com.homesynapse.state` package — **not** the `.test` sub-package per the brief's convention for fixture implementations)
- `InMemoryProjectionAdvancer` (`com.homesynapse.state` package)
- `SubscriberContractTest` (`com.homesynapse.state.test`, abstract, 4 tests + `SpyPublisher` recording wrapper)
- `StateProjectionContractTest extends SubscriberContractTest` (`com.homesynapse.state.test`, abstract, 9 additional tests)

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types for state map keys and query parameters | `EntityId` (fields on EntityState, StateSnapshot; parameters on StateQueryService; field on `StateProjection.applyToState`). `Ulid` (used internally by SelfProducedFilter). |
| **device-model** (`com.homesynapse.device`) | `requires transitive` — AttributeValue hierarchy for entity attribute storage | `AttributeValue`, `StringValue` (`StateProjection.applyToState` constructs `StringValue` from state_changed payloads). |
| **event-model** (`com.homesynapse.event`) | `requires transitive` (M3.5a — upgraded from non-transitive) — Event types in public API | `EventEnvelope` (Subscriber.onEvent parameter, ProjectionAdvancer processor callback), `EventDraft` (DerivationRule return), `EventPublisher` (StateProjection constructor), `CausalContext` (chain construction for derived publishes), `StateReportedEvent`/`StateChangedEvent`/`AvailabilityChangedEvent` (pattern matched in `StateProjection.applyToState`), `SubjectRef`/`SubjectType` (subject entity resolution), `EventTypes` (string constants for derived drafts), `SequenceConflictException` (caught when publish loses sequence race). |
| **event-bus** (`com.homesynapse.event.bus`) | `requires transitive` (added M3.5a, 2026-05-18) — Subscriber callback contract and lifecycle mode | `Subscriber` (implemented by StateProjection), `SubscriberMode` (mode FSM tracked by StateProjection, exposed on public `setMode`/`currentMode`). **Not used directly:** `DerivedWriteRateLimit` (package-private to event-bus; state-store works around via the `DerivedPublishGate` interface — see Gotchas). |
| **slf4j-api** | `requires` (non-transitive, implementation scope) — Logging only | `Logger`, `LoggerFactory` (used internally by StateProjection). No public API exposure. |

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

## Amendments in force

| Amendment | Status | Relevance to this module |
|---|---|---|
| **AMD-41** — State Projection Execution Model | APPLIED (2026-05-16) | Mandates the two-phase read-then-publish-then-checkpoint discipline (§3.2.1), the in-memory `SelfProducedFilter` with 60-second TTL and REPLAY/TRANSITION bypass (§3.2.2), the MVP `ViewCheckpointStore`-only checkpoint mechanism with `SqliteSnapshotStore` deferred until empirical replay > 5 s (§3.2.3), and the reconciliation pass on `projectionVersion` mismatch (§3.2.4) that uses the existing opaque `CheckpointRecord.data` byte slot for `reconciledAt`/`fromVersion`/`toVersion` metadata (no schema migration). New types in this module per M3 deliverables: `StateProjection`, `SelfProducedFilter`, `DerivationRule`, `DerivationContext`, `StateStore` (port), `DerivedWriteRateLimit`, `ProjectionId`, `ReadinessSource`, `MaterializedStateQueryService` — all to live in the flat `com.homesynapse.state` package. |
| **AMD-42** — Subscriber Lifecycle and Isolation | APPLIED (2026-05-16) | `StateProjection` implements the `Subscriber` callback contract from `core/event-bus` (M3.1) and observes the `COLD → REPLAY → TRANSITION → LIVE → SUSPENDED` mode state machine. The projection is REPLAY-mode-aware: its derivation logic defers `state_changed` publishes until LIVE (the REPLAY-mode subscriber MUST NOT publish — defence-in-depth check throws `IllegalStateException`). Per-subscriber resources for this module: one `SelfProducedFilter` instance (INV-SUB-ISO-06). |
| **AMD-43** — Backpressure and Observability | APPLIED (2026-05-16) | The projection wraps every `EventPublisher.publish()` call in a `DerivedWriteRateLimit` token bucket (200/s default per `StateProjection` instance). Refill ticks use the injected `Clock` (DEC-M3-09 enforcement). The publisher path remains non-blocking on writer-queue depth (INV-BUS-02). |
| **NO_DIRECT_TIME_ACCESS** (ArchUnit rule, DEC-M3-09 extension) | ENFORCED | All time access in the projection (filter TTL, reconciliation `reconciledAt`, checkpoint cadence timer) must go through an injected `java.time.Clock`. |

**Virtual thread threading model.** The State Projection subscriber processes events on a virtual thread. In-memory ConcurrentHashMap updates (the read path) execute directly on the virtual thread — no JNI, no carrier pinning. However, the projection subscriber's EventPublisher.publish() calls for derived state_changed events route through the Persistence Layer's platform thread write executor (LTD-03, Doc 04). The subscriber's virtual thread parks during each write. StateQueryService reads are pure ConcurrentHashMap lookups — fully virtual-thread-safe with no executor involvement. See Doc 03 §3.5 (AMD-29).

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

- **GOTCHA (M3.5a, 2026-05-18): `DerivedWriteRateLimit` is package-private to `com.homesynapse.event.bus`.** PLAN-M3 originally specified that `StateProjection` would accept a `DerivedWriteRateLimit` instance directly via its constructor. The actual M3.3 implementation lands `DerivedWriteRateLimit` as a package-private class (visibility `final class DerivedWriteRateLimit ...`, not `public`), with package-private `acquire()` and `refill()` methods. This means state-store cannot reference the type by name. The M3.5a resolution: introduce a new `DerivedPublishGate` interface in state-store with a single `acquire() throws InterruptedException` method. `StateProjection` depends on this interface, not on `DerivedWriteRateLimit` directly. Production wiring (lifecycle module or composition root in `homesynapse-app`) will need a small adapter that bridges from `DerivedWriteRateLimit` to `DerivedPublishGate`. The adapter can live either in event-bus (requires exporting a public adapter type) or in the composition root if it can reach into event-bus's package-private types. **Follow-up: M3.5b or a bus-internal task should add a public adapter or promote `DerivedWriteRateLimit` (and its `acquire()`/`refill()` methods) to public visibility.** This deviation preserves the behavioral contract (rate-limited at 200/s per subscriber) and isolates the projection from event-bus internals.

- **GOTCHA (M3.5b update, 2026-05-18; RESOLVED M3.5b-wiring 2026-05-18): The byte[0] stub is replaced by `StateCheckpointSource.serializeCheckpoint(projectionVersion)`.** M3.5a wrote `byte[0]` from `StateProjection.writeCheckpoint`. M3.5b introduced `core/persistence`'s `CheckpointSerializer` + `SqliteStateStore.serialize(int projectionVersion)` so the projection could supply a real serialized snapshot. M3.5b-wiring (the projection-side follow-up) introduces the `StateCheckpointSource` injection seam in state-store and has `StateProjection.writeCheckpoint` call `source.serializeCheckpoint(projectionVersion)` instead of constructing `new byte[0]`. Composition-root wiring (M3.6 / lifecycle WU) supplies the production source backed by `SqliteStateStore`; tests default to `StateCheckpointSource.stub()` which preserves the M3.5a `byte[0]` behavior.

- **GOTCHA (M3.5b, 2026-05-18; RESOLVED M3.5b-wiring 2026-05-18): The authoritative `projectionVersion` for reconciliation now lives in the checkpoint data blob.** `SqliteStateStore.loadedProjectionVersion()` exposes the version embedded in the Jackson payload — this is the real version per AMD-41 §3.2.4. `CheckpointRecord.projectionVersion()` is still the M3.5a/M2.7 sentinel hardcoded to 1 by both `InMemoryViewCheckpointStore` and `SqliteViewCheckpointStore`. M3.5b-wiring rewires `StateProjection.initialize` to call `checkpointSource.loadedProjectionVersion()` instead of reading the sentinel from `CheckpointRecord`. The future SqliteStateStore-implements-StateCheckpointSource WU promotes the existing package-private `loadedProjectionVersion()` to public and adds the `implements StateCheckpointSource` declaration.

- **GOTCHA (M3.5b-wiring, 2026-05-18): `StateCheckpointSource.stub()` returns `loadedProjectionVersion() == 0`.** Tests that seed a checkpoint via `checkpointStore.writeCheckpoint(...)` AND expect "no reconciliation" must supply a source whose `loadedProjectionVersion()` matches the projection's own version — the stub's 0 will mismatch any projection with version ≥ 1 and trigger reconciliation. The existing `reconciliationOnVersionMismatch` and `reconciliationHonorsAllowStaleSnapshotsFlag` contract tests are unaffected because they construct the projection with version 2 and want reconciliation to fire (mismatch with the stub's 0 still produces the expected reconcile/escape-hatch behavior). New tests added in M3.5b-wiring's `InMemoryStateProjectionTest` use a `FixedSource` test double that returns a specific version to exercise the matching-version path.

- **GOTCHA (M3.5b-wiring, 2026-05-18): The checkpoint size guardrail is advisory, not blocking.** `StateProjection.writeCheckpoint` emits a single WARN (`"Checkpoint data for {} is {} bytes — consider reducing entity count or attribute density"`) when the source returns more than `CHECKPOINT_SIZE_WARN_BYTES = 10 * 1024 * 1024` bytes. The write still proceeds — the threshold is a hint that entity count or attribute density is approaching the size at which checkpoint cadence will start costing significant I/O. 10 MB is intentionally conservative: at 10,000 entities × ~100 bytes of attributes each, the JSON checkpoint is roughly 15–20 MB.

- **GOTCHA (M3.5a): The reconciliation pass uses lazy init on first `onEvent`, NOT in the constructor.** `StateProjection`'s constructor stores parameters only — no I/O, no checkpoint reads, no `StateStore` mutations. The first `onEvent` call triggers `initialize()`: load checkpoint, check `projectionVersion`, reconcile if needed (clear state, reset cursor to 0) OR restore cursor from checkpoint, set `initialized = true`. Rationale: cheap construction, simpler tests, and a projection constructed but never subscribed does not perform I/O.

- **GOTCHA (M3.5a): `StateProjection.processBatch(int)` exists alongside `onEvent` for the batch path.** LIVE delivery goes through `onEvent` (single-event, no advancer). The batch path (`processBatch`) drives the `ProjectionAdvancer` and demonstrates the two-phase discipline: the processor callback runs inside the read tx and BUFFERS derived drafts; the buffered drafts are published AFTER `advance` returns (tx closed). The contract test `readTxClosesBeforePublish` uses this path. In production (M3.5b+), `processBatch` is the entry point for batch catch-up; the bus's per-subscriber VT loop calls `processBatch` when it pulls events from the event store under the AMD-38 cadence.

- **GOTCHA (M3.5a): `EntityState.stale` is intentionally hardcoded to `false` by the projection.** Per Doc 03 §4.1 and the existing M1.9 record contract, `stale` is derived at read time by `StateQueryService` (M3.6 scope). The projection stores `staleAfter` (which can be null) but always sets `stale = false` on materialized records — the field exists on the record only because `EntityState` is a pure data carrier. Consumers must always recompute `stale` from `staleAfter` and the wall clock at query time.

- **GOTCHA (M3.5a): `ViewCheckpointStore.writeCheckpoint(String, long, byte[])` does NOT take a `projectionVersion` parameter — the `InMemoryViewCheckpointStore` fixture hardcodes `projectionVersion = 1` when constructing the stored `CheckpointRecord`.** This means tests that need to exercise version-mismatch reconciliation can seed a checkpoint via `writeCheckpoint` and rely on the hardcoded `1`, then construct the projection with `projectionVersion = 2`. The M3.5b SqliteViewCheckpointStore will need a way to know which `projectionVersion` to write (likely passed at store construction time, since each projection has one stable version). The current M3.5a stub uses an empty `byte[0]` for the checkpoint data; M3.5b adds Jackson serialization of the materialized state.

- **GOTCHA (M3.5a): `StateProjection.processBatch` does NOT advance the cursor on partial publish failure.** When a RuntimeException propagates out of the PUBLISH phase (after the advancer has already closed its read tx and the projection has applied state from some events), the cursor advance call is skipped — checkpoint position remains at its pre-batch value. SequenceConflictException is caught and logged but does NOT prevent cursor advance. This preserves INV-PROJ-04 (checkpoint monotonicity tied to ALL publishes succeeding for crash recovery). Note: state mutations applied INSIDE the read-tx callback are NOT rolled back — the InMemoryStateStore has no transactional rollback. M3.5b's SqliteStateStore will require transactional discipline here.

- **S4-03 (Gradle/JPMS concordance):** `requires com.homesynapse.event` was non-transitive in the original Phase 2 spec, with Gradle scope `implementation`. M3.5a upgraded it to `requires transitive` because `EventEnvelope` and `EventPublisher` now appear in `StateProjection`'s public API. Gradle scope upgraded to `api`. `requires transitive com.homesynapse.event.bus` added (api scope) for the same reason: `Subscriber` and `SubscriberMode` are surfaced on `StateProjection`'s public API. `requires org.slf4j` added non-transitive (implementation scope) — SLF4J is used internally by `StateProjection` only, not exposed.
- **S5-CF2 (Phase 3 watch):** When an integration fails and triggers device orphan lifecycle (AMD-17), the orphan transition MUST set `stale:true` and `availability:UNAVAILABLE` immediately. The 30-second staleness scan (AMD-11) must treat already-stale orphaned devices as no-ops — do not emit duplicate stale events.

## Test Fixtures and Contract Tests

The `testFixtures` source set (`src/testFixtures/java/com/homesynapse/state/test/`) provides one abstract contract test and one in-memory implementation for the `ViewCheckpointStore` interface. The remaining unit tests live in `src/test/java/com/homesynapse/state/` and validate the data records and service interface shapes.

### testFixtures Type Inventory

| Type | Kind | Package | Purpose |
|---|---|---|---|
| `ViewCheckpointStoreContractTest` | abstract class (10 `@Test` methods) | `com.homesynapse.state.test` | Defines the behavioral contract for `ViewCheckpointStore`. Both `InMemoryViewCheckpointStore` and the future `SqliteViewCheckpointStore` must pass this suite. Covers: write/read round-trip; overwrite semantics (latest write per view name wins); per-view isolation; position validation; empty/missing data behavior; byte array aliasing protection (defensive copy on both write and read so that mutating the input or output array does not corrupt the stored checkpoint); `Clock`-injected `writtenAt` value; missing checkpoint returns `Optional.empty()`. |
| `InMemoryViewCheckpointStore` | class implementing `ViewCheckpointStore` | `com.homesynapse.state.test` | `ConcurrentHashMap`-based, thread-safe implementation. `Clock`-injected for deterministic `writtenAt` assignment. Performs defensive `byte[]` copies on both `writeCheckpoint` (input copy) and `readLatestCheckpoint` (output copy) to prevent aliasing bugs. `reset()` for test isolation. |

### Unit Test Coverage Summary

The `src/test/java/com/homesynapse/state/` source set proves the data-level contracts of the M1 type set without requiring a Phase 3 projection implementation. Each class focuses on a single record or interface:

| Test Class | `@Test` Count | What It Proves |
|---|---|---|
| `EntityStateTest` | 12 | 9-field record construction; staleness model (`staleAfter` + `Clock` derivation); three-timestamp independence (`lastChanged`, `lastUpdated`, `lastReported`); `stateVersion` idempotency semantics (advances on every event, not just mutations); `equals` / `hashCode` correctness across all 9 fields. |
| `StateSnapshotTest` | 7 | 5-field record construction; `replaying` flag semantics; `disabledEntities` set tracking; `viewPosition` monotonicity contract; defensive map / set copies. |
| `AvailabilityTest` | 4 | Enum shape (3 values: `AVAILABLE`, `UNAVAILABLE`, `UNKNOWN`); `UNKNOWN` is the documented initial state at entity adoption. |
| `StateQueryServiceTest` | 4 | Interface shape verification (5 methods, correct signatures and return types). No implementation is exercised — this is a Phase 2 spec lock. |
| `InMemoryViewCheckpointStoreTest` | 1 + 10 inherited | Adds one local test for `Clock` advancement behavior on top of the 10 inherited `ViewCheckpointStoreContractTest` methods. |

### Staleness Model Proven at Data Level (M1.9)

The staleness model is HomeSynapse's #1 architectural differentiator and deserves explicit callout. `EntityStateTest` contains 5 dedicated tests for the `staleAfter` + `Clock` staleness model:

- `staleAfter == null` means the entity is never stale (correct for actuators and event-driven reporters).
- `staleAfter` in the past means the entity is stale.
- `staleAfter` in the future means the entity is not stale.
- The derivation matches the `clock.instant().isAfter(staleAfter)` pattern that the Phase 3 `StateQueryService` will use at read time.
- The intentional inconsistency gap is exercised: the `EntityState` record itself accepts `staleAfter == null` together with `stale == true` (because the record is a pure data carrier), and the Phase 3 `StateQueryService` is responsible for preventing this combination at read time by recomputing `stale` from `staleAfter` against the injected `Clock`.

These 5 tests serve as executable documentation of a contract that no other smart home platform implements at the data-model level. Future Phase 3 work that touches `EntityState` or the projection must keep these tests green.

## Phase 3 Notes

- **StateQueryService implementation needed:** `ConcurrentHashMapStateStore` or similar. Uses `ConcurrentHashMap<EntityId, EntityState>` for lock-free reads. `getSnapshot()` takes a read-consistent copy under a `StampedLock` or similar. Implements both `StateQueryService` and an internal `StateProjection` interface for write-side updates from event subscribers.
- **State projection subscriber needed:** Subscribes to the event bus, processes `state_reported`, `state_changed`, `availability_changed`, `entity_enabled`, `entity_disabled` events. Maintains the ConcurrentHashMap. Checks `ProcessingMode` for side-effect suppression during REPLAY.
- **ViewCheckpointStore implementation needed:** `SqliteViewCheckpointStore` in the persistence module. Simple key-value store with `viewName` as key. Stores `position`, `data` (BLOB), `writtenAt`, `projectionVersion`.
- **Checkpoint serialization:** EntityState → JSON → byte[] via Jackson. The `CheckpointRecord.data` field contains the serialized Map<EntityId, EntityState>. Must handle nullable `staleAfter` fields in serialization.
- **Staleness computation:** `EntityState.stale` must be computed at read time in `StateQueryService.getState()` and `getSnapshot()`. The projection stores `staleAfter` but the `stale` boolean is derived from `Instant.now().isAfter(staleAfter)` at query time.
- **Testing strategy:** Unit tests for EntityState/StateSnapshot record construction and field validation. Integration tests for StateQueryService round-trip (write via projection, read via query). Concurrency tests for lock-free read safety. Checkpoint round-trip tests (serialize → store → retrieve → deserialize).
- **Performance targets (from Doc 03 §8):** `StateQueryService.getState()` must complete within 100μs. `getSnapshot()` O(N) but acceptable up to 10,000 entities within 10ms. Projection throughput: 50,000 events/second sustained during replay.

## Phase 3 Cross-Module Context

*Added 2026-05-17 (Post-M3.1 refresh), revised 2026-05-18 (M3.5a + M3.5b complete; M3.5b-wiring complete same day). Phase 3 active — M3.5b State Projection Production Persistence landed 2026-05-18; M3.5b-wiring (projection-checkpoint injection seam) landed 2026-05-18. Next milestone: M3.6 (StateQueryService implementation, ReadinessSource, lifecycle composition root) — including the SqliteStateStore-implements-StateCheckpointSource promotion and composition-root wiring. M3 governance: AMD-41/42/43 APPLIED. See `homesynapse-core-docs/design/HomeSynapse_Core_M3_Implementation_Plan_PLAN-M3-CONSOLIDATED-02.md` for the full M3 implementation plan.*

**M3.6d-a deliverables (2026-05-20) — readiness seam + reconciliation tests:**
- New `ReadinessSource` public interface in `com.homesynapse.state` (1 method: `mode() → SubscriberMode`). Composition root implements this via delegation to `StateProjection.currentMode()`. M3.6e's `MaterializedStateQueryService` consumes it to gate REST/WebSocket traffic.
- New `ReconciliationTest` concrete test class in `src/test/java/com/homesynapse/state/` covering 4 of the brief's 5 reconciliation scenarios: upgrade mismatch discards checkpoint, `allow_stale_snapshots=true` preserves checkpoint, reconciliation is idempotent across repeated mismatched-version init, downgrade mismatch also discards (symmetric to upgrade). The 5th brief test (`reconciliationRecordsMetadataInDataSlot`) is a documented feature gap: `StateProjection.writeCheckpoint` currently passes `null` for the three reconciliation-metadata parameters to `StateCheckpointSource.serializeCheckpoint(int)` — no plumbing exists to thread the metadata through. Recording reconciliation metadata is tracked as a separate enhancement.
- The state-store side of M3.6d-b's wiring is now ready: `StateCheckpointSource` is implemented by `SqliteStateStore` (M3.6d-a persistence-side promotion); `ReadinessSource` is implemented by the composition root (M3.6d-b).

**M3.5b-wiring deliverables (2026-05-18) — projection-side completion of M3.5b:**
- New `StateCheckpointSource` public interface in `com.homesynapse.state` (2 methods + `stub()` factory). Decouples `StateProjection` from the persistence module's `SqliteStateStore`.
- `StateProjection` constructor extended with `StateCheckpointSource checkpointSource` parameter (placed after `ViewCheckpointStore` — related concerns).
- `StateProjection.writeCheckpoint` now calls `source.serializeCheckpoint(projectionVersion)` instead of constructing `new byte[0]`.
- `StateProjection.initialize` reconciliation check now calls `source.loadedProjectionVersion()` instead of reading the `CheckpointRecord.projectionVersion()` sentinel.
- Advisory 10 MB checkpoint size guardrail (`CHECKPOINT_SIZE_WARN_BYTES`) — single WARN per write, non-blocking.
- Contract test `StateProjectionContractTest` factory hook updated; default fixture wires `StateCheckpointSource.stub()`.
- 5 new unit tests in `InMemoryStateProjectionTest` covering data wiring, version-match path, version-mismatch reconciliation path, stub preservation of M3.5a behavior, and size-guardrail non-blocking behavior.

**M3.5b deliverables (2026-05-18) — landed in `core/persistence`:**
- Production `StateStore` implementation: `SqliteStateStore` (in-memory map + checkpoint serialization). No `entity_state` SQLite table per DEC-M3-04 — see persistence MODULE_CONTEXT.md gotcha.
- Real checkpoint serialization replacing the M3.5a byte[0] stub: `CheckpointSerializer` + `CheckpointData` in `core/persistence`.
- Persistent DLQ: `SqliteDeadLetterStore` in `core/persistence`; `DeadLetter`, `SubscriberMaxRetries`, `PersistentDlqWriter` public types in `core/event-bus`.
- V004 migration (DLQ operational indices); `SqlitePersistenceLifecycle` enrolls V002/V003/V004.
- AtomicCheckpointWriter three-way DLQ park extension.

**M3.5a deliverables (2026-05-18):**
- `StateProjection` (public final class implements `Subscriber`) — two-phase discipline, lazy initialization with reconciliation, self-filter, defence-in-depth, checkpoint cadence.
- `SelfProducedFilter` (package-private final class) — 60s TTL, lazy eviction, REPLAY/TRANSITION bypass.
- `StateStore`, `DerivationRule`, `DerivationContext`, `DerivedPublishGate`, `ProjectionId` (public types in the projection's collaborator graph).
- testFixtures: `InMemoryStateStore`, `InMemoryProjectionAdvancer`, `SubscriberContractTest` (4 tests), `StateProjectionContractTest` (9 tests).
- Tests: `InMemoryStateProjectionTest` (13 inherited contract tests), `InMemoryProjectionAdvancerTest` (11 inherited contract tests from Deliverable 0), `SelfProducedFilterTest` (6 unit tests), `StateProjectionVerticalIT` (1 end-to-end integration test with 5 assertions).
- module-info.java: added `requires transitive com.homesynapse.event.bus`, upgraded `requires com.homesynapse.event` from non-transitive to transitive, added `requires org.slf4j`.
- build.gradle.kts: added `api(project(":core:event-bus"))`, upgraded event-model from `implementation` to `api`, added `implementation(libs.slf4j.api)`, added test/testFixtures access to event-bus testFixtures.

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: state projection dispatches on event types via `@EventType` registry lookup
- **AMD-41** — *State Projection Execution Model*: defines how StateProjection processes events, checkpoints, and recovers (APPLIED in M3.5a)
- **M3.5a complete (2026-05-18)** — `requires transitive com.homesynapse.event.bus` added to module-info, StateProjection implements Subscriber. Plan vs Reality reconciliation: `DerivedWriteRateLimit` is package-private (not public as the PLAN assumed); state-store introduced the `DerivedPublishGate` adapter interface as the workaround.

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for Phase 3 pattern discoveries (including M3.1 entries on default interface methods, contract test capability hooks, and JPMS-enforced JDBC-free constraints).
