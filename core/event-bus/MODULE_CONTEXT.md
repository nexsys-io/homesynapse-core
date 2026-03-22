# event-bus — `com.homesynapse.event.bus` — 4 types — Pull-based event distribution, subscriber management, checkpoint persistence

## Purpose

The event-bus module defines the subscription and delivery contract for HomeSynapse's in-process event distribution system. It is a pull-based, notification-driven bus: the EventBus does not deliver events directly to subscribers — it notifies matching subscribers that new events are available, and subscribers pull events from the EventStore themselves. This design enables backpressure, coalescing, and crash-safe checkpoint-based resumption. The module also defines the CheckpointStore interface for durable subscriber position tracking, ensuring that subscribers resume from the correct position after crashes.

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

- **`com.homesynapse.event.bus`** — All types in a single flat package: the EventBus interface, subscriber registration descriptor, subscription filter, and checkpoint store contract.

## Complete Type Inventory

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `EventBus` | interface | Notification-driven subscription/delivery contract for event distribution | Methods: `subscribe(SubscriberInfo)`, `unsubscribe(String subscriberId)`, `notifyEvent(long globalPosition)`, `subscriberPosition(String subscriberId)`. Does NOT deliver events — wakes subscribers via `LockSupport.unpark()` to poll `EventStore`. Thread-safe. |
| `SubscriberInfo` | record | Immutable descriptor for subscriber registration with the EventBus | Fields: `subscriberId` (String, non-null, non-blank — stable across restarts, used as PK in checkpoint table), `filter` (SubscriptionFilter, non-null), `coalesceExempt` (boolean — true for State Projection and Pending Command Ledger). |
| `SubscriptionFilter` | record | Immutable filter determining which events a subscriber receives | Fields: `eventTypes` (Set\<String\>, empty = all types, defensive copy via `Set.copyOf()`), `minimumPriority` (EventPriority, non-null), `subjectTypeFilter` (SubjectType, nullable — null = all subject types). Methods: `matches(EventEnvelope)` (conjunction of all criteria), `all()`, `forTypes(String...)`, `forPriority(EventPriority)`. |
| `CheckpointStore` | interface | Durable storage for subscriber checkpoint positions | Methods: `readCheckpoint(String subscriberId)` → `long` (returns 0 if no checkpoint exists), `writeCheckpoint(String subscriberId, long globalPosition)`. Stored in `subscriber_checkpoints` table in same SQLite database as domain events. Thread-safe. |

**Total: 4 public types + 1 package-info.java + 1 module-info.java = 6 Java files.**

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **event-model** (`com.homesynapse.event`) | `requires transitive` (API dependency) — Event types for filter evaluation | `EventEnvelope` (passed to `SubscriptionFilter.matches()`), `EventPriority` (filter field for minimum priority), `SubjectType` (filter field for subject type restriction). |
| **platform-api** (transitive through event-model) | Identity types used indirectly | `Ulid`, typed ID wrappers accessed through `SubjectRef` on `EventEnvelope`. |

## Consumers

### Current consumers (modules with completed Phase 2 specs):
None directly — event-bus defines contracts that are consumed by the persistence module (implements CheckpointStore) and the startup-lifecycle module (wires EventBus to EventPublisher). All subscriber modules depend on event-bus transitively.

### Planned consumers (from design doc dependency graph):
- **persistence** — Will implement `CheckpointStore` as `SqliteCheckpointStore` (writes to `subscriber_checkpoints` table in SQLite).
- **state-store** — Will register as a subscriber via `SubscriberInfo` with `coalesceExempt = true` (State Projection must see every event, no coalescing).
- **automation** — Will register as a subscriber to evaluate triggers against events.
- **integration-runtime** — Will register as a subscriber for command dispatch events.
- **websocket-api** — Will register as a subscriber to stream events to connected clients.
- **observability** — Will register as a subscriber for system metrics and health events.
- **startup-lifecycle** — Will wire the EventBus implementation, register built-in subscribers, and coordinate startup ordering so subscribers are registered before events flow.

## Cross-Module Contracts

- **Pull-based subscription model.** The EventBus does NOT deliver events to subscribers. It wakes them via `LockSupport.unpark()`. Each subscriber is responsible for calling `EventStore.readFrom()` to pull events starting from their checkpoint position. This is a deliberate design choice that enables per-subscriber backpressure and crash-safe resumption.
- **At-least-once delivery with subscriber idempotency.** If the system crashes between event persistence and checkpoint update, the subscriber will re-process events from its last checkpoint on recovery. Subscribers MUST be idempotent (INV-ES-05).
- **Backpressure coalescing for DIAGNOSTIC events.** Non-exempt subscribers may have DIAGNOSTIC-priority events coalesced during periods of high throughput. This means a subscriber may not see every individual DIAGNOSTIC event — it sees the notification that events are available, then pulls the batch. State Projection and Pending Command Ledger are exempt (`coalesceExempt = true`).
- **Subscriber positions start at 0.** If `CheckpointStore.readCheckpoint()` returns 0 for a subscriber, it means the subscriber has never checkpointed and should start from the beginning of the event log. This is the only way to trigger a full replay.
- **`SubscriptionFilter.matches()` is a conjunction.** An event must satisfy ALL active filter criteria to pass: event type set membership (if non-empty), minimum priority, and subject type (if non-null). An empty event type set means "all types" — it does not mean "no types."

## Constraints

| Constraint | Description |
|---|---|
| **LTD-05** | Per-entity sequences with global position. Subscribers checkpoint against `globalPosition` (SQLite rowid), not `subjectSequence`. |
| **LTD-06** | Write-ahead persistence with at-least-once delivery. Events persisted before bus notification. Subscribers must be idempotent. |
| **INV-ES-04** | Write-ahead persistence. EventBus.notifyEvent() is called AFTER the WAL commit succeeds. Never before. |
| **INV-ES-05** | At-least-once delivery with subscriber idempotency. Duplicate delivery expected during crash recovery. |
| **INV-ES-03** | Per-entity ordering with causal consistency. Subscribers that care about per-entity ordering must process events in `globalPosition` order and use `subjectSequence` for per-entity conflict detection. |

## Sealed Hierarchies

None. This module contains no sealed types.

## Key Design Decisions

1. **Pull-based, not push-based delivery.** Subscribers are woken (notified) but pull events themselves from `EventStore`. This was chosen over direct push delivery because: (a) it enables per-subscriber backpressure without blocking the publisher, (b) slow subscribers don't affect fast subscribers, (c) crash recovery is trivial — resume from checkpoint, (d) no event buffering in the bus itself. The alternative (push-based with per-subscriber queues) was rejected due to memory overhead and complex failure modes on a constrained Pi. Reference: Doc 01 §3.4.

2. **`CheckpointStore` is a separate interface from `EventStore`.** Checkpoints are subscriber positions, not event data. They are stored in the same SQLite database for atomic checkpoint-and-query within a single transaction, but the interfaces are separate because their consumers are different (bus vs. persistence). Reference: Doc 01 §8.

3. **`subscriberId` is a plain `String`, not a typed wrapper.** Subscribers are infrastructure components (State Projection, Automation Engine, etc.), not domain objects. They don't need ULID identity or the type safety guarantees that domain IDs provide. The string must be stable across restarts because it's the primary key in the checkpoint table.

4. **`SubscriptionFilter` uses `Set<String>` for event types, not `Set<EventType>` enum.** Because event types are extensible strings (integrations can define custom types), an enum would create a closed set. The filter uses string matching against `EventEnvelope.eventType()`.

## Gotchas

**GOTCHA: CheckpointStore here is for subscriber POSITION checkpoints only.** It stores `subscriberId → globalPosition` (a single long per subscriber). The state-store module has a separate `ViewCheckpointStore` for view SNAPSHOT checkpoints (`viewName → position + serialized data blob`). These are completely different things with similar names. Do not confuse them.

**GOTCHA: `SubscriptionFilter.eventTypes` empty set means ALL types, not NO types.** An empty `eventTypes` set in `SubscriptionFilter` is a wildcard — it matches all event types. This is the opposite of what you might expect. To match no events, you would need to set `minimumPriority` to a level above CRITICAL (which is not possible — so every filter matches at least CRITICAL events).

**GOTCHA: `coalesceExempt` is critical for correctness of certain subscribers.** The State Projection and Pending Command Ledger MUST be registered with `coalesceExempt = true`. If they miss DIAGNOSTIC events due to coalescing, state will diverge from the event log. Most other subscribers (WebSocket streaming, observability) can safely coalesce.

**GOTCHA: `notifyEvent(long globalPosition)` does NOT pass the event itself.** The bus receives only the position of the newly persisted event. It loads filter-relevant metadata (event type, priority, subject type) from the event store to evaluate subscriber filters. The subscriber then pulls the full event independently.

**GOTCHA: Subscriber registration order matters at startup.** Subscribers must be registered with the EventBus BEFORE the publisher starts accepting events. If events are published before subscribers register, those events will not trigger notifications (though they are still persisted and will be processed on the next catch-up read). The startup-lifecycle module coordinates this ordering.

## Phase 3 Notes

- **EventBus needs an implementation:** `InProcessEventBus` in the core or persistence module. Must implement: subscriber registry (concurrent map), filter evaluation per notification, `LockSupport.unpark()` for matched subscribers, backpressure coalescing logic for non-exempt subscribers.
- **CheckpointStore needs a SQLite implementation:** `SqliteCheckpointStore` in the persistence module. Simple key-value store: `subscriber_checkpoints(subscriber_id TEXT PRIMARY KEY, global_position INTEGER)`. Must support atomic read-checkpoint-then-query-events within a single SQLite transaction.
- **Virtual thread model:** Each subscriber runs on its own virtual thread, blocked on `LockSupport.park()` until notified. The bus unparks matching subscribers. This is the standard virtual thread blocking pattern — no thread pools needed.
- **SQLite operation caveat.** Subscribers that perform SQLite operations (EventStore reads, EventPublisher writes, checkpoint writes) route those operations through the Persistence Layer's platform thread executor (LTD-03). The subscriber's virtual thread parks while the platform thread executes the sqlite-jdbc JNI call, then resumes when the result is available. The "no thread pools needed" statement applies to the bus notification mechanism — the database executor is a separate concern owned by the Persistence Layer. See Doc 01 §3.4 (AMD-28) and Doc 04 (AMD-27) for the full threading model.
- **Testing strategy:** Unit tests for `SubscriptionFilter.matches()` (all combinations of event type, priority, subject type). Integration tests for EventBus subscribe/notify/checkpoint cycle. Concurrency tests for multi-subscriber notification ordering.
- **Performance targets (from Doc 01 §8):** EventBus notification fan-out must complete within 1ms for up to 20 concurrent subscribers. CheckpointStore.writeCheckpoint() must complete within 1ms.
