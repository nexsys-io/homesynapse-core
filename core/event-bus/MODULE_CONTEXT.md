# event-bus — `com.homesynapse.event.bus` — 14 types — Pull-based event distribution, subscriber management, checkpoint persistence, active runtime lifecycle

## Purpose

The event-bus module defines the subscription and delivery contract for HomeSynapse's in-process event distribution system. It is a pull-based, notification-driven bus: the EventBus does not deliver events directly to subscribers — it notifies matching subscribers that new events are available, and subscribers pull events from the EventStore themselves. This design enables backpressure, coalescing, and crash-safe checkpoint-based resumption. The module also defines the CheckpointStore interface for durable subscriber position tracking, ensuring that subscribers resume from the correct position after crashes.

As of M3.1, the module includes the production `InProcessEventBus` implementation with: per-subscriber virtual thread lifecycle, mode FSM (COLD → REPLAY → TRANSITION → LIVE → SUSPENDED), supervisor with exception taxonomy and circuit breaker, in-memory DLQ ring, and per-subscriber isolation guarantees (INV-SUB-ISO-01..06).

## Design Doc Reference

**Doc 01 — Event Model & Event Bus** is the governing design document:
- §3.4: Subscription model (pull-based, virtual threads, filter evaluation)
- §3.6: Backpressure coalescing for DIAGNOSTIC events
- §8: Interface specifications for EventBus, SubscriptionFilter, SubscriberInfo, CheckpointStore

## JPMS Module

```
module com.homesynapse.event.bus {
    requires transitive com.homesynapse.event;

    exports com.homesynapse.event.bus;
}
```

The `requires transitive` on event-model means any module that reads `com.homesynapse.event.bus` automatically gets access to all event types, `EventEnvelope`, `EventPublisher`, `EventStore`, and (transitively) all platform-api identity types.

## Package Structure

- **`com.homesynapse.event.bus`** — All types in a single flat package: the EventBus interface, subscriber registration descriptor, subscription filter, checkpoint store contract, runtime callback interface, mode enum, snapshot record, read connection abstractions, and the production InProcessEventBus implementation.

## Complete Type Inventory

### Public Types (9)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `EventBus` | interface (8 methods) | Notification-driven subscription/delivery contract for event distribution | Methods: `subscribe(SubscriberInfo)`, `unsubscribe(String)`, `notifyEvent(long)`, `subscriberPosition(String)`, `subscribeRuntime(SubscriberInfo, Subscriber)`, `resume(String)`, `subscriberInfo(String)`, `subscribers()`. The 4 new methods have default implementations throwing UnsupportedOperationException for backward compatibility. Does NOT have a `publish()` method — the bus is notification-only. |
| `SubscriberInfo` | record (3 fields) | Immutable descriptor for subscriber registration with the EventBus | Fields: `subscriberId` (String), `filter` (SubscriptionFilter), `coalesceExempt` (boolean). |
| `SubscriptionFilter` | record (3 fields) | Immutable filter determining which events a subscriber receives | Fields: `eventTypes` (Set\<String\>), `minimumPriority` (EventPriority), `subjectTypeFilter` (SubjectType nullable). |
| `CheckpointStore` | interface (2 methods) | Durable storage for subscriber checkpoint positions | Methods: `readCheckpoint(String)` → long, `writeCheckpoint(String, long)`. |
| `Subscriber` | interface | Runtime callback: `onEvent(EventEnvelope)`, `default onCaughtUp()` | Called on the subscriber's dedicated virtual thread. Supervisor wraps all invocations. |
| `SubscriberMode` | enum (5 values) | Lifecycle mode: COLD, REPLAY, TRANSITION, LIVE, SUSPENDED | Transitions are atomic via AtomicReference with CAS. |
| `SubscriberSnapshot` | record (5 fields) | Point-in-time introspection of subscriber state | Fields: `subscriberId`, `mode`, `checkpoint`, `dlqDepth`, `crashCount`. |
| `SubscriberReadConnectionFactory` | functional interface | Factory for per-subscriber read executors (keeps bus JDBC-free) | Called once per `subscribeRuntime()`. Returns SubscriberReadExecutor. |
| `SubscriberReadExecutor` | interface extends AutoCloseable | Dedicated platform-thread read executor per subscriber | Method: `<T> executeRead(Callable<T>)`. Encapsulates platform thread + SQLite connection. |

### Package-Private Types (5)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `InProcessEventBus` | class | Production EventBus implementation | Constructor: `(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory)`. Manages passive registry (Phase 2 compat) and active registry (full runtime). |
| `SubscriberSupervisor` | class | Per-subscriber exception handling, backoff, circuit breaker | Exception taxonomy: Error/IOException/checked→SUSPENDED; RuntimeException→backoff. MIN=3s, MAX=30s, jitter=0.2. Rolling 10-min crash window, 5 crashes → SUSPENDED. |
| `SubscriberDlq` | class | Per-subscriber in-memory DLQ ring (cap 1024) | Methods: `park(DlqEntry)`, `depth()`, `clear()`. Persistent overflow wiring deferred to M3.5b. |
| `ReplayWindowQueue` | class | Bounded queue for REPLAY→TRANSITION events | Methods: `enqueue(long)`, `size()`, `clear()`. Drain logic in M3.2. |
| `SubscriberRuntime` | class | Internal bundle: VT, executor, supervisor, DLQ, mode ref, queue | Holds all per-subscriber resources. Closed on unsubscribe. |

**Total: 9 public types + 5 package-private types = 14 production types.**

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **event-model** (`com.homesynapse.event`) | `requires transitive` (API dependency) — Event types for filter evaluation and delivery | `EventEnvelope` (passed to `SubscriptionFilter.matches()` and `Subscriber.onEvent()`), `EventPriority`, `SubjectType`, `EventStore`, `EventPage`. |
| **platform-api** (transitive through event-model) | Identity types used indirectly | `Ulid`, typed ID wrappers accessed through `SubjectRef` on `EventEnvelope`. |

## Consumers

### Current consumers (modules with completed Phase 2 specs):
None directly — event-bus defines contracts that are consumed by the persistence module (implements CheckpointStore) and the startup-lifecycle module (wires EventBus to EventPublisher). All subscriber modules depend on event-bus transitively.

### Planned consumers (from design doc dependency graph):
- **persistence** — Implements `CheckpointStore` as `SqliteCheckpointStore`. Provides production `SubscriberReadConnectionFactory`.
- **state-store** — Will register as a subscriber via `subscribeRuntime` with `coalesceExempt = true`. Will need `requires com.homesynapse.event.bus` in module-info (M3.5a scope).
- **automation** — Will register as a subscriber to evaluate triggers against events.
- **integration-runtime** — Will register as a subscriber for command dispatch events.
- **websocket-api** — Will register as a subscriber to stream events to connected clients.
- **observability** — Will register as a subscriber for system metrics and health events.
- **startup-lifecycle** — Wires the InProcessEventBus, registers built-in subscribers, coordinates startup ordering.

## Cross-Module Contracts

- **Pull-based subscription model.** The EventBus does NOT deliver events to subscribers. It wakes them via `LockSupport.unpark()`. Each subscriber is responsible for calling `EventStore.readFrom()` to pull events starting from their checkpoint position.
- **At-least-once delivery with subscriber idempotency.** If the system crashes between event persistence and checkpoint update, the subscriber will re-process events from its last checkpoint on recovery. Subscribers MUST be idempotent (INV-ES-05).
- **Backpressure coalescing for DIAGNOSTIC events.** Non-exempt subscribers may have DIAGNOSTIC-priority events coalesced during high throughput.
- **Subscriber positions start at 0.** If `CheckpointStore.readCheckpoint()` returns 0 for a subscriber, it means the subscriber has never checkpointed and should start from the beginning of the event log.
- **`SubscriptionFilter.matches()` is a conjunction.** An event must satisfy ALL active filter criteria to pass.
- **`subscribeRuntime` creates full per-subscriber runtime.** VT, dedicated read connection, supervisor, DLQ, mode FSM. The subscriber starts in COLD and transitions to REPLAY asynchronously.
- **`subscribe` is passive.** Filter evaluation + checkpoint tracking only. No VT, no supervisor, no DLQ. Backward compatible with Phase 2 contract tests.
- **Bus does NOT have a `publish()` method.** Publishing is EventPublisher's job. The bus is notification-only.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-05** | Per-entity sequences with global position. Subscribers checkpoint against `globalPosition`. |
| **LTD-06** | Write-ahead persistence with at-least-once delivery. Events persisted before bus notification. |
| **LTD-11** | No `synchronized` — use ReentrantLock/ReadWriteLock only. |
| **INV-ES-04** | Write-ahead persistence. EventBus.notifyEvent() called AFTER WAL commit. |
| **INV-ES-05** | At-least-once delivery with subscriber idempotency. |
| **INV-SUB-ISO-01** | One virtual thread per subscriber. Named `hs-sub-<subscriberId>`. |
| **INV-SUB-ISO-02** | One read connection per subscriber (via SubscriberReadConnectionFactory). |
| **INV-SUB-ISO-03** | One DLQ per subscriber. Failures in sub-A do not affect sub-B's DLQ. |
| **INV-SUB-ISO-04** | One mode reference per subscriber. AtomicReference with CAS. |
| **INV-SUB-ISO-05** | Replay window queue is per-subscriber isolated. |
| **INV-SUB-ISO-06** | Self-filter is per-subscriber isolated (M3.5a wires this). |

## Amendments in force

| Amendment | Status | Relevance to this module |
|---|---|---|
| **AMD-42** — Subscriber Lifecycle and Isolation | APPLIED (2026-05-16) | Mandates the mode state machine, per-subscriber resources, supervisor discipline, isolation guarantees. Fully implemented in M3.1 (bus skeleton, FSM, supervisor, isolation). M3.2 lands REPLAY→LIVE algorithm. |
| **AMD-43** — Backpressure and Observability | APPLIED (2026-05-16) | Mandates non-blocking publish, metric names, QueueSaturationHealthCheck. M3.3 scope. |
| **NO_DIRECT_TIME_ACCESS** (ArchUnit rule) | ENFORCED | All time access through injected Clock. No Instant.now(), System.currentTimeMillis(), Clock.systemUTC() anywhere in production or test code. |

## Sealed Hierarchies

None. This module contains no sealed types.

## Key Design Decisions

1. **Pull-based, not push-based delivery.** Subscribers are woken (notified) but pull events themselves from EventStore.
2. **`CheckpointStore` is a separate interface from `EventStore`.** Different consumers, same database.
3. **`subscriberId` is a plain String, not a typed wrapper.** Infrastructure components don't need ULID identity.
4. **`SubscriptionFilter` uses `Set<String>` for event types.** Extensible strings, not a closed enum.
5. **New EventBus methods are `default` with UnsupportedOperationException.** Allows InMemoryEventBus (Phase 2 fixture) to compile without modification.
6. **SubscriberInfo stays 3-field (DP-3/DP-6).** All introspection goes through SubscriberSnapshot, not SubscriberInfo.
7. **Bus module is JDBC-free (DP-4).** SubscriberReadConnectionFactory/Executor keep java.sql out of module-info.
8. **Infrastructure exception carve-out (DP-1).** Error, IOException, and non-RuntimeException checked exceptions → immediate SUSPENDED. Only RuntimeException follows backoff path.

## Gotchas

**GOTCHA: CheckpointStore here is for subscriber POSITION checkpoints only.** Not state-store view snapshots.

**GOTCHA: `SubscriptionFilter.eventTypes` empty set means ALL types, not NO types.**

**GOTCHA: `coalesceExempt` is critical for correctness of State Projection and Pending Command Ledger.**

**GOTCHA: `notifyEvent(long globalPosition)` does NOT pass the event itself.** The bus loads filter-relevant metadata from the event store.

**GOTCHA: V002 schema (`subscriber_dead_letters` table) exists but is NOT wired.** The in-memory DLQ ring (cap 1024) is the M3.1 implementation. Persistent overflow is M3.5b.

**GOTCHA: `InMemoryEventBus` is NOT modified in M3.1.** It remains the Phase 2 fixture for the original 4-method interface. The production bus is InProcessEventBus.

**GOTCHA: `InMemoryEventBusTest` and `InProcessEventBusTest` both extend `EventBusContractTest`.** The new Tiers 5-10 use `assumeTrue(supportsActiveRuntime())` to skip for InMemoryEventBusTest.

**GOTCHA: The supervisor's `deliver()` uses `Thread.sleep()` for backoff.** This is safe on virtual threads (unmounts carrier). But it means tests that exercise retries must either use short sleeps or advance a mutable clock. The MutableClock in InProcessEventBusTest enables deterministic backoff testing.

## Test Fixtures and Contract Tests

The `testFixtures` source set now provides five types:

| Type | Kind | Package | Purpose |
|---|---|---|---|
| `CheckpointStoreContractTest` | abstract class (9 @Test methods) | `com.homesynapse.event.bus.test` | Behavioral contract for CheckpointStore. |
| `InMemoryCheckpointStore` | class | `com.homesynapse.event.bus.test` | ConcurrentHashMap-based CheckpointStore. |
| `EventBusContractTest` | abstract class (44 @Test methods: 18 + 16 active + 10 disabled) | `com.homesynapse.event.bus.test` | Behavioral contract for EventBus. 4 @Nested tiers (Phase 2) + 4 new tiers (M3.1) + 2 disabled tiers (M3.2, M3.3). |
| `InMemoryEventBus` | class | `com.homesynapse.event.bus.test` | Phase 2 contract-test fixture. 4-method interface only. |
| `RecordingReadConnectionFactory` | class | `com.homesynapse.event.bus.test` | Recording stub for INV-SUB-ISO-02 assertions. |

### EventBusContractTest — 10 Nested Tiers

- **Tier 1 — Subscription Lifecycle (5 tests)**
- **Tier 2 — Notification and Filtering (7 tests)**
- **Tier 3 — Checkpoint Integration (4 tests)**
- **Tier 4 — Concurrency Safety (2 tests)**
- **Tier 5 — Mode State Machine (4 active tests)** — M3.1
- **Tier 6 — Per-Subscriber Isolation (6 active tests)** — M3.1
- **Tier 7 — Supervisor (5 active tests)** — M3.1
- **Tier 8 — Lifecycle (1 active test)** — M3.1
- **Tier 9 — REPLAY→LIVE Transition (6 disabled @Disabled("M3.2"))**
- **Tier 10 — Backpressure and Metrics (4 disabled @Disabled("M3.3"))**

## Phase 3 Notes

- **M3.1 landed:** Bus skeleton, mode FSM, supervisor, per-subscriber isolation, in-memory DLQ, circuit breaker. Production `InProcessEventBus` with full 8-method interface.
- **M3.2 pending:** REPLAY→LIVE transition logic (three-phase replay, ReplayDriver, TransitionCoordinator, replay window drain, gap detection).
- **M3.3 pending:** BusMetrics, QueueSaturationHealthCheck, WriterQueueGauge, per-subscriber DerivedWriteRateLimit.
- **M3.5a pending:** StateProjection vertical slice. State-store module-info will need `requires com.homesynapse.event.bus`.
- **M3.5b pending:** Persistent DLQ wiring (DeadLetter record, SqliteDeadLetterStore, V004 DLQ indices). The in-memory DLQ ring is the current implementation.
- **Performance targets:** Bus notification fan-out within 1ms for 20 subscribers. CheckpointStore.writeCheckpoint() within 1ms.
