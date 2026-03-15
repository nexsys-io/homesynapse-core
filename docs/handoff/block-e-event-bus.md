# Block E — EventBus, Subscription Model, and Subscriber Infrastructure

You are implementing Block E of HomeSynapse Core Phase 2 (Interface Specification). You are acting as the NexSys Coder — an implementation engineer writing constraint-compliant, infrastructure-grade Java 21 for a local-first, event-sourced smart home operating system running on constrained hardware.

**Read the NexSys Coder skill** before writing any code.

---

## Project Location

Repository root: `homesynapse-core`
Event model module: `core/event-model/src/main/java/com/homesynapse/event/`
Event bus module: `core/event-bus/src/main/java/com/homesynapse/event/bus/`
Design doc: `homesynapse-core-docs/design/01-event-model-and-event-bus.md`

---

## Context: What Exists Now

The `event-model` module (`com.homesynapse.event` package) contains the foundational type system built in Blocks A, B, and D. All files below exist and compile cleanly with `-Xlint:all -Werror`. **Do NOT modify any of them.**

**Identity types (in platform-api, `com.homesynapse.platform.identity`):**
- `Ulid.java` — `record Ulid(long msb, long lsb) implements Comparable<Ulid>` with parse/toString, toBytes/fromBytes, extractTimestamp, isValid
- `UlidFactory.java` — monotonic generator with ReentrantLock + SecureRandom
- `EntityId`, `DeviceId`, `AreaId`, `AutomationId`, `PersonId`, `SystemId`, `HomeId`, `IntegrationId` — all `record XxxId(Ulid value) implements Comparable<XxxId>` with `of(Ulid)` and `parse(String)`

**Event model types (in event-model, `com.homesynapse.event`):**
- `EventId.java` — `record EventId(Ulid value) implements Comparable<EventId>` with `of(Ulid)`, `parse(String)`
- `EventEnvelope.java` — 13-component record (eventId, eventType, schemaVersion, ingestTime, eventTime, subjectRef, subjectSequence, globalPosition, priority, origin, categories, causalContext, payload). Compact constructor validates all constraints. `List.copyOf` on categories.
- `CausalContext.java` — `record CausalContext(Ulid correlationId, Ulid causationId, Ulid actorRef)` with `root(Ulid, Ulid)`, `chain(Ulid, Ulid, Ulid)`, `isRoot()`. correlationId non-null; causationId and actorRef nullable.
- `DomainEvent.java` — non-sealed marker interface, no methods
- `DegradedEvent.java` — `record DegradedEvent(String eventType, int schemaVersion, String rawPayload, String failureReason) implements DomainEvent`
- `SubjectRef.java` — `record SubjectRef(Ulid id, SubjectType type)` with factory methods for each subject type
- `SubjectType.java` — enum: ENTITY, DEVICE, INTEGRATION, AUTOMATION, SYSTEM, PERSON
- `EventPriority.java` — enum: CRITICAL, NORMAL, DIAGNOSTIC
- `EventOrigin.java` — enum: PHYSICAL, USER_COMMAND, AUTOMATION, DEVICE_AUTONOMOUS, INTEGRATION, SYSTEM, UNKNOWN
- `EventCategory.java` — enum with 8 values and wireValue()/fromWireValue()
- `ProcessingMode.java` — enum: LIVE, REPLAY, PROJECTION, DRY_RUN
- `CommandIdempotency.java` — enum: IDEMPOTENT, NOT_IDEMPOTENT, CONDITIONAL

**Block D types (in event-model, `com.homesynapse.event`):**
- `EventDraft.java` — record: 7 components (eventType, schemaVersion, eventTime, subjectRef, priority, origin, payload). Caller-provided metadata for EventPublisher.
- `EventPublisher.java` — interface: `publish(EventDraft, CausalContext)` and `publishRoot(EventDraft, Ulid)`. Returns EventEnvelope. Throws SequenceConflictException.
- `EventStore.java` — interface: 6 query methods (readFrom, readBySubject, readByCorrelation, readByType, readByTimeRange, latestPosition). Returns EventPage or List<EventEnvelope>.
- `EventPage.java` — record: 3 components (events, nextPosition, hasMore). Defensive copy on events list.
- `SequenceConflictException.java` — checked exception with subjectRef and conflictingSequence fields.

**Module structure:**
- `event-model/build.gradle.kts` declares `api(project(":platform:platform-api"))` and `api(libs.slf4j.api)`
- `event-model/module-info.java`: `module com.homesynapse.event { requires transitive com.homesynapse.platform; exports com.homesynapse.event; }`
- `event-bus/build.gradle.kts` declares `api(project(":core:event-model"))`
- `event-bus` has only `package-info.java` in `com.homesynapse.event.bus` — no module-info.java yet

**Build conventions:**
- `-Xlint:all -Werror` — zero warnings, zero unused imports
- Spotless copyright header: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`

---

## Design Authority

All interface designs are derived from Doc 01 (Event Model & Event Bus design document). The critical sections are:

- **§3.4 Subscription Model** — pull-based with notification, subscriber lifecycle, checkpoint semantics, re-entrant event production, delivery gap handling
- **§3.6 Backpressure and Coalescing** — subscriber-local backpressure, coalescing rules for specific DIAGNOSTIC types, coalescing exemptions for correctness-critical subscribers
- **§3.7 Processing Modes** — REPLAY to LIVE transition semantics
- **§8.1 Interfaces table** — EventBus, SubscriberLifecycle
- **§8.2 Key Types** — SubscriptionFilter
- **§4.2 Domain Event Store Schema** — subscriber_checkpoints table
- **§11.1 Metrics** — subscriber lag, process latency

Read §3.4, §3.6, §3.7, and §8 fully before starting.

---

## Locked Design Decision: EventBus Types Live in event-bus Module

The EventBus interface and all subscriber-model types belong in the `event-bus` module (`com.homesynapse.event.bus` package). This is the module that will contain the EventBus **implementation** in Phase 3, and it already depends on `event-model`. Downstream modules that need to subscribe to events will depend on `event-bus`.

**Exception:** `SubscriptionFilter` also goes in the `event-bus` module. Even though it's a pure data type, it's part of the subscription API that only the bus exposes.

---

## Exact Deliverables (in execution order)

### Step 1: Create SubscriptionFilter.java

Location: `core/event-bus/src/main/java/com/homesynapse/event/bus/SubscriptionFilter.java`

```java
public record SubscriptionFilter(
    Set<String> eventTypes,
    EventPriority minimumPriority,
    SubjectType subjectTypeFilter
)
```

3 components. Based on Doc 01 §3.4:
- `eventTypes` — the set of event type strings this subscriber wants. Empty set means "all types." Defensively copied via `Set.copyOf(eventTypes)`.
- `minimumPriority` — the minimum priority tier to receive. `DIAGNOSTIC` means receive everything. `NORMAL` means NORMAL + CRITICAL only. `CRITICAL` means CRITICAL only. Never null.
- `subjectTypeFilter` — restrict delivery to events whose subject belongs to a specific subject type category. Nullable — `null` means "all subject types."

**Compact constructor validations:**
- `eventTypes` — non-null, defensively copied via `Set.copyOf(eventTypes)`
- `minimumPriority` — non-null
- `subjectTypeFilter` — nullable (null is valid)

**Convenience factory methods:**
- `static SubscriptionFilter all()` — returns a filter that matches all events (empty event types, DIAGNOSTIC priority, null subject type)
- `static SubscriptionFilter forTypes(String... eventTypes)` — returns a filter matching specific event types at DIAGNOSTIC priority
- `static SubscriptionFilter forPriority(EventPriority minimumPriority)` — returns a filter matching all types at the given priority or above

**Add a `matches(EventEnvelope)` method:**
```java
public boolean matches(EventEnvelope envelope)
```
Returns `true` if the given envelope passes this filter. Logic:
1. If `eventTypes` is non-empty and does not contain `envelope.eventType()`, return false
2. If `envelope.priority().ordinal() > minimumPriority.ordinal()`, return false (EventPriority is ordered CRITICAL=0, NORMAL=1, DIAGNOSTIC=2 — higher ordinal means lower priority; reject if envelope's priority is lower than the minimum)
3. If `subjectTypeFilter` is non-null and does not equal `envelope.subjectRef().type()`, return false
4. Otherwise return true

**IMPORTANT:** Check the actual ordinal ordering of EventPriority. If CRITICAL has ordinal 0 (declared first), NORMAL ordinal 1, DIAGNOSTIC ordinal 2, then "minimum priority NORMAL" means "accept CRITICAL and NORMAL, reject DIAGNOSTIC." The comparison is: `envelope.priority().ordinal() > minimumPriority.ordinal()` means reject. Verify this against the existing EventPriority.java before implementing.

**Javadoc must explain:**
- How the filter operates (conjunction of all non-trivial criteria)
- Empty eventTypes means "match all"
- Priority filtering semantics (higher priority tiers are always included)
- Null subjectTypeFilter means "match all subject types"
- The matches() method contract
- Thread-safe (immutable record with defensively copied set)

### Step 2: Create SubscriberInfo.java

Location: `core/event-bus/src/main/java/com/homesynapse/event/bus/SubscriberInfo.java`

```java
public record SubscriberInfo(
    String subscriberId,
    SubscriptionFilter filter,
    boolean coalesceExempt
)
```

3 components. Captures subscriber registration metadata.
- `subscriberId` — a stable string identifier for the subscriber (e.g., `"state_projection"`, `"automation_engine"`). Used as the primary key in the subscriber_checkpoints table. Non-null, not blank.
- `filter` — the subscription filter for bus-side event matching. Non-null.
- `coalesceExempt` — `true` if this subscriber must receive every event individually, even under backpressure (Doc 01 §3.6). The State Projection and Pending Command Ledger are coalescing-exempt because skipping intermediate events would cause missed state transitions or missed confirmation matches. Default for most subscribers is `false`.

**Compact constructor validations:**
- `subscriberId` — non-null, not blank
- `filter` — non-null
- (no validation on `coalesceExempt`)

### Step 3: Create EventBus.java

Location: `core/event-bus/src/main/java/com/homesynapse/event/bus/EventBus.java`

```java
public interface EventBus {

    void subscribe(SubscriberInfo subscriber);

    void unsubscribe(String subscriberId);

    void notifyEvent(long globalPosition);

    long subscriberPosition(String subscriberId);
}
```

**Method contracts (document each thoroughly in Javadoc):**

- **`subscribe(SubscriberInfo subscriber)`** — registers a subscriber with the bus. The subscriber's filter determines which events trigger notification. The subscriber's checkpoint is loaded from the CheckpointStore at registration time. If no checkpoint exists, the subscriber starts from position 0 (beginning of log). Duplicate subscriberId registration replaces the previous registration. Throws `NullPointerException` if subscriber is null.

- **`unsubscribe(String subscriberId)`** — removes a subscriber from the bus. The subscriber will no longer receive notifications. The subscriber's checkpoint is retained in the CheckpointStore (not deleted) so that re-registration resumes from the last position. No-op if the subscriberId is not currently registered. Throws `NullPointerException` if subscriberId is null.

- **`notifyEvent(long globalPosition)`** — called by the EventPublisher after persisting an event. The bus evaluates registered filters and wakes matching subscribers via `LockSupport.unpark()` (Doc 01 §3.4). The `globalPosition` parameter is the position of the newly persisted event. The bus does NOT deliver the event directly — subscribers poll the EventStore themselves using `EventStore.readFrom()`. This separation means the bus is lightweight: it only determines who to wake, not what to deliver.

- **`subscriberPosition(String subscriberId)`** — returns the last checkpointed global position for the given subscriber. Returns 0 if the subscriber has no checkpoint. Used by monitoring and the REPLAY→LIVE transition logic (Doc 01 §3.7) to determine how far behind a subscriber is.

**Interface-level Javadoc must explain:**
- The EventBus is the notification mechanism for the pull-based subscription model (Doc 01 §3.4)
- Subscribers are notified that events exist; they poll the EventStore for actual event data
- The bus manages filter evaluation, subscriber lifecycle, and backpressure coalescing (Doc 01 §3.6)
- The bus does NOT own event persistence — that's EventPublisher's responsibility
- Thread-safe: registration, unregistration, and notification may occur concurrently

### Step 4: Create CheckpointStore.java

Location: `core/event-bus/src/main/java/com/homesynapse/event/bus/CheckpointStore.java`

```java
public interface CheckpointStore {

    long readCheckpoint(String subscriberId);

    void writeCheckpoint(String subscriberId, long globalPosition);
}
```

**Method contracts:**

- **`readCheckpoint(String subscriberId)`** — returns the last checkpointed global position for the subscriber. Returns 0 if no checkpoint exists (subscriber has never checkpointed). Used during subscriber registration to resume from the last position. Throws `NullPointerException` if subscriberId is null.

- **`writeCheckpoint(String subscriberId, long globalPosition)`** — atomically writes the subscriber's checkpoint. Called by subscribers after processing a batch of events. The checkpoint must be durable before this method returns — in the SQLite implementation, this means the write is committed to the `subscriber_checkpoints` table in the same database file as the domain event store (Doc 01 §4.2). Throws `NullPointerException` if subscriberId is null. Throws `IllegalArgumentException` if globalPosition is negative.

**Interface-level Javadoc must explain:**
- CheckpointStore manages durable subscriber checkpoints for crash recovery
- Checkpoints are stored in the `subscriber_checkpoints` table in the domain event store database (Doc 01 §4.2)
- Storing checkpoints in the same SQLite file as events enables atomic checkpoint-and-query within a single connection
- Thread-safe: multiple subscribers may read/write checkpoints concurrently

### Step 5: Create module-info.java for event-bus

Location: `core/event-bus/src/main/java/module-info.java`

```java
/**
 * Event bus — subscription, notification, checkpoint, and backpressure management.
 */
module com.homesynapse.event.bus {
    requires transitive com.homesynapse.event;

    exports com.homesynapse.event.bus;
}
```

This declares:
- `requires transitive com.homesynapse.event` — the event-bus needs EventEnvelope, EventPriority, SubjectType, etc., and any module depending on event-bus also needs event-model transitively
- `exports com.homesynapse.event.bus` — makes SubscriptionFilter, SubscriberInfo, EventBus, and CheckpointStore visible to downstream modules

### Step 6: Compile Gate

Run `./gradlew :core:event-bus:compileJava :core:event-model:compileJava :platform:platform-api:compileJava` from the repository root. All three must pass with zero errors and zero warnings (`-Xlint:all -Werror`).

**Common pitfalls:**
- Unused imports will cause `-Werror` to fail
- `java.util.Set` import needed for SubscriptionFilter
- `com.homesynapse.event.EventEnvelope` import needed for SubscriptionFilter.matches()
- `com.homesynapse.event.EventPriority` and `com.homesynapse.event.SubjectType` imports needed
- The existing `package-info.java` in event-bus should remain untouched
- Verify the `module-info.java` is in the correct location: `core/event-bus/src/main/java/module-info.java` (NOT inside the package directory)

---

## Constraints

1. **Java 21** — use records, interfaces as appropriate
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies** — everything uses types already in platform-api and event-model
5. **Javadoc on every public type, method, and constructor** — `@param`, `@return`, `@throws`, `@see` tags
6. **SubscriptionFilter, SubscriberInfo, EventBus, CheckpointStore go in `com.homesynapse.event.bus` package** within event-bus module
7. **Do NOT create implementations** — these are interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files** — all work is new file creation (except module-info.java which is new)

---

## Execution Order

1. Create SubscriptionFilter.java (standalone, references event-model types)
2. Create SubscriberInfo.java (references SubscriptionFilter)
3. Create EventBus.java (references SubscriberInfo)
4. Create CheckpointStore.java (standalone)
5. Create module-info.java for event-bus module
6. Run `./gradlew :core:event-bus:compileJava :core:event-model:compileJava :platform:platform-api:compileJava` and fix any issues

---

## Summary of New Files

| File | Module | Kind | Components/Methods |
|------|--------|------|--------------------|
| SubscriptionFilter.java | event-bus | record | 3 components (eventTypes, minimumPriority, subjectTypeFilter) + matches() + 3 factory methods |
| SubscriberInfo.java | event-bus | record | 3 components (subscriberId, filter, coalesceExempt) |
| EventBus.java | event-bus | interface | 4 methods: subscribe, unsubscribe, notifyEvent, subscriberPosition |
| CheckpointStore.java | event-bus | interface | 2 methods: readCheckpoint, writeCheckpoint |
| module-info.java | event-bus | module descriptor | requires transitive event-model, exports event.bus |
