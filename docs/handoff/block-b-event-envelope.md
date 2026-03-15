# Block B — EventEnvelope, DomainEvent, and CausalContext Rewrite

You are implementing Block B of HomeSynapse Core Phase 2 (Interface Specification). You are acting as the NexSys Coder — an implementation engineer writing constraint-compliant, infrastructure-grade Java 21 for a local-first, event-sourced smart home operating system.

**Read the NexSys Coder skill** at `/sessions/sleepy-fervent-franklin/mnt/.skills/skills/nexsys-coder/SKILL.md` before writing any code.

---

## Project Location

Repository root: `/sessions/sleepy-fervent-franklin/mnt/ClaudeFolder/homesynapse-core`
Event model module: `core/event-model/src/main/java/com/homesynapse/event/`
Platform identity module: `platform/platform-api/src/main/java/com/homesynapse/platform/identity/`

---

## Context: What Exists Now

Block A (completed) created the Ulid value type infrastructure and retrofitted all identity types. The event-model module currently contains:

**Files that will be DELETED in this block:**
- `CorrelationId.java` — typed String wrapper, replaced by raw Ulid in CausalContext
- `CausationId.java` — typed String wrapper, replaced by raw Ulid in CausalContext
- `ActorRef.java` — typed String wrapper, replaced by raw Ulid in CausalContext

**Files that will be REWRITTEN in this block:**
- `CausalContext.java` — currently references CorrelationId/CausationId/ActorRef; must be rewritten to use raw Ulid fields

**Files that exist and must NOT be modified:**
- `SubjectType.java` — enum: ENTITY, DEVICE, INTEGRATION, AUTOMATION, SYSTEM, PERSON
- `SubjectRef.java` — record(Ulid id, SubjectType type) with factory methods
- `EventPriority.java` — enum: CRITICAL, NORMAL, DIAGNOSTIC
- `EventOrigin.java` — enum: PHYSICAL, USER_COMMAND, AUTOMATION, DEVICE_AUTONOMOUS, INTEGRATION, SYSTEM, UNKNOWN
- `EventCategory.java` — enum with 8 values, has wireValue()/fromWireValue()
- `ProcessingMode.java` — enum: LIVE, REPLAY, PROJECTION, DRY_RUN
- `CommandIdempotency.java` — enum: IDEMPOTENT, NOT_IDEMPOTENT, CONDITIONAL
- `package-info.java`

**Platform identity types (do not modify):**
- `Ulid.java` — record(long msb, long lsb) implements Comparable<Ulid>, with parse/toString (Crockford Base32), toBytes/fromBytes (BLOB(16)), extractTimestamp, isValid
- `UlidFactory.java` — monotonic generator with ReentrantLock + SecureRandom, generate() and generate(Clock)
- `EntityId.java`, `DeviceId.java`, `AreaId.java`, `AutomationId.java`, `PersonId.java`, `SystemId.java`, `HomeId.java`, `IntegrationId.java` — all `record XxxId(Ulid value) implements Comparable<XxxId>` with `of(Ulid)` and `parse(String)`

**module-info.java** for event-model (currently):
```java
module com.homesynapse.event {
    requires transitive com.homesynapse.platform;
    exports com.homesynapse.event;
}
```
This should NOT need modification.

---

## Locked Design Decisions

These decisions are final and must be followed exactly:

### Decision 1: Non-Generic EventEnvelope
`EventEnvelope` is a non-generic record. The payload field is typed as `DomainEvent` (a marker interface). Subscribers use Java 21 sealed interface pattern matching to dispatch on payload type. No type parameter.

### Decision 2: CausalContext Embedding
`EventEnvelope` carries a single `CausalContext causalContext` field (not three separate causality fields). `CausalContext` is rewritten to use raw `Ulid` fields — `Ulid correlationId`, `@Nullable Ulid causationId`, `@Nullable Ulid actorRef`. The old typed wrappers (CorrelationId, CausationId, ActorRef) are deleted.

### Decision 3: List<EventCategory> for Categories
`EventEnvelope` carries `List<EventCategory> categories` (not a Set, not a single value). The list is copied defensively in the compact constructor via `List.copyOf()` to guarantee immutability.

### Decision 4: DomainEvent is Non-Sealed Initially
`DomainEvent` starts as a plain (non-sealed) marker interface in Phase 2. It becomes `sealed` when payload records are defined in Phase 3. This avoids creating an artificial sealed hierarchy with only `DegradedEvent` as a permitted subtype.

---

## Exact Deliverables (in execution order)

### Step 1: Delete the three typed wrappers

Delete these files entirely:
- `core/event-model/src/main/java/com/homesynapse/event/CorrelationId.java`
- `core/event-model/src/main/java/com/homesynapse/event/CausationId.java`
- `core/event-model/src/main/java/com/homesynapse/event/ActorRef.java`

### Step 2: Create EventId.java

Location: `core/event-model/src/main/java/com/homesynapse/event/EventId.java`

```java
public record EventId(Ulid value) implements Comparable<EventId>
```

Pattern: Identical to EntityId/DeviceId etc. but in the event package.

**Required members:**
- Compact constructor: `Objects.requireNonNull(value, "EventId value must not be null")`
- `public static EventId of(Ulid value)` — factory
- `public static EventId parse(String crockford)` — delegates to `Ulid.parse()`
- `public int compareTo(EventId other)` — delegates to `value.compareTo(other.value)`
- `public String toString()` — delegates to `value.toString()`

**Javadoc must explain:**
- EventId is the globally unique, monotonically increasing identifier for each event in the domain event store (Doc 01 §4.1)
- Generated by EventPublisher at append time using UlidFactory
- Monotonic ordering within a single millisecond is guaranteed by UlidFactory's increment-on-same-ms behavior
- Stored as BLOB(16) in SQLite
- Used as correlation_id for root events (self-correlation) and as causation_id for derived events

### Step 3: Rewrite CausalContext.java

Location: `core/event-model/src/main/java/com/homesynapse/event/CausalContext.java`

Complete rewrite. The new record uses raw Ulid fields instead of typed wrappers:

```java
public record CausalContext(
    Ulid correlationId,
    @Nullable Ulid causationId,
    @Nullable Ulid actorRef
)
```

**Required members:**
- Compact constructor: validates `correlationId` non-null. `causationId` and `actorRef` may be null.
- `public static CausalContext root(Ulid correlationId, @Nullable Ulid actorRef)` — creates context for root events (causationId = null)
- `public static CausalContext chain(Ulid correlationId, Ulid causationId, @Nullable Ulid actorRef)` — creates context for derived events (validates causationId non-null)
- `public boolean isRoot()` — returns `causationId == null`

**@Nullable annotation:** Use `@Nullable` from `jdk.internal.lang` — NO. Do NOT use jdk.internal. Instead, simply document nullability in Javadoc. Java records don't need annotation-based null markers for Phase 2. Just use clear Javadoc: `@param causationId the causing event's ID; {@code null} for root events`. The compact constructor enforces the invariants.

**Javadoc must explain:**
- Carries causality metadata for propagation through event chains (Doc 01 §4.1, §8.3)
- correlationId: the root event's event_id, propagated unchanged through all downstream events. Non-null for all events.
- causationId: the immediately preceding event's event_id. Null for root events only.
- actorRef: the ULID of the PersonId attributable to this event chain. Null when no user is attributable.
- Root events are created via `root()` — correlation is self (the event's own ID assigned at append time), causation is null
- Derived events are created via `chain()` — inherits correlation from causing event, causation set to causing event's ID

**Imports needed:** `com.homesynapse.platform.identity.Ulid`, `java.util.Objects`

### Step 4: Create DomainEvent.java

Location: `core/event-model/src/main/java/com/homesynapse/event/DomainEvent.java`

```java
public interface DomainEvent {
}
```

A marker interface. Non-sealed (Decision 4). No methods.

**Javadoc must explain:**
- Marker interface for all event payloads carried by EventEnvelope (Doc 01 §4.1)
- Every event type in the taxonomy (Doc 01 §4.3) is represented by a record that implements DomainEvent
- Currently non-sealed; will become a sealed interface when concrete payload records are defined in Phase 3
- Subscribers use pattern matching on DomainEvent subtypes for type-safe dispatch:
  ```
  switch (envelope.payload()) {
      case StateChanged sc -> handleStateChanged(sc);
      case StateReported sr -> handleStateReported(sr);
      ...
  }
  ```
- DegradedEvent implements this interface for failed upcasting scenarios (Doc 01 §3.10)

### Step 5: Create DegradedEvent.java

Location: `core/event-model/src/main/java/com/homesynapse/event/DegradedEvent.java`

```java
public record DegradedEvent(
    String eventType,
    int schemaVersion,
    String rawPayload,
    String failureReason
) implements DomainEvent
```

**Required members:**
- Compact constructor: validates all four fields non-null, `schemaVersion >= 1`, `eventType` not blank

**Javadoc must explain:**
- Wrapper for events whose payload could not be upcast to the current schema version (Doc 01 §3.10)
- Produced by the upcaster pipeline in lenient mode (diagnostic tools, trace viewer, export utilities)
- Core projections (State Store, Automation Engine, Pending Command Ledger) run in strict mode and never see DegradedEvent — a failed upcast halts processing
- Preserves the raw JSON payload and failure description for forensic investigation
- eventType and schemaVersion are copied from the original event envelope so that diagnostic tools can identify what failed
- rawPayload is the original JSON string before the upcaster attempted transformation

### Step 6: Create EventEnvelope.java

Location: `core/event-model/src/main/java/com/homesynapse/event/EventEnvelope.java`

This is the primary deliverable.

```java
public record EventEnvelope(
    EventId eventId,
    String eventType,
    int schemaVersion,
    Instant ingestTime,
    Instant eventTime,           // nullable — null if source has no reliable clock
    SubjectRef subjectRef,
    long subjectSequence,
    long globalPosition,
    EventPriority priority,
    EventOrigin origin,
    List<EventCategory> categories,
    CausalContext causalContext,
    DomainEvent payload
)
```

That's 13 components.

**Compact constructor validations:**
- `eventId` — non-null
- `eventType` — non-null, not blank
- `schemaVersion` — >= 1
- `ingestTime` — non-null
- `eventTime` — nullable (no validation needed, null is valid)
- `subjectRef` — non-null
- `subjectSequence` — >= 1
- `globalPosition` — >= 0 (0 is valid for pre-persistence state; -1 might mean unassigned, but let's use >= 0)
- `priority` — non-null
- `origin` — non-null
- `categories` — non-null, not empty, defensively copied via `List.copyOf(categories)`
- `causalContext` — non-null
- `payload` — non-null

**Javadoc must be comprehensive enough that a developer who never read Doc 01 could implement against it:**
- Class-level: explain that EventEnvelope is the standard wrapper for every event in the domain event store. Envelope fields are owned by the core; integration-specific data lives in the payload. Immutable and thread-safe (record).
- Field-level Javadoc via @param tags:
  - eventId: globally unique, monotonic within millisecond (LTD-04), generated by EventPublisher at append time
  - eventType: dotted category key identifying the event's type per the taxonomy (Doc 01 §4.3). Core types use underscored names (e.g. state_reported). Integration types use dotted namespace ({integration}.{type}).
  - schemaVersion: positive integer identifying the payload schema version. Starts at 1. Used by the upcaster pipeline (Doc 01 §3.10) to chain transformations.
  - ingestTime: system clock at the moment the event was appended to the log. Always present, always system-derived. Storage: integer microseconds since epoch. Wire: ISO 8601 (LTD-08).
  - eventTime: when the real-world occurrence happened, as reported by the event source. Null if the source has no reliable clock. Same three-layer representation as ingestTime.
  - subjectRef: identifies the domain object this event is about — entity, device, automation, person, or system component.
  - subjectSequence: monotonically increasing within the subject's event stream. (subject_ref, subject_sequence) is a unique constraint for optimistic concurrency.
  - globalPosition: SQLite rowid. Monotonic across all subjects. Subscribers checkpoint against this value.
  - priority: delivery urgency and retention tier (CRITICAL/NORMAL/DIAGNOSTIC). Does not affect append-time durability.
  - origin: evidence-based classification of the event's source. UNKNOWN is the default when evidence is insufficient.
  - categories: consent-scope categories for this event. Populated by static lookup from eventType at creation time. Enables scoped access controls, crypto-shredding (INV-PD-07), and subscription filtering.
  - causalContext: carries correlation, causation, and actor attribution for this event in the causal chain.
  - payload: the event-type-specific data. Structure varies by (eventType, schemaVersion). Use pattern matching on DomainEvent subtypes for dispatch.

**Imports needed:** `java.time.Instant`, `java.util.List`, `java.util.Objects`, plus all event-model types.

### Step 7: Compile Gate

Run `./gradlew compileJava` from the repository root. The build must pass with zero errors and zero warnings (`-Xlint:all -Werror` is enforced by the convention plugin).

**Common pitfalls:**
- Unused imports will cause `-Xlint:all -Werror` to fail
- Spotless enforces the copyright header: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
- `module-info.java` already exports `com.homesynapse.event` — no changes needed there
- The `requires transitive com.homesynapse.platform` ensures identity types (including Ulid) are accessible to consumers

---

## Constraints

1. **Java 21** — use records, sealed interfaces (when appropriate), pattern matching
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies** — everything uses types already in platform-api and event-model
5. **Javadoc on every public type and member** — `@param`, `@return`, `@throws`, `@see` tags as appropriate
6. **Immutability** — records are immutable by nature; `List.copyOf()` for the categories list
7. **Null safety via documentation and compact constructors** — no annotation processors, no @Nullable annotations from external libraries. Document nullability in Javadoc and enforce in compact constructors.

---

## Execution Order

1. Delete CorrelationId.java, CausationId.java, ActorRef.java
2. Create EventId.java
3. Rewrite CausalContext.java (now depends only on Ulid, not on deleted types)
4. Create DomainEvent.java
5. Create DegradedEvent.java
6. Create EventEnvelope.java (depends on all of the above)
7. Run `./gradlew compileJava` and fix any issues

Do NOT modify any file not listed above. Do NOT create test files (those are Phase 3). Do NOT add dependencies to build.gradle files.
