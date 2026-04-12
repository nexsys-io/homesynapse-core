# Block D — EventPublisher, EventStore, and Supporting Types

You are implementing Block D of HomeSynapse Core Phase 2 (Interface Specification). You are acting as the NexSys Coder — an implementation engineer writing constraint-compliant, infrastructure-grade Java 21 for a local-first, event-sourced smart home operating system running on constrained hardware.

**Read the NexSys Coder skill** at `/sessions/sleepy-fervent-franklin/mnt/.skills/skills/nexsys-coder/SKILL.md` before writing any code.

---

## Project Location

Repository root: `/sessions/sleepy-fervent-franklin/mnt/ClaudeFolder/homesynapse-core`
Event model module: `core/event-model/src/main/java/com/homesynapse/event/`
Design doc: `homesynapse-core-docs/design/01-event-model-and-event-bus.md`

---

## Context: What Exists Now

The `event-model` module (`com.homesynapse.event` package) contains the foundational type system built in Blocks A and B. All files below exist and compile cleanly. **Do NOT modify any of them.**

**Identity types (in platform-api, `com.homesynapse.platform.identity`):**
- `Ulid.java` — `record Ulid(long msb, long lsb) implements Comparable<Ulid>` with parse/toString, toBytes/fromBytes, extractTimestamp, isValid
- `UlidFactory.java` — monotonic generator with ReentrantLock + SecureRandom, `generate()` and `generate(Clock)`
- `EntityId`, `DeviceId`, `AreaId`, `AutomationId`, `PersonId`, `SystemId`, `HomeId`, `IntegrationId` — all `record XxxId(Ulid value) implements Comparable<XxxId>` with `of(Ulid)` and `parse(String)`

**Event model types (in event-model, `com.homesynapse.event`):**
- `EventId.java` — `record EventId(Ulid value) implements Comparable<EventId>` with `of(Ulid)`, `parse(String)`
- `EventEnvelope.java` — 14-component record: `(EventId eventId, String eventType, int schemaVersion, Instant ingestTime, Instant eventTime, SubjectRef subjectRef, long subjectSequence, long globalPosition, EventPriority priority, EventOrigin origin, List<EventCategory> categories, CausalContext causalContext, DomainEvent payload, Ulid actorRef)`. Compact constructor validates all constraints and does `List.copyOf` on categories.
- `CausalContext.java` — `record CausalContext(Ulid correlationId, Ulid causationId)` with `root(Ulid)`, `chain(Ulid, Ulid)`, `isRoot()` factories. correlationId is non-null; causationId is nullable.
- `DomainEvent.java` — non-sealed marker interface, no methods
- `DegradedEvent.java` — `record DegradedEvent(String eventType, int schemaVersion, String rawPayload, String failureReason) implements DomainEvent`
- `SubjectRef.java` — `record SubjectRef(Ulid id, SubjectType type)` with factory methods for each subject type
- `SubjectType.java` — enum: ENTITY, DEVICE, INTEGRATION, AUTOMATION, SYSTEM, PERSON
- `EventPriority.java` — enum: CRITICAL, NORMAL, DIAGNOSTIC
- `EventOrigin.java` — enum: PHYSICAL, USER_COMMAND, AUTOMATION, DEVICE_AUTONOMOUS, INTEGRATION, SYSTEM, UNKNOWN
- `EventCategory.java` — enum with 8 values and wireValue()/fromWireValue()
- `ProcessingMode.java` — enum: LIVE, REPLAY, PROJECTION, DRY_RUN
- `CommandIdempotency.java` — enum: IDEMPOTENT, NOT_IDEMPOTENT, CONDITIONAL

**Module structure:**
- `event-model/build.gradle.kts` declares `api(project(":platform:platform-api"))` and `api(libs.slf4j.api)`
- `event-model/module-info.java`: `module com.homesynapse.event { requires transitive com.homesynapse.platform; exports com.homesynapse.event; }`
- `event-bus/build.gradle.kts` declares `api(project(":core:event-model"))`
- `event-bus` has only `package-info.java` in `com.homesynapse.event.bus` — no module-info.java yet

**Build conventions:**
- `-Xlint:all -Werror` — zero warnings, zero unused imports
- Spotless copyright header: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
- JUnit 5 + AssertJ available for tests (but no tests in this block)

---

## Design Authority

All interface designs are derived from Doc 01 (Event Model & Event Bus design document). The critical sections are:

- **§8.3 EventPublisher Method Summary** — two methods, compile-time causality enforcement
- **§8.1 Interfaces table** — lists EventPublisher, EventStore, and their consumers
- **§4.1 Event Envelope** — defines all fields the publisher must populate
- **§4.2 Domain Event Store Schema** — SQL schema, the `UNIQUE(subject_ref, subject_sequence)` constraint
- **§3.4 Subscription Model** — subscriber polling via `readFrom(pos, n)`, checkpoint-based
- **§4.5 Causal Chain Projection** — needs correlation-based queries
- **§6.7 Optimistic Concurrency Conflict** — sequence conflict handling

Read §8 (Key Interfaces) fully before starting. Also read §4.1, §4.2, and §3.4 for the query patterns.

---

## Locked Design Decision: Interfaces Live in event-model

`EventPublisher` and `EventStore` are **interfaces** (contracts), not implementations. They belong in the `event-model` module (`com.homesynapse.event` package) alongside EventEnvelope and the other types. The `event-bus` module will contain the **implementations** in a later block. This means every downstream module that depends on `event-model` can program against EventPublisher and EventStore without depending on the bus implementation.

---

## Exact Deliverables (in execution order)

### Step 1: Create EventDraft.java

Location: `core/event-model/src/main/java/com/homesynapse/event/EventDraft.java`

Doc 01 §8.3 defines two publish methods: `publish(EventDraft, CausalContext)` and `publishRoot(EventDraft)`. However, the EventPublisher needs additional metadata to build a complete EventEnvelope — metadata the caller provides but the design doc's simplified signatures don't show. The `EventDraft` record bundles all caller-provided event metadata (everything except causality fields and publisher-assigned fields).

```java
public record EventDraft(
    String eventType,
    int schemaVersion,
    Instant eventTime,          // nullable — null if source has no reliable clock
    SubjectRef subjectRef,
    EventPriority priority,
    EventOrigin origin,
    DomainEvent payload,
    Ulid actorRef               // nullable — ULID of PersonId initiating the event chain, null if not attributable
)
```

7 components. These are the fields the **caller** provides. The **publisher** generates: eventId, ingestTime, subjectSequence, globalPosition, categories (from static eventType→category lookup), actorRef (from EventDraft).

**Compact constructor validations:**
- `eventType` — non-null, not blank
- `schemaVersion` — >= 1
- `eventTime` — nullable (null is valid)
- `subjectRef` — non-null
- `priority` — non-null
- `origin` — non-null
- `payload` — non-null
- `actorRef` — nullable (null is valid)

**Javadoc must explain:**
- Bundles all caller-provided metadata for event publication via EventPublisher
- Separates caller-provided fields from publisher-assigned fields (eventId, ingestTime, subjectSequence, globalPosition, categories)
- The categories field is NOT provided by the caller — EventPublisher derives it from a static eventType→category mapping at creation time (Doc 01 §4.1, §4.4)
- The actorRef field is provided by the caller and placed directly into the EventEnvelope's actorRef field (the ULID of the PersonId initiating the event chain, or null if not attributable)
- The causal context is passed separately to EventPublisher.publish() / publishRoot() because it determines the method shape (the two-method API enforces causality at compile time)
- @param documentation for each field, consistent with EventEnvelope's field documentation

### Step 2: Create EventPublisher.java

Location: `core/event-model/src/main/java/com/homesynapse/event/EventPublisher.java`

```java
public interface EventPublisher {

    EventEnvelope publish(EventDraft draft, CausalContext cause);

    EventEnvelope publishRoot(EventDraft draft);
}
```

**Two methods, no overloads.** This is the compile-time causality enforcement from Doc 01 §8.3:
- `publish()` — for derived events (events caused by a prior event). The `CausalContext` carries the correlation chain from the causing event and is placed directly into the new EventEnvelope. The actorRef comes from the EventDraft.
- `publishRoot()` — for root events (external stimuli). The publisher creates CausalContext with correlationId = the new event's own eventId (self-correlation) and causationId = null. The actorRef comes from the EventDraft.

**Both methods:**
- Are synchronous from the caller's perspective — the event is persisted to SQLite WAL and the method returns only after the WAL commit (INV-ES-04, LTD-06)
- Return the fully-populated EventEnvelope (all 14 fields, including publisher-assigned eventId, ingestTime, subjectSequence, globalPosition, and categories)
- Subscriber notification happens asynchronously after the method returns
- Throw `SequenceConflictException` if the `(subjectRef, subjectSequence)` unique constraint is violated (Doc 01 §6.7)

**Javadoc must be thorough:**
- Interface-level: explain that EventPublisher is the sole write path into the domain event store. Single-writer model (LTD-03). The two-method API enforces causality at compile time — callers cannot accidentally produce a derived event without a causal context, and cannot produce a root event with one.
- Method-level: explain what each method does, what the publisher generates vs. what the caller provides, the synchronous persistence guarantee, the exception condition, and the ordering guarantee (events are assigned monotonically increasing globalPosition and per-subject subjectSequence values).
- The `cause` parameter on `publish()`: the CausalContext is extracted from the **causing** event's envelope by the caller and passed ready-made. The publisher places it directly into the new EventEnvelope. Document that the caller is responsible for constructing the correct CausalContext using CausalContext.chain() with values from the causing EventEnvelope.
- The `draft` parameter: bundles all caller-provided metadata, including the actorRef (the ULID of the PersonId who initiated this event chain, or null if not attributable).

**NOTE on the CausalContext for publish():** The caller constructs the NEW event's CausalContext themselves using `CausalContext.chain(correlationId, causationId)` and passes it ready-made. This means `publish(EventDraft, CausalContext)` receives a ready-made CausalContext that the publisher places directly into the new EventEnvelope. The publisher does NOT transform or rewrite the CausalContext — it trusts the caller built it correctly. This keeps the API clean (two parameters per method) and leverages the CausalContext.chain() factory method we already built.

For `publishRoot()`, the publisher constructs the CausalContext internally: `CausalContext.root(newEventId)` — because the correlationId is the new event's own eventId, which only the publisher knows.

Update the Javadoc accordingly: `publish()` receives a pre-built CausalContext for the new event; `publishRoot()` constructs the root CausalContext internally.

### Step 3: Create EventPage.java

Location: `core/event-model/src/main/java/com/homesynapse/event/EventPage.java`

```java
public record EventPage(
    List<EventEnvelope> events,
    long nextPosition,
    boolean hasMore
)
```

**Compact constructor:**
- `events` — non-null, defensively copied via `List.copyOf(events)`
- `nextPosition` — >= 0
- (no validation on `hasMore`)

**Javadoc must explain:**
- A page of events returned by EventStore query methods
- `events`: the envelopes in this page, in globalPosition order. May be empty if no events match.
- `nextPosition`: the globalPosition to use as the `afterPosition` argument for the next query to retrieve the subsequent page. When `hasMore` is false, this is the position of the last event in the page (or the original `afterPosition` if the page is empty).
- `hasMore`: true if there are additional events beyond this page matching the query. Callers should continue paging while this is true.
- Immutable — the events list is defensively copied.

### Step 4: Create SequenceConflictException.java

Location: `core/event-model/src/main/java/com/homesynapse/event/SequenceConflictException.java`

```java
public class SequenceConflictException extends Exception {

    private final SubjectRef subjectRef;
    private final long conflictingSequence;

    public SequenceConflictException(SubjectRef subjectRef, long conflictingSequence) { ... }

    public SubjectRef subjectRef() { ... }
    public long conflictingSequence() { ... }
}
```

**Javadoc must explain:**
- Thrown by EventPublisher when an append violates the `(subject_ref, subject_sequence)` unique constraint (Doc 01 §4.2, §6.7)
- Indicates an optimistic concurrency conflict — two events attempted to claim the same sequence number for the same subject
- Under the single-writer model (LTD-03), this should only occur if the caller computes an incorrect sequence number. The publisher assigns sequence numbers internally, so this exception indicates a bug in the publisher implementation or a corrupted event store.
- Recovery: the caller should not retry with the same sequence number. The publisher implementation should read the current max sequence and re-derive.
- Constructor should call `super()` with a descriptive message including the subjectRef and conflictingSequence.

### Step 5: Create EventStore.java

Location: `core/event-model/src/main/java/com/homesynapse/event/EventStore.java`

This is the read-side interface. The EventStore provides query primitives for subscribers, the REST API, diagnostic tools, and the causal chain projection.

```java
public interface EventStore {

    EventPage readFrom(long afterPosition, int maxCount);

    EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount);

    List<EventEnvelope> readByCorrelation(Ulid correlationId);

    EventPage readByType(String eventType, long afterPosition, int maxCount);

    EventPage readByTimeRange(Instant from, Instant to, long afterPosition, int maxCount);

    long latestPosition();
}
```

**Method contracts (document each thoroughly in Javadoc):**

- **`readFrom(long afterPosition, int maxCount)`** — the primary subscriber polling method (Doc 01 §3.4). Returns events with `globalPosition > afterPosition`, ordered by globalPosition ascending, limited to `maxCount`. Returns an EventPage with `hasMore = true` if more events exist beyond the page. `afterPosition = 0` reads from the beginning. This is the hot path — every subscriber calls this on every poll cycle.

- **`readBySubject(SubjectRef subject, long afterSequence, int maxCount)`** — reads the event stream for a specific subject. Returns events for the given subject with `subjectSequence > afterSequence`, ordered by subjectSequence ascending. Used for entity history, replay for a specific device/entity, and state projection rebuilds.

- **`readByCorrelation(Ulid correlationId)`** — returns all events in a causal chain, ordered by globalPosition ascending. No pagination — causal chains are bounded in practice (Doc 01 §4.5 warns at depth 50). Returns an empty list if no events match. Used by the causal chain projection and the trace query API.

- **`readByType(String eventType, long afterPosition, int maxCount)`** — reads events of a specific type across all subjects. Returns events with matching `eventType` and `globalPosition > afterPosition`. Used by diagnostic tools and the REST API's type-filtered event endpoints.

- **`readByTimeRange(Instant from, Instant to, long afterPosition, int maxCount)`** — reads events within a time range using `COALESCE(event_time, ingest_time)` semantics (Doc 01 §4.2 index design). `from` is inclusive, `to` is exclusive. Combined with position-based pagination via `afterPosition`. Used by the REST API's time-range query endpoints and retention queries.

- **`latestPosition()`** — returns the current global log head position (the highest globalPosition in the store). Returns 0 if the store is empty. Used by subscribers to detect how far behind they are, and by the REPLAY→LIVE transition logic (Doc 01 §3.7).

**Interface-level Javadoc:**
- EventStore is the read-side interface for the append-only domain event store (Doc 01 §4.2)
- All queries return events in their natural ordering (globalPosition for cross-subject queries, subjectSequence for per-subject queries)
- The EventStore does not provide write access — writes go through EventPublisher exclusively (single-writer model, LTD-03)
- Thread-safe: multiple subscribers may read concurrently
- Schema upcasting (Doc 01 §3.10) is applied transparently at read time — callers always receive events with current-version payloads (or DegradedEvent in lenient mode)

**Parameter validation on all methods:**
- `maxCount` must be >= 1
- `afterPosition` must be >= 0
- `afterSequence` must be >= 0
- `from` must be before `to` in readByTimeRange
- All reference parameters non-null

Throw `IllegalArgumentException` for validation failures.

### Step 6: Compile Gate

Run `./gradlew :core:event-model:compileJava :platform:platform-api:compileJava` from the repository root. Both must pass with zero errors and zero warnings (`-Xlint:all -Werror`).

**Common pitfalls:**
- Unused imports will cause `-Werror` to fail
- `java.time.Instant` import needed for EventStore and EventDraft
- `java.util.List` import needed for EventStore (readByCorrelation) and EventPage
- All new types are in `com.homesynapse.event` — the existing `module-info.java` already exports this package, no changes needed

---

## Constraints

1. **Java 21** — use records, interfaces as appropriate
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies** — everything uses types already in platform-api and event-model
5. **Javadoc on every public type, method, and constructor** — `@param`, `@return`, `@throws`, `@see` tags
6. **All types go in `com.homesynapse.event` package** within event-model module
7. **Do NOT create implementations** — these are interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files** — all work is new file creation

---

## Execution Order

1. Create EventDraft.java
2. Create SequenceConflictException.java (needed by EventPublisher)
3. Create EventPublisher.java (references EventDraft, CausalContext, SequenceConflictException)
4. Create EventPage.java (needed by EventStore)
5. Create EventStore.java (references EventPage, SubjectRef, Ulid, Instant)
6. Run `./gradlew :core:event-model:compileJava :platform:platform-api:compileJava` and fix any issues

---

## Summary of New Files

| File | Kind | Components/Methods |
|------|------|--------------------|
| EventDraft.java | record | 8 components (eventType, schemaVersion, eventTime, subjectRef, priority, origin, payload, actorRef) |
| EventPublisher.java | interface | 2 methods: publish(EventDraft, CausalContext), publishRoot(EventDraft) |
| EventPage.java | record | 3 components (events, nextPosition, hasMore) |
| SequenceConflictException.java | exception | subjectRef + conflictingSequence fields |
| EventStore.java | interface | 6 methods: readFrom, readBySubject, readByCorrelation, readByType, readByTimeRange, latestPosition |
