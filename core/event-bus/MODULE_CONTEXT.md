# event-bus — `com.homesynapse.event.bus` — 29 types — Pull-based event distribution, subscriber management, checkpoint persistence, active runtime lifecycle, backpressure metrics

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

### Public Types (10)

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
| `BusMetrics` | interface (6 methods + 2 factories) | M3.3 — typed facade for the seven canonical bus metric emissions (AMD-43 §3.6.2) | Methods: `recordPublishLatency`, `incrementPublisherBlocked`, `recordWriterQueueDepth`, `recordSubscriberLag`, `recordDerivedWriteAccepted`, `recordDerivedWriteParked`. Static factories: `noop()` and `jfr()`. All emissions are fire-and-forget; thread-safe. |

### Package-Private Types (19)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `InProcessEventBus` | class | Production EventBus implementation | Constructors: `(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory)` (convenience — wires `BusMetrics.noop()` and `() -> 0`) and `(EventStore, CheckpointStore, Clock, SubscriberReadConnectionFactory, BusMetrics, IntSupplier)` (production, M3.3). `notifyEvent` samples the IntSupplier on entry, emits depth gauge + publisher-blocked counter (DEC-M3-14, AMD-43 §3.6.2), and records publish latency on exit. `liveLoop` records subscriber lag after each successful supervisor delivery. Routes by subscriber mode: REPLAY/TRANSITION → ReplayWindowQueue; LIVE → pendingPositions + unpark; COLD/SUSPENDED → skip. The queue's lock is held across the mode-read + routing decision. Constant: `PUBLISHER_BLOCKED_DEPTH_THRESHOLD = 5000` (AMD-43 §3.6.2). |
| `SubscriberSupervisor` | class | Per-subscriber exception handling, backoff, circuit breaker | Exception taxonomy: Error/IOException/checked→SUSPENDED; RuntimeException→backoff. MIN=3s, MAX=30s, jitter=0.2. Rolling 10-min crash window, 5 crashes → SUSPENDED. |
| `SubscriberDlq` | class | Per-subscriber in-memory DLQ ring (cap 1024) | Methods: `park(DlqEntry)`, `depth()`, `clear()`. Persistent overflow wiring deferred to M3.5b. Also receives `CAUGHT_UP_TRANSITION` synthetic entries on `onCaughtUp()` exceptions (AMD-42 §3.4.3). |
| `ReplayWindowQueue` | class | Bounded thread-safe queue for events arriving during REPLAY/TRANSITION | Bound `MAX_CAPACITY = 10_000`. Internal `ReentrantLock` (LTD-11). Methods: `enqueue(long)→boolean` (false on overflow, latches `overflowed` flag), `poll()→Long`, `size()`, `isEmpty()`, `clear()`, `overflowed()`, `lock()/unlock()` for compound atomic operations. The `ReplayDriver` polls `overflowed()` and restarts REPLAY from the persisted checkpoint on overflow. |
| `SubscriberRuntime` | class | Internal bundle: VT, executor, supervisor, DLQ, mode ref, queue, lastReplayedPosition, optional rateLimit | Holds all per-subscriber resources. M3.3 added a nullable `DerivedWriteRateLimit` field. The field remains `null` for all subscribers after M3.5a: state-store's `StateProjection` consumes the bus's package-private `DerivedWriteRateLimit` through a state-store-defined `DerivedPublishGate` adapter interface rather than referencing the bus type directly, so the bus does not instantiate the limiter onto the runtime yet. Bus-side wiring is deferred to the dedicated bus-fix WU that promotes `DerivedWriteRateLimit` visibility (or adds a public adapter). `lastReplayedPosition` (AtomicLong) is the gap-detection high-water mark consumed by `TransitionCoordinator`. Closed on unsubscribe (closes rateLimit if present). |
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
| `QueueSaturationHealthCheck` | class | M3.3 — hysteresis health check on writer queue depth (AMD-43 §3.6.3) | Constructor: `(IntSupplier, Clock, int warnDepth, int criticalDepth, int saturationTicks, Consumer<HealthSignal>)`. Defaults: warn=5000, critical=10000, saturationTicks=5. `tick()` advances the state machine by one tick — call from a 1-second scheduler in production. Re-emit cadence: CRITICAL 10 s, WARN 30 s. Recovery requires 5 consecutive below-threshold ticks. |
| `HealthSignal` | record (4 fields) | M3.3 — payload of a health emission | Fields: `level` (HealthLevel), `channel` (String), `depth` (int), `timestamp` (Instant). |
| `HealthLevel` | enum (3 values) | M3.3 — bus-internal severity level: INFO, WARN, CRITICAL | Distinct from the observability module's HealthStatus — that translation happens in the lifecycle/observability bridge layer. |
| `DerivedWriteRateLimit` | class implements AutoCloseable | M3.3 — per-subscriber token bucket for derived-write throttling (AMD-43 §3.6.4) | Constructors: `(int capacity, Clock, BusMetrics, String)` and `(Clock, BusMetrics, String)` using default capacity 200. Bucket capacity 200, refill 10 tokens/50 ms (200 tokens/sec effective). `acquire()` blocks on a `Semaphore` when empty (VT-safe — no carrier pinning). `refill()` adds tokens and releases parked threads. `close()` releases parked threads and marks closed. M3.3 landed the standalone primitive. M3.5a did NOT wire it onto StateProjection directly — the class and its `acquire()`/`refill()` methods are package-private to `com.homesynapse.event.bus` and therefore not reachable from `core/state-store`, so state-store introduced a `DerivedPublishGate` adapter interface and consumes that instead. A bus-fix WU is tracked to either promote `DerivedWriteRateLimit` (and `acquire`/`refill`) to public visibility or add a public adapter type — at which point lifecycle wiring can bridge the two. |

**Total: 10 public types + 19 package-private types = 29 production types.**

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

**GOTCHA: V002 schema (`subscriber_dead_letters` table) exists but is NOT wired.** The in-memory DLQ ring (cap 1024) is the M3.1 implementation. Persistent overflow is M3.5b.

**GOTCHA: `InMemoryEventBus` is NOT modified in M3.1.** It remains the Phase 2 fixture for the original 4-method interface. The production bus is InProcessEventBus.

**GOTCHA: `InMemoryEventBusTest` and `InProcessEventBusTest` both extend `EventBusContractTest`.** The new Tiers 5-10 use `assumeTrue(supportsActiveRuntime())` to skip for InMemoryEventBusTest.

**GOTCHA: The supervisor's `deliver()` uses `Thread.sleep()` for backoff.** This is safe on virtual threads (unmounts carrier). But it means tests that exercise retries must either use short sleeps or advance a mutable clock. The MutableClock in InProcessEventBusTest enables deterministic backoff testing.

**GOTCHA: `ReplayWindowQueue` exposes `lock()/unlock()` for compound atomic operations.** Both `InProcessEventBus.notifyEvent` (mode-read + routing) and `TransitionCoordinator.drainAndPromote` (empty-check + TRANSITION→LIVE CAS) hold the queue's lock across the compound action — without this, an enqueue could interleave between the coordinator's empty-check and CAS, stranding events. The lock is a `ReentrantLock` (LTD-11). Callers must follow strict `lock()` / `try { ... } finally { unlock(); }` pairing.

**GOTCHA: `ReplayWindowQueue.enqueue(long)` returns `boolean`.** It returns `false` on overflow (queue is at 10,000) and latches an `overflowed()` flag. The bus's `notifyEvent` does NOT throw on overflow — the latched flag is the signal. The `ReplayDriver` polls `overflowed()` between page iterations; on detection it re-reads the persisted checkpoint via `CheckpointStore.readCheckpoint`, resets its internal cursor, clears the queue, and restarts the page-replay loop. Overflow is recoverable (at-least-once delivery), not data loss.

**GOTCHA: `lastReplayedPosition` is the GAP DETECTION high-water mark, not the checkpoint.** It tracks the highest `globalPosition` for which delivery was *attempted* (success or PARKED) through the supervisor during REPLAY/TRANSITION. The persisted checkpoint advances independently to track *paged-past* progress (so non-matching events also advance the checkpoint). These two values can diverge — gap detection in `TransitionCoordinator` uses only `lastReplayedPosition`.

**GOTCHA: `onCaughtUp()` exceptions become a synthetic DLQ entry, not a normal supervisor crash.** Per AMD-42 §3.4.3, an exception from `Subscriber.onCaughtUp()` does NOT go through `SubscriberSupervisor.deliver` — it bypasses the crash-window counter, backoff, and circuit breaker. Instead, `TransitionCoordinator` catches the throwable directly and parks a `SubscriberDlq.DlqEntry` at the synthetic position `CAUGHT_UP_TRANSITION_MARKER = -1L`. The subscriber remains in LIVE mode regardless.

**GOTCHA: M3.2's LIVE loop writes a per-event checkpoint after each successful delivery.** This differs from REPLAY's AMD-38 batched cadence (200 events OR 2 s). Rationale: LIVE delivery is event-by-event with single-position reads, so per-event checkpointing has negligible overhead and minimises lag against the log head. AMD-38's WAL-pressure concern applies to the continuous-reader pattern in REPLAY, not to LIVE.

**GOTCHA: JFR-native emission is the M3.3 decision (Decision 1).** A typed primitive adapter layer (Counter/Gauge/Histogram interfaces in observability module) will be needed when a pull-based metrics consumer (Prometheus, OTLP) is introduced — likely M4+. The adapter wraps the JFR events; it does not replace the emission path. This is **accepted design debt, not a gap**.

**GOTCHA: Hysteresis 5-tick recovery threshold is not conservative — it is architecturally correct for single-node deployment.** HomeSynapse has no redundancy. A false recovery means the operator sees INFO `writer.queue.recovered`, stops investigating, and is blindsided by the next CRITICAL. The 5-tick symmetric threshold prevents flapping in the failure domain where flapping is most dangerous: a system with no redundancy. Do not reduce recovery ticks below 5 without a deliberate amendment.

**GOTCHA: `BusMetricsJfr` is package-private.** Construct via `BusMetrics.jfr()` from the lifecycle module's composition root. The same applies to `NoopBusMetrics` (use `BusMetrics.noop()`). Tests can construct their own `BusMetrics` implementation directly (e.g. `BusMetricsRecorder` in `EventBusContractTest`).

**GOTCHA: Writer queue depth is observed via `IntSupplier` injection (DEC-M3-14), NOT through `core/observability`.** The lifecycle module passes `() -> writeCoordinator.queueSize()` to `InProcessEventBus` at construction time. The bus holds no reference to persistence types. This overrides PLAN-M3-CONSOLIDATED-02 §7.2 and §7.9 which prescribed routing through observability. The justification is the single-value, zero-observability-module-impact tradeoff documented in the M3.3 deliberation.

**GOTCHA: `DerivedWriteRateLimit` is standalone-independent (DEC-M3-15).** The class has no compile-time dependency on StateProjection — it depends only on `Clock`, `BusMetrics`, and `Semaphore`. The M3.5a STOP gate prescribed by PLAN-M3 §7.9 does NOT apply to this milestone because the component is independently testable with mock collaborators. The pattern formalised here: M3.5a STOP gates are removed whenever the gated component is independently testable without StateProjection.

**GOTCHA: `BusMetrics.recordPublishLatency` is recorded by `InProcessEventBus.notifyEvent` itself, not by the upstream `EventPublisher`.** The bus measures the wall-clock duration of its notification fan-out as the bus-side contribution to overall publish latency. End-to-end publish latency from `EventPublisher.publish()` (persist + notify) is a future production-wiring concern. The metric name is canonical (AMD-43 §3.6.2); the measurement point is a deliberate scope-trimmed choice for M3.3.

**GOTCHA: `BusSubscriberLagEvent` carries both lag-events and lag-millis in a single JFR event.** The seven logical metric names map to six JFR event classes because subscriber lag has both observations emitted from a single observation point in `liveLoop` after successful supervisor delivery. The JFR consumer (`MetricsStreamBridge`) can produce two separate `MetricSnapshot`s from one event payload.

**GOTCHA: `lagEvents` approximates the queue-tail distance via `runtime.pendingPositions().size()`.** This avoids an extra event-store query on every successful delivery (which would burn carrier-thread budget on the read executor). The approximation is correct in the steady state but lags an extra catch-up burst by one delivery interval. Production wiring may revisit this if a direct writer-tail observation becomes available cheaply.

**GOTCHA: `QueueSaturationHealthCheck` does NOT own a scheduler.** M3.5a did NOT wire this (scope was state-store only). Production wiring (lifecycle module, post-M3.5a) calls `tick()` from a 1-second `ScheduledExecutorService` task. Tests call `tick()` directly with a fixed clock to keep timing deterministic — the constructor takes only the algorithm parameters, not a scheduler. The brief's reference to a "shared supervisor scheduler" is forward-looking; the supervisor does not currently own one.

**GOTCHA: `subscriberTransitionsColdToReplayOnFirstScheduling` was relaxed in M3.2.** With M3.2's full algorithm, an empty store completes COLD→REPLAY→TRANSITION→LIVE in microseconds — the test now asserts `isIn(REPLAY, TRANSITION, LIVE)` rather than `isEqualTo(REPLAY)`. The same broadening applies to `subscribeRuntimeStartsInColdMode`. The original strict mode equality was an M3.1 artifact of the placeholder loop that never advanced past REPLAY.

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
- **Tier 9 — REPLAY→LIVE Transition (5 active tests + 1 disabled @Disabled("M3.5a") for `reconciliationOnVersionMismatch`)** — M3.2
- **Tier 10 — Backpressure and Metrics (6 active tests, M3.3)** — `publishDoesNotBlockAt5000` (INV-BUS-02), `busMetricsRecordPublishLatency`, `publisherBlockedCountIncrementsAbove5000`, `publisherBlockedCountNotIncrementedBelow5000`, `writerQueueDepthGaugeSampledOnNotify`, `subscriberLagPopulatedAfterDelivery`. All gated on active runtime AND access to `BusMetricsRecorder` + `AtomicInteger queueDepth()` harness hooks. `BusMetricsRecorder` is a public static nested class on `EventBusContractTest` that records all 7 canonical metric emissions for assertion.

## Phase 3 Notes

- **M3.1 landed:** Bus skeleton, mode FSM, supervisor, per-subscriber isolation, in-memory DLQ, circuit breaker. Production `InProcessEventBus` with full 8-method interface.
- **M3.2 complete (2026-05-17):** REPLAY→TRANSITION→LIVE algorithm landed. New types `ReplayDriver` and `TransitionCoordinator`. `ReplayWindowQueue` completed with bounded 10,000-entry capacity, latched overflow flag, and lock exposure for compound atomic operations. `SubscriberRuntime` gained `lastReplayedPosition` (AtomicLong) for gap detection. `InProcessEventBus` `subscriberLoop` now drives Driver → Coordinator → LIVE pull loop; `notifyEvent` routes by mode (REPLAY/TRANSITION → queue, LIVE → pendingPositions, COLD/SUSPENDED → skip). LIVE delivery writes a per-event checkpoint. REPLAY follows AMD-38 cadence (200 events OR 2 s). Tier 9 contract suite: 5 active tests + 1 retagged to `@Disabled("M3.5a")` for `reconciliationOnVersionMismatch`. New `ReplayTransitionIT` exercises 1,000 + 500 event end-to-end run across a simulated restart with concurrent publish.
- **M3.3 complete (2026-05-17):** Backpressure metrics and observability landed (AMD-43). New types: `BusMetrics` (public interface + `noop()`/`jfr()` factories), `NoopBusMetrics`, `BusMetricsJfr`, six JFR custom event classes (`BusPublishLatencyEvent`, `BusPublisherBlockedEvent`, `BusWriterQueueDepthEvent`, `BusSubscriberLagEvent`, `BusWriteAcceptedEvent`, `BusWriteParkedEvent`), `QueueSaturationHealthCheck`, `HealthSignal` (record), `HealthLevel` (enum), `DerivedWriteRateLimit`. `InProcessEventBus` constructor extended with `(BusMetrics, IntSupplier)` parameters (DEC-M3-14 — no persistence module dependency); `notifyEvent` samples depth, emits depth gauge + publisher-blocked counter, and records bus-side publish latency; `liveLoop` records subscriber lag after each successful supervisor delivery. `SubscriberRuntime` gained a nullable `DerivedWriteRateLimit` field (still `null` for all subscribers after M3.5a — see below). Tier 10 contract suite: 5 active tests (busMetricsRecordPublishLatency, publisherBlockedCountIncrementsAbove5000, publisherBlockedCountNotIncrementedBelow5000, writerQueueDepthGaugeSampledOnNotify, subscriberLagPopulatedAfterDelivery) with a `BusMetricsRecorder` public static fixture nested in `EventBusContractTest`. New unit tests: `BusMetricsJfrTest`, `QueueSaturationHealthCheckTest`, `DerivedWriteRateLimitTest`. No new ArchUnit rules; no new `requires` directives in module-info.
- **M3.5a complete (2026-05-18):** StateProjection vertical slice landed in `core/state-store`. State-store's `module-info.java` now declares `requires transitive com.homesynapse.event.bus`. `StateProjection` (public final class in state-store) implements `Subscriber` and observes `SubscriberMode` via a public `setMode(SubscriberMode)` method on the projection itself. **`DerivedWriteRateLimit` was NOT promoted in M3.5a:** the class and its `acquire()`/`refill()` methods are package-private to `com.homesynapse.event.bus`, so state-store could not reference them across the JPMS boundary. Instead, state-store introduced a `DerivedPublishGate` interface (single `acquire() throws InterruptedException` method) and depends on that. Production lifecycle wiring will need a small adapter that bridges the bus's package-private `DerivedWriteRateLimit` to state-store's `DerivedPublishGate`. The Tier 9 `reconciliationOnVersionMismatch` test in `EventBusContractTest` remains `@Disabled("M3.5a")` — enabling it requires either a test-local subscriber that drives reconciliation or bus-code changes to support subscriber-initiated REPLAY restart. **Tracked as a dedicated bus-fix WU** (decision per M3.5a deliberation §6 — kept separate from M3.5b to avoid muddying its persistence scope). M3.5a proved the reconciliation pattern end-to-end in `StateProjectionContractTest#reconciliationOnVersionMismatch` on the state-store side.
- **M3.5b pending:** Persistent DLQ wiring (DeadLetter record, SqliteDeadLetterStore, V004 DLQ indices). The in-memory DLQ ring is the current implementation. M3.2's synthetic `CAUGHT_UP_TRANSITION` marker (`eventPosition = -1L`) is recorded in the in-memory DLQ on `onCaughtUp()` exceptions per AMD-42 §3.4.3; persistent wiring is part of M3.5b.
- **Performance targets:** Bus notification fan-out within 1ms for 20 subscribers. CheckpointStore.writeCheckpoint() within 1ms.
