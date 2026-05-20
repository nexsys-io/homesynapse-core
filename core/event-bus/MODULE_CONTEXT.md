# event-bus — `com.homesynapse.event.bus` — 33 types — Pull-based event distribution, subscriber management, checkpoint persistence, active runtime lifecycle, backpressure metrics, persistent dead-letter seam, operator-tunable bus configuration

## Purpose

The event-bus module defines the subscription and delivery contract for HomeSynapse's in-process event distribution system. It is a pull-based, notification-driven bus: the EventBus does not deliver events directly to subscribers — it notifies matching subscribers that new events are available, and subscribers pull events from the EventStore themselves. This design enables backpressure, coalescing, and crash-safe checkpoint-based resumption. The module also defines the CheckpointStore interface for durable subscriber position tracking, ensuring that subscribers resume from the correct position after crashes.

As of M3.2, the module implements the full three-phase REPLAY→TRANSITION→LIVE algorithm (AMD-42 §3.4.2). M3.1 landed the bus skeleton: per-subscriber virtual thread lifecycle, mode FSM (COLD → REPLAY → TRANSITION → LIVE → SUSPENDED), supervisor with exception taxonomy and circuit breaker, in-memory DLQ ring, and per-subscriber isolation guarantees (INV-SUB-ISO-01..06). M3.2 added: `ReplayDriver` (page-replay loop from persisted checkpoint with AMD-38 checkpoint cadence and overflow restart), `TransitionCoordinator` (drains the `ReplayWindowQueue` with gap detection, atomically promotes to LIVE, fires `onCaughtUp()` exactly once), the bounded thread-safe `ReplayWindowQueue` (10,000-entry cap, latched overflow flag), and the LIVE pull loop with per-event checkpointing.

M3.3 adds backpressure metrics and observability (AMD-43): the `BusMetrics` typed facade with JFR-native production implementation, the seven canonical bus metric names (Decision 1 of M3.3 deliberation), hysteresis-based `QueueSaturationHealthCheck`, and per-subscriber `DerivedWriteRateLimit` token-bucket. The bus's notification path now samples writer queue depth via an injected `IntSupplier` (DEC-M3-14), emits the depth gauge on every notification, increments a publisher-blocked counter when depth exceeds 5000 (INV-BUS-02 — record only, never block), and records subscriber lag after each successful LIVE delivery. No new ArchUnit rules; no new `requires` directives (jdk.jfr is a platform module).

## Design Doc Reference

**Doc 01 — Event Model & Event Bus** is the governing design document:
- §3.4: Subscription model (pull-based, virtual threads, filter evaluation)
- §3.6: Backpressure coalescing for DIAGNOSTIC events
- §8: Interface specifications for EventBus, SubscriptionFilter, SubscriberInfo, CheckpointStore

## JPMS Module

```
module com.homesynapse.event.bus {
    requires transitive com.homesynapse.event;
    requires jdk.jfr;

    exports com.homesynapse.event.bus;
}
```

The `requires transitive` on event-model means any module that reads `com.homesynapse.event.bus` automatically gets access to all event types, `EventEnvelope`, `EventPublisher`, `EventStore`, and (transitively) all platform-api identity types.

The non-transitive `requires jdk.jfr` (added M3.3) lets the bus emit JFR custom events from `BusMetricsJfr`. Despite shipping with the JDK, `jdk.jfr` is NOT in `java.base` and is NOT auto-required — JPMS demands the explicit directive. Consumers of `com.homesynapse.event.bus` do NOT see `jdk.jfr` transitively: the JFR event classes are package-private implementation details and never appear in exported API signatures.

## Package Structure

- **`com.homesynapse.event.bus`** — All types in a single flat package: the EventBus interface, subscriber registration descriptor, subscription filter, checkpoint store contract, runtime callback interface, mode enum, snapshot record, read connection abstractions, and the production InProcessEventBus implementation.

## Complete Type Inventory

### Public Types (19)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `EventBus` | interface (8 methods) | Notification-driven subscription/delivery contract for event distribution | Methods: `subscribe(SubscriberInfo)`, `unsubscribe(String)`, `notifyEvent(long)`, `subscriberPosition(String)`, `subscribeRuntime(SubscriberInfo, Subscriber)`, `resume(String)`, `subscriberInfo(String)`, `subscribers()`. The 4 new methods have default implementations throwing UnsupportedOperationException for backward compatibility. Does NOT have a `publish()` method — the bus is notification-only. |
| `EventBusConfig` | record (2 fields) | M3.6b — operator-tunable bus parameters (audit findings D1-07, D4-09). | Fields: `replayQueueCapacity` (int, ≥ 1), `publisherBlockedDepthThreshold` (int, ≥ 1). Compact constructor validates both fields and throws `IllegalArgumentException` on violation. Constant: `HOME_DEFAULT = new EventBusConfig(10_000, 5_000)` reproduces the prior M3.4b behaviour exactly. Consumed by the 7-arg `InProcessEventBus` constructor and by `InProcessEventBusFactory.createWithConfig(...)`. |
| `InProcessEventBus` | class (public, M3.6b — DEC-M3-16 visibility promotion 2026-05-20) | Production EventBus implementation | Constructors: `(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory)` (convenience — wires `BusMetrics.noop()` and `() -> 0` and delegates with `EventBusConfig.HOME_DEFAULT`) and `(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory, BusMetrics, IntSupplier)` (delegates with `EventBusConfig.HOME_DEFAULT`) — both package-private; plus the canonical `public InProcessEventBus(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory, BusMetrics, IntSupplier, EventBusConfig)` (M3.6b) which the composition root calls directly. `notifyEvent` samples the IntSupplier on entry, emits depth gauge + publisher-blocked counter (DEC-M3-14, AMD-43 §3.6.2) — the threshold is now an instance field initialised from `config.publisherBlockedDepthThreshold()` (no longer a `static final int`). Records publish latency on exit. `liveLoop` records subscriber lag after each successful supervisor delivery. Routes by subscriber mode: REPLAY/TRANSITION → ReplayWindowQueue; LIVE → pendingPositions + unpark; COLD/SUSPENDED → skip. `subscribeRuntime` constructs the per-subscriber `ReplayWindowQueue` with `config.replayQueueCapacity()`. The queue's lock is held across the mode-read + routing decision. |
| `DeadLetter` | record (11 fields) | M3.5b — public record mirroring the V002 `subscriber_dead_letters` row shape (AMD-36). | Fields: `dlqId` (long, `0` is `UNASSIGNED_DLQ_ID` sentinel pre-persist), `subscriberId`, `sequenceKey`, `eventPosition` (≥ 0), `eventId` (`Ulid`, BLOB(16) in SQLite), `causeClass`, `causeMessage`, `attemptCount` (≥ 1), `firstSeenAt` (`Instant`), `lastAttemptAt` (`Instant`), `diagnostics` (nullable). Compact constructor enforces non-null + non-negative invariants matching V002 `NOT NULL` columns. |
| `SubscriberMaxRetries` | record (1 field) | M3.5b — typed wrapper for the supervisor's per-subscriber retry cap (AMD-36). | Field: `value` (int, ≥ 1). Constant: `DEFAULT = SubscriberMaxRetries(5)` per AMD-36 — five retries after the initial delivery, six total attempts before park. Validates `value >= 1` in compact constructor. |
| `PersistentDlqWriter` | functional interface (1 method) | M3.5b — injection seam for persistent dead-letter overflow (AMD-36). | Single method: `park(DeadLetter)`. Static factory `noop()` returns a no-op writer for tests / in-memory-only deployments. Composition root supplies a lambda backed by `SqliteDeadLetterStore` so the bus has no compile-time dependency on persistence. |
| `SubscriberInfo` | record (3 fields) | Immutable descriptor for subscriber registration with the EventBus | Fields: `subscriberId` (String), `filter` (SubscriptionFilter), `coalesceExempt` (boolean). |
| `SubscriptionFilter` | record (3 fields) | Immutable filter determining which events a subscriber receives | Fields: `eventTypes` (Set\<String\>), `minimumPriority` (EventPriority), `subjectTypeFilter` (SubjectType nullable). |
| `CheckpointStore` | interface (2 methods) | Durable storage for subscriber checkpoint positions | Methods: `readCheckpoint(String)` → long, `writeCheckpoint(String, long)`. |
| `Subscriber` | interface | Runtime callback: `onEvent(EventEnvelope)`, `default onCaughtUp()` | Called on the subscriber's dedicated virtual thread. Supervisor wraps all invocations. |
| `SubscriberMode` | enum (5 values) | Lifecycle mode: COLD, REPLAY, TRANSITION, LIVE, SUSPENDED | Transitions are atomic via AtomicReference with CAS. |
| `SubscriberSnapshot` | record (5 fields) | Point-in-time introspection of subscriber state | Fields: `subscriberId`, `mode`, `checkpoint`, `dlqDepth`, `crashCount`. |
| `SubscriberReadConnectionFactory` | functional interface | Factory for per-subscriber read executors (keeps bus JDBC-free) | Called once per `subscribeRuntime()`. Returns SubscriberReadExecutor. |
| `SubscriberReadExecutor` | interface extends AutoCloseable | Dedicated platform-thread read executor per subscriber | Method: `<T> executeRead(Callable<T>)`. Encapsulates platform thread + SQLite connection. |
| `BusMetrics` | interface (6 methods + 2 factories) | M3.3 — typed facade for the seven canonical bus metric emissions (AMD-43 §3.6.2) | Methods: `recordPublishLatency`, `incrementPublisherBlocked`, `recordWriterQueueDepth`, `recordSubscriberLag`, `recordDerivedWriteAccepted`, `recordDerivedWriteParked`. Static factories: `noop()` and `jfr()`. All emissions are fire-and-forget; thread-safe. |
| `DerivedWriteRateLimit` | class implements AutoCloseable | M3.3 — per-subscriber token bucket for derived-write throttling (AMD-43 §3.6.4). Visibility promoted to `public` in Bus-Fix Piece A (2026-05-18) so cross-module consumers (composition root, state-store via `DerivedPublishGate` method reference) can reach the type directly. | Public constructors: `(int capacity, Clock, BusMetrics, String)` and `(Clock, BusMetrics, String)` using default capacity 200. Public methods: `acquire()` (throws `InterruptedException`), `refill()`, `close()` (from `AutoCloseable`). Bucket capacity 200, refill 10 tokens/50 ms (200 tokens/sec effective). `acquire()` blocks on a `Semaphore` when empty (VT-safe — no carrier pinning). `refill()` adds tokens and releases parked threads. `close()` releases parked threads and marks closed. Inspection accessors (`capacity()`, `available()`, `clock()`, `subscriberId()`) remain package-private — they exist for in-package tests only. |
| `QueueSaturationHealthCheck` | final class (public — promoted from package-private in M3.6d-a, DEC-M3-16 part 3) | M3.3 — hysteresis health check on writer queue depth (AMD-43 §3.6.3). Composition root constructs one instance and `SharedScheduler` calls `tick()` every second. | Public constructor: `(IntSupplier queueDepthSupplier, Clock, int warnDepth, int criticalDepth, int saturationTicks, Consumer<HealthSignal> emitter)`. Defaults: warn=5000, critical=10000, saturationTicks=5. Public method: `tick()` advances the state machine by one tick. Public constants: `CHANNEL_SATURATING`, `CHANNEL_RECOVERED`. Re-emit cadence: CRITICAL 10 s, WARN 30 s. Recovery requires 5 consecutive below-threshold ticks. Promoting this type required promoting `HealthSignal` and `HealthLevel` too — both appear in the constructor's `Consumer<HealthSignal>` parameter type, and `-Xlint:exports` would have failed on a public class leaking package-private types. |
| `HealthSignal` | record (4 fields, public — promoted in M3.6d-a) | M3.3 — payload of a health emission from `QueueSaturationHealthCheck`. | Fields: `level` (HealthLevel), `channel` (String), `depth` (int), `timestamp` (Instant). Promoted to satisfy `-Xlint:exports` once `QueueSaturationHealthCheck` became public — without this promotion, the public constructor would leak a package-private type. |
| `HealthLevel` | enum (3 values, public — promoted in M3.6d-a) | M3.3 — bus-internal severity level: INFO, WARN, CRITICAL. | Distinct from the observability module's HealthStatus — that translation happens in the lifecycle/observability bridge layer. Promoted alongside `HealthSignal` in the same `-Xlint:exports` chain. |

### Package-Private Types (14)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `SubscriberSupervisor` | class | Per-subscriber exception handling, backoff, circuit breaker | Exception taxonomy: Error/IOException/checked→SUSPENDED; RuntimeException→DLQ + crash record + breaker check. Rolling 10-min crash window, 5 crashes → SUSPENDED. **M3.5b-supervisor-wiring (2026-05-19):** the RuntimeException catch site now constructs `DeadLetter` (11 fields, full identity context — subscriberId, sequenceKey from `envelope.subjectRef().toString()`, eventId from `envelope.eventId().value()`, null-guarded causeMessage) and calls `dlq.park(deadLetter)` (ring + persistent writer path). `attemptCount = 1` because the retry loop is not yet activated. Dead code: `computeBackoff()`, `sleepForBackoff()`, `MAX_RETRIES = 5` — reserved for the future retry-loop WU; `MIN_BACKOFF_MS=3s`, `MAX_BACKOFF_MS=30s`, `JITTER_FACTOR=0.2` are referenced only by the dead `computeBackoff`. |
| `SubscriberDlq` | class | Per-subscriber in-memory DLQ ring (cap 1024); M3.5b adds a `PersistentDlqWriter` injection seam. | Methods: `park(DlqEntry)` (CAUGHT_UP_TRANSITION synthetic marker path — ring only), `park(DeadLetter)` (M3.5b — ring + persistent writer; **now the supervisor's primary path as of M3.5b-supervisor-wiring 2026-05-19**), `depth()`, `clear()`, `subscriberId()`. Two constructors: the no-arg M3.1 constructor preserved for legacy callers and a new constructor `(String subscriberId, PersistentDlqWriter writer)` for production lifecycle wiring. `InProcessEventBus.subscribeRuntime` now uses the two-arg form so the DLQ's identity matches the supervisor's. Also receives `CAUGHT_UP_TRANSITION` synthetic entries on `onCaughtUp()` exceptions via `TransitionCoordinator.park(DlqEntry)` with `eventPosition = CAUGHT_UP_TRANSITION_MARKER = -1L` (AMD-42 §3.4.3) — this path retains `DlqEntry` because `DeadLetter` rejects negative `eventPosition`. |
| `ReplayWindowQueue` | class | Bounded thread-safe queue for events arriving during REPLAY/TRANSITION | Configurable capacity (default 10,000 via no-arg constructor; parameterised via `ReplayWindowQueue(int maxCapacity)` — M3.6b, audit D4-09). Static `MAX_CAPACITY = 10_000` retained as documentation reference for the default value. Internal `ReentrantLock` (LTD-11). Methods: `enqueue(long)→boolean` (false on overflow, latches `overflowed` flag — checks `queue.size() >= maxCapacity` against the instance field), `poll()→Long`, `size()`, `isEmpty()`, `clear()`, `overflowed()`, `lock()/unlock()` for compound atomic operations. The `ReplayDriver` polls `overflowed()` and restarts REPLAY from the persisted checkpoint on overflow. |
| `SubscriberRuntime` | class | Internal bundle: VT, executor, supervisor, DLQ, mode ref, queue, lastReplayedPosition, optional rateLimit | Holds all per-subscriber resources. M3.3 added a nullable `DerivedWriteRateLimit` field. After Bus-Fix Piece A (2026-05-18) the `DerivedWriteRateLimit` type is now `public`, so the bus is no longer blocked on visibility — however the field still remains `null` for all subscribers because the composition root / lifecycle module has not yet been wired to instantiate the limiter and pass it in at `subscribeRuntime` time. That wiring lands with the future lifecycle-module work. `lastReplayedPosition` (AtomicLong) is the gap-detection high-water mark consumed by `TransitionCoordinator`. Closed on unsubscribe (closes rateLimit if present). |
| `ReplayDriver` | class | Drives the REPLAY phase | Pages event log from persisted checkpoint to live tail, delivers matches via supervisor, writes checkpoints per AMD-38 cadence (200 events OR 2 s), handles `ReplayWindowQueue` overflow restart, CASes mode REPLAY→TRANSITION on tail. Constants: `MAX_REPLAY_PAGE = 500`, `CHECKPOINT_EVENT_THRESHOLD = 200`, `CHECKPOINT_MAX_INTERVAL_SECONDS = 2`. Reads route through `SubscriberReadExecutor` (AMD-26/27, INV-SUB-ISO-02). One instance per subscriber-VT activation. |
| `TransitionCoordinator` | class | Drains `ReplayWindowQueue` with gap detection (`globalPosition > lastReplayedPosition`), atomically CASes mode TRANSITION→LIVE under the queue's lock (closes race with concurrent `notifyEvent` enqueues), fires `onCaughtUp()` exactly once per process per subscriber. `onCaughtUp` exceptions become a synthetic DLQ entry at marker position `CAUGHT_UP_TRANSITION_MARKER = -1L` (AMD-42 §3.4.3). One instance per subscriber-VT activation. |
| `NoopBusMetrics` | class | M3.3 — singleton no-op BusMetrics implementation | Used by the convenience `InProcessEventBus` constructor and `InMemoryEventBus`. Returned by `BusMetrics.noop()`. All methods are no-ops. |
| `BusMetricsJfr` | class | M3.3 — JFR-native BusMetrics implementation (Decision 1) | Each method constructs and commits the corresponding `jdk.jfr.Event` subclass. Thread-local buffer write, no synchronization, no I/O. Returned by `BusMetrics.jfr()`. Consumed by the existing `MetricsStreamBridge` in `observability/observability` via its `RecordingStream`. |
| `BusPublishLatencyEvent` | class extends jdk.jfr.Event | M3.3 — `homesynapse.bus.publish.latency` histogram | Field: `long latencyMicros`. `@StackTrace(false)` mandatory for hot-path performance. |
| `BusPublisherBlockedEvent` | class extends jdk.jfr.Event | M3.3 — `homesynapse.bus.publisher.blocked.count` counter | Instant event, no payload beyond JFR timestamp. |
| `BusWriterQueueDepthEvent` | class extends jdk.jfr.Event | M3.3 — `homesynapse.bus.writer.queue.depth` gauge | Field: `int depth`. Sampled on every publish notification (fresh value per DEC-M3-14). |
| `BusSubscriberLagEvent` | class extends jdk.jfr.Event | M3.3 — combined event for `homesynapse.bus.subscriber.lag.events` and `homesynapse.bus.subscriber.lag.millis` | Fields: `String subscriberId, long lagEvents, long lagMillis`. Two logical metric names share a single JFR event class because they share an observation point. |
| `BusWriteAcceptedEvent` | class extends jdk.jfr.Event | M3.3 — `homesynapse.bus.subscriber.derived_writes.accepted` counter | Field: `String subscriberId`. Emitted by `DerivedWriteRateLimit.acquire()` on token availability. |
| `BusWriteParkedEvent` | class extends jdk.jfr.Event | M3.3 — `homesynapse.bus.subscriber.derived_writes.parked` counter | Field: `String subscriberId`. Emitted by `DerivedWriteRateLimit.acquire()` when a thread must park. |
| (`QueueSaturationHealthCheck`, `HealthSignal`, `HealthLevel` now live in the Public Types table — promoted in M3.6d-a, DEC-M3-16 part 3.) | | | |

**Total: 19 public types + 14 package-private types = 33 production types.** M3.5b added three public types (`DeadLetter`, `SubscriberMaxRetries`, `PersistentDlqWriter`). M3.6b added one new public type (`EventBusConfig`) and promoted one type from package-private to public (`InProcessEventBus`, DEC-M3-16). M3.6d-a promoted three types from package-private to public (`QueueSaturationHealthCheck` per DEC-M3-16 part 3, plus `HealthSignal` and `HealthLevel` to satisfy `-Xlint:exports` on `QueueSaturationHealthCheck`'s constructor signature).

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
- **state-store** — `StateProjection` (landed M3.5a, 2026-05-18) implements `Subscriber`. State-store's `module-info.java` declares `requires transitive com.homesynapse.event.bus`. Bus-side `subscribeRuntime` wiring of the projection (`coalesceExempt = true`) is the next integration step and lands with the bus-fix WU + lifecycle module work.
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
| **INV-SUB-ISO-06** | Self-filter is per-subscriber isolated. Wired in M3.5a by `state-store`'s package-private `SelfProducedFilter` owned by each `StateProjection` instance. |

## Amendments in force

| Amendment | Status | Relevance to this module |
|---|---|---|
| **AMD-42** — Subscriber Lifecycle and Isolation | APPLIED (2026-05-16) | Mandates the mode state machine, per-subscriber resources, supervisor discipline, isolation guarantees. Fully implemented in M3.1 (bus skeleton, FSM, supervisor, isolation). M3.2 lands REPLAY→LIVE algorithm. |
| **AMD-43** — Backpressure and Observability | APPLIED (2026-05-16) | M3.3 implemented: JFR-native emission (Decision 1), `BusMetrics` typed facade, six JFR event classes for the seven canonical metric names, IntSupplier queue depth (DEC-M3-14), `QueueSaturationHealthCheck` with 5-tick hysteresis, `DerivedWriteRateLimit` standalone (DEC-M3-15). No new ArchUnit rules added. |
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

**GOTCHA: V002 schema (`subscriber_dead_letters` table) is wired as of M3.5b.** The persistent infrastructure (`SqliteDeadLetterStore` in `core/persistence`) is reachable through the bus's `PersistentDlqWriter` seam. However the supervisor's `park(DlqEntry)` path is unchanged from M3.1 — it writes only to the in-memory ring because `DlqEntry` lacks `sequenceKey` and `eventId`. Composition-root wiring (lifecycle module) instantiates `SubscriberDlq` with a real writer, but until the supervisor is taught to construct `DeadLetter` instances and call `park(DeadLetter)`, the persistent writer remains dormant. Tracked as a follow-up work unit; M3.5b lands the infrastructure.

**GOTCHA: `InMemoryEventBus` is NOT modified in M3.1.** It remains the Phase 2 fixture for the original 4-method interface. The production bus is InProcessEventBus.

**GOTCHA: `InMemoryEventBusTest` and `InProcessEventBusTest` both extend `EventBusContractTest`.** The new Tiers 5-10 use `assumeTrue(supportsActiveRuntime())` to skip for InMemoryEventBusTest.

**GOTCHA: The supervisor's `computeBackoff()` / `sleepForBackoff()` / `MAX_RETRIES = 5` are dead code as of M3.1 and remain so after M3.5b-supervisor-wiring (2026-05-19).** The current `deliver()` parks on first failure (`attemptCount = 1`) and returns `PARKED` — there is no in-call retry loop. The future retry-loop WU will: (1) move `recordCrash()` to the post-exhaustion site so transient retries do not pollute the breaker window; (2) lift `attemptCount` to `MAX_RETRIES + 1` at park time; (3) park only after `MAX_RETRIES` exhaustion. Until then, every `RuntimeException` produces one DeadLetter row AND one crash-window increment. Do NOT reference `computeBackoff()`/`sleepForBackoff()`/`MAX_RETRIES`/`MIN_BACKOFF_MS`/`MAX_BACKOFF_MS`/`JITTER_FACTOR` from new code — they are reserved for the retry-loop wiring.

**GOTCHA: `DeadLetter.causeMessage` MUST be null-guarded by the supervisor.** `RuntimeException.getMessage()` can return `null` (e.g., `new NullPointerException()` with no constructor message). The V002 `cause_message TEXT NOT NULL` column and `DeadLetter`'s compact constructor both reject `null`. `SubscriberSupervisor.deliver()` coerces null to empty string via `e.getMessage() != null ? e.getMessage() : ""`. Any future site that constructs a `DeadLetter` from a caught Throwable must apply the same guard.

**GOTCHA: `ReplayWindowQueue` exposes `lock()/unlock()` for compound atomic operations.** Both `InProcessEventBus.notifyEvent` (mode-read + routing) and `TransitionCoordinator.drainAndPromote` (empty-check + TRANSITION→LIVE CAS) hold the queue's lock across the compound action — without this, an enqueue could interleave between the coordinator's empty-check and CAS, stranding events. The lock is a `ReentrantLock` (LTD-11). Callers must follow strict `lock()` / `try { ... } finally { unlock(); }` pairing.

**GOTCHA: `ReplayWindowQueue.enqueue(long)` returns `boolean`.** It returns `false` on overflow (queue is at 10,000) and latches an `overflowed()` flag. The bus's `notifyEvent` does NOT throw on overflow — the latched flag is the signal. The `ReplayDriver` polls `overflowed()` between page iterations; on detection it re-reads the persisted checkpoint via `CheckpointStore.readCheckpoint`, resets its internal cursor, clears the queue, and restarts the page-replay loop. Overflow is recoverable (at-least-once delivery), not data loss.

**GOTCHA: `lastReplayedPosition` is the GAP DETECTION high-water mark, not the checkpoint.** It tracks the highest `globalPosition` for which delivery was *attempted* (success or PARKED) through the supervisor during REPLAY/TRANSITION. The persisted checkpoint advances independently to track *paged-past* progress (so non-matching events also advance the checkpoint). These two values can diverge — gap detection in `TransitionCoordinator` uses only `lastReplayedPosition`.

**GOTCHA: `onCaughtUp()` exceptions become a synthetic DLQ entry, not a normal supervisor crash.** Per AMD-42 §3.4.3, an exception from `Subscriber.onCaughtUp()` does NOT go through `SubscriberSupervisor.deliver` — it bypasses the crash-window counter, backoff, and circuit breaker. Instead, `TransitionCoordinator` catches the throwable directly and parks a `SubscriberDlq.DlqEntry` at the synthetic position `CAUGHT_UP_TRANSITION_MARKER = -1L`. The subscriber remains in LIVE mode regardless.

**GOTCHA: M3.2's LIVE loop writes a per-event checkpoint after each successful delivery.** This differs from REPLAY's AMD-38 batched cadence (200 events OR 2 s). Rationale: LIVE delivery is event-by-event with single-position reads, so per-event checkpointing has negligible overhead and minimises lag against the log head. AMD-38's WAL-pressure concern applies to the continuous-reader pattern in REPLAY, not to LIVE.

**GOTCHA: JFR-native emission is the M3.3 decision (Decision 1).** A typed primitive adapter layer (Counter/Gauge/Histogram interfaces in observability module) will be needed when a pull-based metrics consumer (Prometheus, OTLP) is introduced — likely M4+. The adapter wraps the JFR events; it does not replace the emission path. This is **accepted design debt, not a gap**.

**GOTCHA: Hysteresis 5-tick recovery threshold is not conservative — it is architecturally correct for single-node deployment.** HomeSynapse has no redundancy. A false recovery means the operator sees INFO `writer.queue.recovered`, stops investigating, and is blindsided by the next CRITICAL. The 5-tick symmetric threshold prevents flapping in the failure domain where flapping is most dangerous: a system with no redundancy. Do not reduce recovery ticks below 5 without a deliberate amendment.

**GOTCHA: `BusMetricsJfr` is package-private.** Construct via `BusMetrics.jfr()` from the lifecycle module's composition root. The same applies to `NoopBusMetrics` (use `BusMetrics.noop()`). Tests can construct their own `BusMetrics` implementation directly (e.g. `BusMetricsRecorder` in `EventBusContractTest`).

**GOTCHA: `QueueSaturationHealthCheck` was promoted to public in M3.6d-a — but its `tick()` method is also now public.** Before M3.6d-a both were package-private; tests inside the bus's package called `tick()` directly. The composition root needs cross-package access to drive the 1-second cadence through `SharedScheduler`, which is in `com.homesynapse.lifecycle`. The promotion is per DEC-M3-16 part 3.

**GOTCHA: `HealthSignal` and `HealthLevel` are now public (M3.6d-a).** They were originally package-private — the brief that asked for `QueueSaturationHealthCheck` promotion incorrectly described it as a "clean" promotion. The constructor's `Consumer<HealthSignal>` parameter would have leaked the package-private `HealthSignal` type and failed `-Xlint:exports`. Both types had to be promoted in the same change to keep the module compile clean.

**GOTCHA: Writer queue depth is observed via `IntSupplier` injection (DEC-M3-14), NOT through `core/observability`.** The lifecycle module passes `() -> writeCoordinator.queueSize()` to `InProcessEventBus` at construction time. The bus holds no reference to persistence types. This overrides PLAN-M3-CONSOLIDATED-02 §7.2 and §7.9 which prescribed routing through observability. The justification is the single-value, zero-observability-module-impact tradeoff documented in the M3.3 deliberation.

**GOTCHA: `DerivedWriteRateLimit` is standalone-independent (DEC-M3-15).** The class has no compile-time dependency on StateProjection — it depends only on `Clock`, `BusMetrics`, and `Semaphore`. The M3.5a STOP gate prescribed by PLAN-M3 §7.9 does NOT apply to this milestone because the component is independently testable with mock collaborators. The pattern formalised here: M3.5a STOP gates are removed whenever the gated component is independently testable without StateProjection.

**GOTCHA: `DerivedWriteRateLimit` visibility (Bus-Fix Piece A, 2026-05-18).** The class was originally implemented as package-private in M3.3. M3.5a discovered this blocked cross-module consumption — `core/state-store`'s `StateProjection` could not name the type, so state-store introduced a `DerivedPublishGate` adapter interface as the seam (M3.5a G4 deviation). Bus-Fix Piece A promoted the class declaration and the `acquire()`/`refill()` methods to `public` (plus the two constructors needed for external construction). Composition-root wiring can now do `DerivedPublishGate gate = rateLimit::acquire;` directly. No new types were added; `DerivedPublishGate` remains in state-store as an abstraction boundary (and to keep state-store's compile-time surface independent of bus internals if the bus's limiter is ever swapped). Inspection accessors (`capacity()`, `available()`, `clock()`, `subscriberId()`) remained package-private — they exist only for in-package tests.

**GOTCHA: `BusMetrics.recordPublishLatency` is recorded by `InProcessEventBus.notifyEvent` itself, not by the upstream `EventPublisher`.** The bus measures the wall-clock duration of its notification fan-out as the bus-side contribution to overall publish latency. End-to-end publish latency from `EventPublisher.publish()` (persist + notify) is a future production-wiring concern. The metric name is canonical (AMD-43 §3.6.2); the measurement point is a deliberate scope-trimmed choice for M3.3.

**GOTCHA: `BusSubscriberLagEvent` carries both lag-events and lag-millis in a single JFR event.** The seven logical metric names map to six JFR event classes because subscriber lag has both observations emitted from a single observation point in `liveLoop` after successful supervisor delivery. The JFR consumer (`MetricsStreamBridge`) can produce two separate `MetricSnapshot`s from one event payload.

**GOTCHA: `lagEvents` approximates the queue-tail distance via `runtime.pendingPositions().size()`.** This avoids an extra event-store query on every successful delivery (which would burn carrier-thread budget on the read executor). The approximation is correct in the steady state but lags an extra catch-up burst by one delivery interval. Production wiring may revisit this if a direct writer-tail observation becomes available cheaply.

**GOTCHA: `QueueSaturationHealthCheck` does NOT own a scheduler.** M3.5a did NOT wire this (scope was state-store only). Production wiring (lifecycle module, post-M3.5a) calls `tick()` from a 1-second `ScheduledExecutorService` task. Tests call `tick()` directly with a fixed clock to keep timing deterministic — the constructor takes only the algorithm parameters, not a scheduler. The brief's reference to a "shared supervisor scheduler" is forward-looking; the supervisor does not currently own one.

**GOTCHA: `subscriberTransitionsColdToReplayOnFirstScheduling` was relaxed in M3.2.** With M3.2's full algorithm, an empty store completes COLD→REPLAY→TRANSITION→LIVE in microseconds — the test now asserts `isIn(REPLAY, TRANSITION, LIVE)` rather than `isEqualTo(REPLAY)`. The same broadening applies to `subscribeRuntimeStartsInColdMode`. The original strict mode equality was an M3.1 artifact of the placeholder loop that never advanced past REPLAY.

## Test Fixtures and Contract Tests

The `testFixtures` source set now provides seven types (one extended for M3.4b):

| Type | Kind | Package | Purpose |
|---|---|---|---|
| `CheckpointStoreContractTest` | abstract class (9 @Test methods) | `com.homesynapse.event.bus.test` | Behavioral contract for CheckpointStore. |
| `InMemoryCheckpointStore` | class | `com.homesynapse.event.bus.test` | ConcurrentHashMap-based CheckpointStore. |
| `EventBusContractTest` | abstract class (44 @Test methods: 18 + 16 active + 10 disabled) | `com.homesynapse.event.bus.test` | Behavioral contract for EventBus. 4 @Nested tiers (Phase 2) + 4 new tiers (M3.1) + 2 disabled tiers (M3.2, M3.3). |
| `InMemoryEventBus` | class | `com.homesynapse.event.bus.test` | Phase 2 contract-test fixture. 4-method interface only. |
| `RecordingReadConnectionFactory` | class | `com.homesynapse.event.bus.test` | Recording stub for INV-SUB-ISO-02 assertions. |
| `DeadLetterStoreContractTest` | abstract class (10 @Test methods) | `com.homesynapse.event.bus.test` | M3.5b — behavioral contract for any persistent dead-letter store implementation. Subclassed by the persistence module's `SqliteDeadLetterStoreContractTest`. |
| `InProcessEventBusFactory` | utility class | `com.homesynapse.event.bus` (main package) | **M3.4a / M3.4b / M3.6b — public test factory** that constructs `InProcessEventBus` and returns it typed as the public `EventBus` interface. Lives in the main package (NOT `.test` sub-package) so it can reach package-private convenience constructors. Three static methods: `create(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory) → EventBus` (M3.4a — delegates to `createWithMetrics(..., BusMetrics.noop(), () -> 0)`); `createWithMetrics(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory, BusMetrics, IntSupplier) → EventBus` (M3.4b — delegates to `createWithConfig(..., EventBusConfig.HOME_DEFAULT)`); `createWithConfig(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory, BusMetrics, IntSupplier, EventBusConfig) → EventBus` (M3.6b — routes through the canonical 7-arg public `InProcessEventBus` constructor with a caller-supplied bus config; the only path that lets a test override replay-queue capacity or publisher-blocked threshold). The composition-root lifecycle module (M3.6d) will replace this seam with production wiring. |

### EventBusContractTest — 10 Nested Tiers

- **Tier 1 — Subscription Lifecycle (5 tests)**
- **Tier 2 — Notification and Filtering (7 tests)**
- **Tier 3 — Checkpoint Integration (4 tests)**
- **Tier 4 — Concurrency Safety (2 tests)**
- **Tier 5 — Mode State Machine (4 active tests)** — M3.1
- **Tier 6 — Per-Subscriber Isolation (6 active tests)** — M3.1
- **Tier 7 — Supervisor (5 active tests)** — M3.1
- **Tier 8 — Lifecycle (1 active test)** — M3.1
- **Tier 9 — REPLAY→LIVE Transition (6 active tests as of M3.6d-a — `reconciliationOnVersionMismatch` un-disabled and implemented)** — M3.2 + M3.6d-a
- **Tier 10 — Backpressure and Metrics (6 active tests, M3.3)** — `publishDoesNotBlockAt5000` (INV-BUS-02), `busMetricsRecordPublishLatency`, `publisherBlockedCountIncrementsAbove5000`, `publisherBlockedCountNotIncrementedBelow5000`, `writerQueueDepthGaugeSampledOnNotify`, `subscriberLagPopulatedAfterDelivery`. All gated on active runtime AND access to `BusMetricsRecorder` + `AtomicInteger queueDepth()` harness hooks. `BusMetricsRecorder` is a public static nested class on `EventBusContractTest` that records all 7 canonical metric emissions for assertion.

## Phase 3 Notes

- **M3.1 landed:** Bus skeleton, mode FSM, supervisor, per-subscriber isolation, in-memory DLQ, circuit breaker. Production `InProcessEventBus` with full 8-method interface.
- **M3.2 complete (2026-05-17):** REPLAY→TRANSITION→LIVE algorithm landed. New types `ReplayDriver` and `TransitionCoordinator`. `ReplayWindowQueue` completed with bounded 10,000-entry capacity, latched overflow flag, and lock exposure for compound atomic operations. `SubscriberRuntime` gained `lastReplayedPosition` (AtomicLong) for gap detection. `InProcessEventBus` `subscriberLoop` now drives Driver → Coordinator → LIVE pull loop; `notifyEvent` routes by mode (REPLAY/TRANSITION → queue, LIVE → pendingPositions, COLD/SUSPENDED → skip). LIVE delivery writes a per-event checkpoint. REPLAY follows AMD-38 cadence (200 events OR 2 s). Tier 9 contract suite: 5 active tests + 1 retagged to `@Disabled("M3.5a")` for `reconciliationOnVersionMismatch`. New `ReplayTransitionIT` exercises 1,000 + 500 event end-to-end run across a simulated restart with concurrent publish.
- **M3.3 complete (2026-05-17):** Backpressure metrics and observability landed (AMD-43). New types: `BusMetrics` (public interface + `noop()`/`jfr()` factories), `NoopBusMetrics`, `BusMetricsJfr`, six JFR custom event classes (`BusPublishLatencyEvent`, `BusPublisherBlockedEvent`, `BusWriterQueueDepthEvent`, `BusSubscriberLagEvent`, `BusWriteAcceptedEvent`, `BusWriteParkedEvent`), `QueueSaturationHealthCheck`, `HealthSignal` (record), `HealthLevel` (enum), `DerivedWriteRateLimit`. `InProcessEventBus` constructor extended with `(BusMetrics, IntSupplier)` parameters (DEC-M3-14 — no persistence module dependency); `notifyEvent` samples depth, emits depth gauge + publisher-blocked counter, and records bus-side publish latency; `liveLoop` records subscriber lag after each successful supervisor delivery. `SubscriberRuntime` gained a nullable `DerivedWriteRateLimit` field (still `null` for all subscribers after M3.5a — see below). Tier 10 contract suite: 5 active tests (busMetricsRecordPublishLatency, publisherBlockedCountIncrementsAbove5000, publisherBlockedCountNotIncrementedBelow5000, writerQueueDepthGaugeSampledOnNotify, subscriberLagPopulatedAfterDelivery) with a `BusMetricsRecorder` public static fixture nested in `EventBusContractTest`. New unit tests: `BusMetricsJfrTest`, `QueueSaturationHealthCheckTest`, `DerivedWriteRateLimitTest`. No new ArchUnit rules; no new `requires` directives in module-info.
- **M3.5a complete (2026-05-18):** StateProjection vertical slice landed in `core/state-store`. State-store's `module-info.java` now declares `requires transitive com.homesynapse.event.bus`. `StateProjection` (public final class in state-store) implements `Subscriber` and observes `SubscriberMode` via a public `setMode(SubscriberMode)` method on the projection itself. State-store consumes the bus's rate limiter through a `DerivedPublishGate` interface (single `acquire() throws InterruptedException` method) — the adapter seam introduced because `DerivedWriteRateLimit` was package-private at M3.5a time. The Tier 9 `reconciliationOnVersionMismatch` test in `EventBusContractTest` remains `@Disabled("M3.5a")` — enabling it requires either a test-local subscriber that drives reconciliation or bus-code changes to support subscriber-initiated REPLAY restart. **Tracked as a dedicated bus-fix WU** (decision per M3.5a deliberation §6 — kept separate from M3.5b to avoid muddying its persistence scope). M3.5a proved the reconciliation pattern end-to-end in `StateProjectionContractTest#reconciliationOnVersionMismatch` on the state-store side.
- **Bus-Fix Piece A complete (2026-05-18):** `DerivedWriteRateLimit` promoted from package-private to `public`. Class declaration, both constructors, and the `acquire()`/`refill()` methods are now public. The inspection accessors (`capacity()`, `available()`, `clock()`, `subscriberId()`) remain package-private — they are test-only. No new types, no `module-info.java` changes, no behavioral changes. Composition-root wiring can now adapt to state-store's `DerivedPublishGate` via a method reference (`DerivedPublishGate gate = rateLimit::acquire;`). `DerivedPublishGate` itself remains in state-store as an abstraction boundary. Type count change: 10 → 11 public, 19 → 18 package-private (total still 29).
- **M3.5b complete (2026-05-18):** Persistent DLQ infrastructure landed.
  - New public types: `DeadLetter` (11 fields mirroring V002), `SubscriberMaxRetries` (typed int wrapper, `DEFAULT = 5`), `PersistentDlqWriter` (`@FunctionalInterface` with `noop()` factory).
  - `SubscriberDlq` gained a `(String subscriberId, PersistentDlqWriter writer)` constructor and a new `park(DeadLetter)` method that writes to both the in-memory ring AND the persistent writer.
  - testFixtures adds `DeadLetterStoreContractTest` — the behavioral contract every persistent DLQ store implementation must pass. Subclassed by the persistence module's `SqliteDeadLetterStoreContractTest` (10 tests covering INSERT semantics, idempotent upsert via `UNIQUE(subscriber_id, event_position)`, per-subscriber isolation, position lookup, counts, null diagnostics round-trip, and null-park rejection).
  - M3.2's synthetic `CAUGHT_UP_TRANSITION` marker (`eventPosition = -1L`) is still recorded in the in-memory DLQ on `onCaughtUp()` exceptions per AMD-42 §3.4.3.
- **M3.5b-supervisor-wiring complete (2026-05-19):** Supervisor now constructs `DeadLetter` instead of `DlqEntry` in the `RuntimeException` catch block, calling `dlq.park(deadLetter)` (ring + persistent writer). Single-site replacement at `SubscriberSupervisor.deliver()`; exception taxonomy, crash window, and circuit breaker behavior are unchanged. Fields extracted from the envelope: `subscriberId` from supervisor's own field, `sequenceKey` from `envelope.subjectRef().toString()` (type-prefixed format, e.g. `entity:01H...`), `eventId` from `envelope.eventId().value()` (Ulid unwrap from EventId), `causeMessage` null-guarded with empty-string coercion, `diagnostics = null`, `attemptCount = 1` (no retry loop yet — see dead-code gotcha). Also: `InProcessEventBus.subscribeRuntime` now constructs `SubscriberDlq` via the two-arg form `(info.subscriberId(), PersistentDlqWriter.noop())` so the DLQ's identity matches the supervisor's. `TransitionCoordinator.park(DlqEntry)` for the `CAUGHT_UP_TRANSITION_MARKER = -1L` path is untouched — `DeadLetter` rejects negative `eventPosition`. New test class: `SubscriberSupervisorTest` (12 tests).
- **M3.5b-supervisor-wiring out-of-scope (deferred):** Retry loop activation (`computeBackoff`, `sleepForBackoff`, `MAX_RETRIES`); `DlqAdminEndpoint` / `ProjectionRebuildEndpoint` / `ProjectionStatusEndpoint` (operator REST tooling); lifecycle composition root wiring of a real `PersistentDlqWriter` (M3.6).
- **M3.6b complete (2026-05-20):** Operator-tunable bus configuration landed (audit findings D1-07, D4-09; DEC-M3-16 visibility promotion).
  - New public type: `EventBusConfig` (2-field record — `replayQueueCapacity`, `publisherBlockedDepthThreshold`; both validated `>= 1` in the compact constructor). Constant `HOME_DEFAULT = new EventBusConfig(10_000, 5_000)` reproduces the prior M3.4b behaviour exactly.
  - `ReplayWindowQueue` parameterised: new `ReplayWindowQueue(int maxCapacity)` constructor with `maxCapacity >= 1` validation; no-arg form retained and delegates with `MAX_CAPACITY = 10_000`. `enqueue()` now checks the instance `maxCapacity` field, not the static constant. `MAX_CAPACITY` constant retained as the documentation reference for the default — promoted to `public` so cross-package callers (e.g. operational tooling) can read the default.
  - `InProcessEventBus` promoted to `public` (DEC-M3-16). Pre-promotion audit confirmed all 8 `EventBus` interface methods use only public types in signatures — no `-Xlint:exports` warnings expected. New canonical public 7-arg constructor `(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory, BusMetrics, IntSupplier, EventBusConfig)` is the only path the composition root (M3.6d) will call directly. The existing 4-arg and 6-arg constructors remain package-private and delegate through the chain (4-arg → 6-arg → 7-arg) with `EventBusConfig.HOME_DEFAULT`. The `PUBLISHER_BLOCKED_DEPTH_THRESHOLD = 5000` static constant was removed in favour of an instance `final int publisherBlockedDepthThreshold` initialised from `config.publisherBlockedDepthThreshold()`. `subscribeRuntime` now constructs the per-subscriber `ReplayWindowQueue` with `config.replayQueueCapacity()` instead of the no-arg default.
  - `InProcessEventBusFactory` gained `createWithConfig(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory, BusMetrics, IntSupplier, EventBusConfig)`; existing `create(...)` and `createWithMetrics(...)` retained and delegate with `EventBusConfig.HOME_DEFAULT`. Tests that need to assert overflow behaviour at a smaller replay-window capacity reach the new overload.
  - `EventBusContractTest.replayWindowOverflowAt10000IsCriticalAlert` renamed to `replayWindowOverflowAtConfiguredCapacityIsCriticalAlert` and its overflow thresholds now derive from `EventBusConfig.HOME_DEFAULT.replayQueueCapacity()` rather than the literal 10,000 — keeps the assertion in lock-step with whatever capacity the harness wires.
  - New tests: `EventBusConfigTest` (4 tests — HOME_DEFAULT values, three validation rejections) and `ReplayWindowQueueTest` (4 tests — overflow at custom capacity, default-capacity backward compatibility, two validation rejections).
- **M3.6b out-of-scope (deferred to later WUs):** Per-subscriber capacity overrides (all subscribers share the same `EventBusConfig` per INV-SUB-ISO-05); `QueueSaturationHealthCheck` visibility promotion (M3.6d, DEC-M3-16 part 3); composition-root wiring (M3.6d consumes `EventBusConfig` and the now-public `InProcessEventBus`); retuning the default capacity for any tier (`HOME_DEFAULT` preserves the prior 10,000 / 5,000 values per SD-1); deriving `EventBusConfig` from `DeploymentProfile` (future work mirroring M3.6a's `PersistenceConfig` pattern).
- **Performance targets:** Bus notification fan-out within 1ms for 20 subscribers. CheckpointStore.writeCheckpoint() within 1ms.
