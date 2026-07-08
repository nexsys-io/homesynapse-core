# event-model — `com.homesynapse.event` — 63 types — Universal event vocabulary, EventEnvelope, EventPublisher, EventStore, all domain event payloads

> **M9.5-DUR (2026-07-08, AMD-99): +2 registration event types + the full-fidelity mirror family — the registries become projections of the event log (REG-INV-1, register §53).** `EventTypes` **71→73** (+`DEVICE_REGISTERED`="device_registered", +`ENTITY_REGISTERED`="entity_registered"); `CORE_PRODUCTION_EVENT_CLASSES` **41→43** (+`DeviceRegisteredEvent`, +`EntityRegisteredEvent` — the ONLY two new `@EventType`/`DomainEvent` types). **The mirror family (8 new public records + 1 sealed interface, NONE annotated, NONE implementing `DomainEvent` — the 41→43 pin tripwire, test-pinned by `EntityRegisteredEventTest.mirrorsAreNotEvents`):** `HardwareIdentifierRef(namespace,value)` · `CapabilityInstanceRef` (7 fields, maps key-sorted) · `AttributeSchemaRef` (11 fields; `AttributeType` STAYS TYPED — value-model leaf; `permissions`/`validValues` flatten to sorted `List<String>`, `validValues` nullable-passthrough) · `CommandDefinitionRef` (6; `idempotencyClass`→name, `Duration` typed) · `ParameterSchemaRef` (7) · `ExpectedOutcomeRef` (3) · `ExpectationRef` (sealed, 4 NESTED permits `ExactMatchRef`/`WithinToleranceRef`/`EnumTransitionRef`/`AnyChangeRef` — `AttributeValue` stays typed) · `ConfirmationPolicyRef` (4 — the DP-a installed tuning, the trust-product fact). Package-private `PayloadMirrors` carries the shared construction discipline: **Number canonicalization** (every `Number` component pins to the Jackson-native pair — integral→`Integer`/`Long`, decimal→`Double` — so a decoded payload is STRUCTURALLY equal to the emitted one; beware `cond ? Integer : Long` numeric promotion, fixed red-first) and **deterministic collections** (Set-derived lists sorted, mirror maps key-sorted, `DeviceRegisteredEvent.hardwareIdentifiers` sorted by (namespace,value) in the compact ctor). `DeviceRegisteredEvent` (14 fields — `integrationId` is the Crockford **String** per the ratified AMD-99 §3 contract; `deviceId`/`areaId`/`viaDeviceId` raw `Ulid`, LTD-04 wrappers reconstruct at apply) / `EntityRegisteredEvent` (12 fields incl. `capabilities` — `entityRole` carries the RESOLVED name, never null). Emission contract: `adopt()` publishes device_registered → entity_registered×N → device_adopted LAST (byte-unchanged); updates are idempotent RE-EMITS of the same types (F1 — no third type). **NEW GOTCHA (carry-list C2): the registration-event mirrors must track the device-model schema — a new component on Device/Entity/CapabilityInstance/AttributeSchema/CommandDefinition/ParameterSchema/ExpectedOutcome/Expectation/ConfirmationPolicy REQUIRES a mirror field or an explicit exclusion ruling in the register; full fidelity, no silent drops (AMD-99 §3).**

> **M7.2a-2 status (execution/dispatch event slice — DONE).** AMD-92 rows 4/5/6/9 minted: 4 new `@EventType` payload records — `AutomationConditionEvaluatedEvent` (+ nested value record `EvaluatedEntityState`), `AutomationActionStartedEvent`, `AutomationActionCompletedEvent`, `AutomationConflictDetectedEvent` (+ nested value record `ConflictEntry`) — all DIAGNOSTIC priority. **`EventTypes` constants 67→71; `CORE_PRODUCTION_EVENT_CLASSES` roster 37→41** (the 4 parents; the 2 nested value records are NOT `@EventType` and do NOT enter the roster — the AMD-92 R92-2 nested-payload precedent). Residency (AMD-92-INV-01): every component is a bare `Ulid`/`String`/`int`/`boolean`/`List` or a platform/event identity type (`EntityId`/`AutomationId`/`EventId`) — no automation-resident type; `EvaluatedEntityState.value` is a plain `String` projection (NOT a typed `AttributeValue` — AMD-52 not triggered). `EvaluatedEntityState.value` and `.lastChangedByEventId` are nullable (unreported attribute / the materialized `EntityState` does not yet track the last-changing event id); `AutomationActionCompletedEvent.errorDetail` is nullable. **module-info UNCHANGED** — all component types resolve on the existing `requires transitive com.homesynapse.platform` edge. Persistence `EventCategoryMapping.TABLE` 49→53 (each maps to `EventCategory.AUTOMATION`). Count pins reconciled: EventTypesTest 71, EventTypeAnnotationTest 41, EventTypeRegistryTest 41/53, EventCategoryMappingTest 53, JacksonWarmupTest 41/53. Serde round-trips (incl. nested records, null-safe) in `AutomationEventSerdeTest`.

## Purpose

The event-model module defines the universal event vocabulary for HomeSynapse Core. It contains the `EventEnvelope` (the immutable wrapper for all events), all domain event payload records, the `EventPublisher` (sole write path into the event store), and the `EventStore` (read-side query interface). Every meaningful thing that happens in HomeSynapse produces an immutable event that flows through the types defined in this module. This is the most important module in the system — all observable state is derived by replaying events from the event store.

## Design Doc Reference

**Doc 01 — Event Model & Event Bus** is the governing design document:
- §3: Event taxonomy and type naming conventions (dotted namespace `device.state_changed`, etc.)
- §4: EventEnvelope field definitions, payload records, and behavioral contracts
- §8: Interface specifications for EventPublisher, EventStore, EventPage

The Identity & Addressing Model (foundations) also governs the `EventId` type and `SubjectRef` construction rules.

## JPMS Module

```
module com.homesynapse.event {
    requires transitive com.homesynapse.value;
    requires transitive com.homesynapse.platform;

    exports com.homesynapse.event;
}
```

The `requires transitive` on platform-api means any module that reads `com.homesynapse.event` automatically gets access to all typed ID wrappers in `com.homesynapse.platform.identity`. **`requires transitive com.homesynapse.value` was added at M4.0b-4a** (the AttributeValue relocation): it makes event-model and device-model **peers over the shared `com.homesynapse.value` leaf**, which is what lets the typed `StateChangedEvent` payload at M4.0b-4b carry an `AttributeValue` without forcing an `event → device` edge (and the JPMS cycle that would create). At the M4.0b-4a baseline event-model does **not yet name** any value type in code (only a prose Javadoc mention in `StateReportedEvent`), so this edge is **forward-prep for 4b** — harmless and unused until then. **event-model does NOT `requires com.homesynapse.device`** — the cycle is broken.

## Package Structure

- **`com.homesynapse.event`** — All types live in a single flat package. Contains: the event envelope and its supporting types, the publisher and store interfaces, all domain event payload records, enums for event metadata, and the exception hierarchy.

## Complete Type Inventory

### Core Infrastructure Types

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `EventEnvelope` | record (14 fields) | Universal immutable wrapper for all events in the domain event store | Fields: `eventId` (EventId), `eventType` (String), `schemaVersion` (int, ≥1), `ingestTime` (Instant), `eventTime` (Instant, nullable), `subjectRef` (SubjectRef), `subjectSequence` (long, ≥1), `globalPosition` (long), `priority` (EventPriority), `origin` (EventOrigin), `categories` (List\<EventCategory\>, non-empty), `causalContext` (CausalContext), `actorRef` (Ulid, nullable), `payload` (DomainEvent). Compact constructor validates all fields, defensive copy of categories via `List.copyOf()`. actorRef is nullable — null for system/autonomous events. |
| `EventPublisher` | interface | Sole write path into the domain event store — enforces single-writer model | Methods: `publish(EventDraft, CausalContext)` → `EventEnvelope`, `publishRoot(EventDraft)` → `EventEnvelope`. Throws `SequenceConflictException`. Thread-safe. publishRoot is single-parameter — actorRef comes from the EventDraft. |
| `EventStore` | interface | Read-side query interface for the append-only domain event store | Methods: `readFrom(long, int)`, `readBySubject(SubjectRef, long, int)`, `readByCorrelation(Ulid)`, `readByType(String, long, int)`, `readByTimeRange(Instant, Instant, long, int)`, `latestPosition()`. Most return `EventPage`; `readByCorrelation` returns `List<EventEnvelope>`; `latestPosition` returns `long`. |
| `EventDraft` | record (9 fields) | Pre-publish event builder — bundles caller-provided metadata | Fields: `eventType` (String), `schemaVersion` (int), `eventTime` (Instant, nullable), `subjectRef` (SubjectRef), `priority` (EventPriority), `origin` (EventOrigin), `payload` (DomainEvent), `actorRef` (Ulid, nullable), `idempotencyKey` (String, nullable — max 128 chars, non-blank when non-null; AMD-35). Publisher assigns: eventId, ingestTime, subjectSequence, globalPosition, categories. Publisher copies actorRef from draft to envelope. `idempotencyKey` is written to the `idempotency_key` column by `SqliteEventStore`; a partial unique index `(home_id, idempotency_key) WHERE idempotency_key IS NOT NULL` enforces per-home uniqueness. |
| `EventId` | record(`Ulid value`) implements `Comparable<EventId>` | Globally unique, monotonically increasing identifier for each event | Factory: `of(Ulid)`, `parse(String)`. Generated by EventPublisher at append time via `UlidFactory`. |
| `CausalContext` | record (2 fields) | Carries causality metadata for event chain propagation | Fields: `correlationId` (Ulid, non-null), `causationId` (Ulid, nullable). Static factories: `root(Ulid correlationId)`, `chain(Ulid correlationId, Ulid causationId)`. Method: `isRoot()` (true when causationId is null). Actor attribution is on EventEnvelope, not here. |
| `SubjectRef` | record | Subject identity with type discriminator for event filtering | Fields: `id` (Ulid), `type` (SubjectType). Static factories: `entity(EntityId)`, `device(DeviceId)`, `integration(IntegrationId)`, `automation(AutomationId)`, `system(SystemId)`, `person(PersonId)`. |
| `DomainEvent` | interface (marker) | Marker interface for all event payloads carried by EventEnvelope | Permanently non-sealed (AMD-33). Cannot be sealed because `IntegrationLifecycleEvent` in integration-api extends `DomainEvent` from a different JPMS module. In event-model, 38 records implement this interface: 37 core payload records (all annotated with `@EventType` — 24 prior + 8 added by the M7.1 automation run-initiation slice + 5 added by the M7.2a-1 run-lifecycle slice, AMD-92) plus `DegradedEvent` (the fallback wrapper, deliberately unannotated). |
| `DegradedEvent` | record implements `DomainEvent` | Wrapper for events whose payload could not be upcast to current schema version | Fields: `eventType` (String), `schemaVersion` (int), `rawPayload` (String), `failureReason` (String). Used in lenient mode by diagnostic tools. |
| `EventPage` | record | Pagination container for EventStore query results | Fields: `events` (List\<EventEnvelope\>), `nextPosition` (long), `hasMore` (boolean). Defensive copy via `List.copyOf()`. |
| `SequenceConflictException` | class extends `Exception` | Thrown by EventPublisher on unique constraint violation of (subjectRef, subjectSequence) | Fields: `subjectRef` (SubjectRef), `conflictingSequence` (long). Accessors: `subjectRef()`, `conflictingSequence()`. Separate from HomeSynapseException — this is an optimistic concurrency signal, not a domain error. |
| `EventType` | annotation (`@Retention(RUNTIME)`, `@Target(TYPE)`, `@Documented`) | Marks a `DomainEvent` record with its canonical event type string for registry-based deserialization (M2.1, LTD-19 / AMD-33) | Single `value()` element — must reference an `EventTypes` constant, never a raw string literal. Present on all 23 core `DomainEvent` payload records in this module. `DegradedEvent` is deliberately NOT annotated. Read at startup by `EventTypeRegistry` in the persistence module (M2.4 — COMPLETE) via `Class.getAnnotation(EventType.class)` to build the `String → Class<? extends DomainEvent>` map used to resolve the `eventType` discriminator column to a concrete record class at deserialization time. Replaces Jackson's `@JsonTypeInfo`, which is banned by ArchUnit Rule 7 (`NO_JSON_TYPE_INFO_IN_EVENTS`). Because this is a custom annotation in a different package, it does not trigger that rule. |
| `EventTypes` | final class (utility, no instantiation) | Canonical registry of core event type string constants AND the canonical roster of core event payload classes (M3.6c) | 55 `public static final String` constants (the 46 below + 7 dot-namespaced AMD-58/59 constants per the M4.C note + `CONFIG_VALIDATION_COMPLETED` = "config.validation_completed" per the M6.1/AMD-70 note + `CONFIG_SECTION_RELOADED` = "config.section_reloaded" per the M6.4/AMD-70 note): COMMAND_ISSUED, COMMAND_DISPATCHED, COMMAND_RESULT, COMMAND_CONFIRMATION_TIMED_OUT, STATE_REPORTED, STATE_REPORT_REJECTED, STATE_CHANGED, STATE_CONFIRMED, DEVICE_DISCOVERED, DEVICE_ADOPTED, DEVICE_REMOVED, DEVICE_METADATA_CHANGED, ENTITY_TRANSFERRED, ENTITY_TYPE_CHANGED, AVAILABILITY_CHANGED, ENTITY_PROFILE_CHANGED, ENTITY_ENABLED, ENTITY_DISABLED, AUTOMATION_TRIGGERED, AUTOMATION_COMPLETED, PRESENCE_SIGNAL, PRESENCE_CHANGED, SYSTEM_STARTED, SYSTEM_STOPPED, CONFIG_CHANGED, CONFIG_ERROR, MIGRATION_APPLIED, SNAPSHOT_CREATED, SYSTEM_STORAGE_CRITICAL, SYSTEM_REGISTRY_REBUILT, STORAGE_PRESSURE_CHANGED, SYSTEM_INTEGRITY_FAILURE, SYSTEM_BACKUP_FAILED, TELEMETRY_STORE_REBUILT, PERSISTENCE_VACUUM_FAILED, PERSISTENCE_RETENTION_INCOMPLETE, AUTOMATION_CAPABILITY_MISMATCH, TELEMETRY_SUMMARY, SUBSCRIBER_CHECKPOINT_EXPIRED, SUBSCRIBER_FALLING_BEHIND, CAUSALITY_DEPTH_WARNING, INTEGRATION_STARTED, INTEGRATION_STOPPED, INTEGRATION_HEALTH_CHANGED, INTEGRATION_RESTARTED, INTEGRATION_RESOURCE_EXCEEDED. The five `INTEGRATION_*` constants were added in M2.i and are consumed by the `@EventType` annotations on the `IntegrationLifecycleEvent` subtypes in the `integration-api` module. **M2.i / M3.6c also added one `public static final List<Class<? extends DomainEvent>>` field — `CORE_PRODUCTION_EVENT_CLASSES`** — the canonical, ordered list of the 24 core `DomainEvent` payload records that ship with HomeSynapse Core (22 original + `ConfigValidationCompletedEvent`, M6.1/AMD-70, + `ConfigSectionReloadedEvent`, M6.4/AMD-70). The composition root aggregates this list with `IntegrationEvents.LIFECYCLE_EVENT_CLASSES` from `com.homesynapse.integration` (via `Stream.concat`) to construct the `EventTypeRegistry` at startup. Per DECIDE-04, this explicit aggregation is the only sanctioned discovery mechanism — classpath scanning and `ServiceLoader` are banned (enforced by ArchUnit Rule 3, `noServiceLoader`). Adding a new core event record requires editing this list (the forcing function). **Currency (M7.2a-1):** the enumeration above is the M2.i/M3.6c snapshot and is NOT re-enumerated for later slices — as of M7.2a-1 `EventTypes` holds **67** `String` constants and `CORE_PRODUCTION_EVENT_CLASSES` holds **37** records (M7.1 added +7 constants / +8 records via AMD-92 rows 1,3,11–16,19; M7.2a-1 added +5 / +5 via rows 7,8,10,17,18 — `AUTOMATION_RUN_SKIPPED`, `AUTOMATION_RUN_CANCELLED`, `AUTOMATION_DISABLED`, `CASCADE_DEPTH_EXCEEDED`, `CASCADE_LOOP_DETECTED` — and reshaped `AutomationCompletedEvent` to its 7-component row-2 shape). See `EventTypes.java` for the authoritative list. |

### Exception Hierarchy

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `HomeSynapseException` | abstract class extends `Exception` | Base exception for all typed, domain-level errors | Abstract methods: `errorCode()` → String (dotted error code), `suggestedHttpStatus()` → int. Constructors: `(String message)`, `(String message, Throwable cause)`. Separate from `SequenceConflictException`. |
| `EntityNotFoundException` | class extends `HomeSynapseException` | Entity does not exist in the system | errorCode: `"entity.not_found"`, suggestedHttpStatus: 404. |
| `DeviceNotFoundException` | class extends `HomeSynapseException` | Device does not exist in the system | errorCode: `"device.not_found"`, suggestedHttpStatus: 404. |
| `CapabilityMismatchException` | class extends `HomeSynapseException` | Command targets unsupported or conflicting capability | errorCode: `"capability.mismatch"`, suggestedHttpStatus: 409. |
| `ConfigurationValidationException` | class extends `HomeSynapseException` | Configuration value fails JSON Schema or subsystem validation | errorCode: `"config.validation_failed"`, suggestedHttpStatus: 422. |
| `IntegrationUnavailableException` | class extends `HomeSynapseException` | Integration adapter unreachable or in failed state | errorCode: `"integration.unavailable"`, suggestedHttpStatus: 503. |

### Enums

| Type | Kind | Purpose | Values |
|---|---|---|---|
| `EventCategory` | enum | Consent-scope categories for event classification and crypto-shredding | DEVICE_STATE, ENERGY, PRESENCE, ENVIRONMENTAL, SECURITY, AUTOMATION, DEVICE_HEALTH, SYSTEM. Methods: `wireValue()`, `fromWireValue(String)`. |
| `EventPriority` | enum | Priority tier governing delivery urgency and retention lifetime | CRITICAL(severity=0), NORMAL(severity=1), DIAGNOSTIC(severity=2). Method: `severity()` → int. Lower severity = higher priority. Does not affect append-time durability. Use `severity()` for comparisons, not `ordinal()`. |
| `EventOrigin` | enum | Semantic source category for event provenance | PHYSICAL, USER_COMMAND, AUTOMATION, DEVICE_AUTONOMOUS, INTEGRATION, SYSTEM, UNKNOWN. |
| `SubjectType` | enum | Subject type discriminator for event bus filtering | ENTITY, DEVICE, INTEGRATION, AUTOMATION, SYSTEM, PERSON. |
| `ProcessingMode` | enum | Processing mode governing subscriber behavior during event consumption | LIVE (full processing + side effects), REPLAY (state updates only, no side effects), PROJECTION (state only), DRY_RUN (evaluation only, no execution). |
| `CommandIdempotency` | enum | Idempotency classification for commands in crash recovery | IDEMPOTENT, NOT_IDEMPOTENT, CONDITIONAL. |

### Domain Event Payload Records (all implement `DomainEvent`)

> **All 24 core payload records below carry `@EventType(EventTypes.CONSTANT)`** (M2.1; 23rd added M6.1/AMD-70, 24th added M6.4/AMD-70). The annotation value always references an `EventTypes` constant — never a raw string literal. `DegradedEvent` is the sole `DomainEvent` implementor in this module without `@EventType`: it is the fallback wrapper for failed upcasts, not a typed event, and must not be registered in `EventTypeRegistry`. See the `EventType` row in Core Infrastructure Types and the gotcha below.

> **M6.1 note (AMD-70, 2026-06-09): `ConfigValidationCompletedEvent` + `EventTypes.CONFIG_VALIDATION_COMPLETED` ("config.validation_completed") added.** First dot-namespaced CORE record (the AMD-58/59 dot-namespaced strings belong to integration-api records). **Type-residency rule (E70-1, load-bearing):** event records in `com.homesynapse.event` must NOT reference `com.homesynapse.config` types — config already `requires transitive com.homesynapse.event`, so the reverse edge would be a JPMS cycle (the AMD-52 `event↔device` class). The payload is flattened: `severityCounts` keys are config's `Severity.name()` strings carried as plain `String`s.

> **M6.4 note (AMD-70, 2026-06-11): `ConfigSectionReloadedEvent` + `EventTypes.CONFIG_SECTION_RELOADED` ("config.section_reloaded") added — the AMD-70 companion event.** Same E70-1 flattening: `appliedClassification` is config's `ReloadClassification.name()` carried as a plain `String`; every component is `java.base`. Published by the config reload pipeline once per changed section (DIAGNOSTIC/SYSTEM/null-eventTime, ruling R5). The P2 consumer/pin survey was re-run at M6.4 (all manifest sites 23→24 / 35→36 / 54→55; `HomeSynapseCore` + `IntegrationTestHarness` aggregate via `Stream.of` — verified, no count pins).

> **M4.C note (AMD-58/59, 2026-06-05): `EventTypes` gained 7 string constants used by `com.homesynapse.integration`, not by any event-model record.** Five dot-namespaced integration-lifecycle strings (`INTEGRATION_CONFIG_UPDATED`="integration.config.updated", `INTEGRATION_OPTIONS_UPDATED`, `INTEGRATION_REAUTH_REQUIRED`, `INTEGRATION_REAUTH_COMPLETED`, `INTEGRATION_MIGRATION_COMPLETED`) and two capability strings (`CAPABILITY_ADDED`="capability.added", `CAPABILITY_REMOVED`="capability.removed"). This follows the precedent of the existing five `INTEGRATION_*` snake_case constants: `EventTypes` holds the canonical strings (event-model owns the type taxonomy), while the records that carry them live in integration-api. **No new core record and no `CORE_PRODUCTION_EVENT_CLASSES` entry** — the new records register via `IntegrationEvents.LIFECYCLE_EVENT_CLASSES`/`CAPABILITY_EVENT_CLASSES`. The legacy five snake_case integration strings are frozen forever (persisted contract).

#### State Events
| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `StateReportedEvent` | record | Raw attribute observation from integration adapter | `attributeKey`, `value` (String), `unit` (nullable), `rawProtocolValue` (nullable), `rawProtocolUnit` (nullable). Priority: DIAGNOSTIC. |
| `StateChangedEvent` | record | Attribute's canonical state was updated (derived via State Projection) | `attributeKey`, `oldValue` (**`AttributeValue`, nullable** — null = first report), `newValue` (**`AttributeValue`, non-null**), `triggeredBy` (EventId). Priority: NORMAL. **AMD-52 (M4.0b-4b):** old/new are the typed `com.homesynapse.value.AttributeValue` the derivation rule reconstructed (was `String`); the compact ctor null-guards `attributeKey`/`newValue`/`triggeredBy` only. The typed payload is emitted at `events.schema_version = 2`; a legacy `schema_version = 1` String-payload row read under the typed reader degrades to a `DegradedEvent` (Path B). No Jackson annotation — the `AttributeValue` codec lives only in `core/persistence`. |
| `StateConfirmedEvent` | record | Command's intended state change confirmed by device report | `commandEventId` (EventId), `reportEventId` (EventId), `attributeKey`, `expectedValue`, `actualValue`, `matchType` (String: "exact", "within_tolerance", "enum_transition", "any_change"). Priority: NORMAL. |
| `StateReportRejectedEvent` | record | Attribute value rejected due to validation failure | `attributeKey`, `reportedValue`, `reason`, `validationRule`. Priority: DIAGNOSTIC. |

#### Command Events
| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `CommandIssuedEvent` | record | Command created and queued | `targetEntityRef` (Ulid), `commandType`, `parameters` (String — serialized JSON), `confirmationTimeoutMs` (int), `idempotencyClass` (CommandIdempotency). Priority: NORMAL. |
| `CommandDispatchedEvent` | record | Command dispatched to integration adapter | `targetEntityRef` (Ulid), `integrationId` (Ulid), `protocolMetadata` (String). Priority: DIAGNOSTIC. |
| `CommandResultEvent` | record | Command completed with result | `targetEntityRef` (Ulid), `commandType`, `outcome` (String), `failureReason` (nullable). Priority: NORMAL (success), CRITICAL (rejection/timeout). |
| `CommandConfirmationTimedOutEvent` | record | Command confirmation timed out | `commandEventId` (EventId), `resultEventId` (EventId, nullable). Priority: DIAGNOSTIC. |

#### Device Lifecycle Events
| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `DeviceDiscoveredEvent` | record | New device discovered on protocol network | `integrationId` (Ulid), `protocolAddress`, `manufacturer`, `model`. Priority: NORMAL. |
| `DeviceAdoptedEvent` | record | Discovered device accepted into system | `entityId` (Ulid). Priority: NORMAL. |
| `DeviceRemovedEvent` | record | Device removed from system | `reason`. Priority: NORMAL. |
| `AvailabilityChangedEvent` | record | Device availability status changed | `previousStatus`, `newStatus`. Priority: CRITICAL (offline), NORMAL (online). |

#### Automation Events (M7.1 run-initiation slice — AMD-92 rows 1, 3, 11–16, 19)
| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `AutomationTriggeredEvent` | record | Automation trigger matched, run begins | **RESHAPED (AMD-92 row 1):** `Ulid runId`, `EventId triggeringEventId`, `List<String> matchedTriggers` (trigger IDs, not indices), `Map<String,Set<EntityId>> resolvedTargets`, `String definitionHash`, `int cascadeDepth`. Priority: NORMAL. **Flattened per SD-1 (no automation-resident type). C1-interim: NO production publish site until M7.2.** |
| `AutomationCompletedEvent` | record | Automation run finished with terminal status | `status`, `failureReason` (nullable), `durationMs` (long). Priority: NORMAL. **Reshape is M7.2 (AMD-92 row 2) — untouched in M7.1.** |
| `AutomationInvokedEvent` | record | Automation explicitly invoked (ManualTrigger source) | `String invocationContext` (nullable). Priority: NORMAL. (Row 3) |
| `AutomationSlugRedirectEvent` | record | Selector followed a slug-tombstone chain | `requestedSlug`, `resolvedSlug`, `EntityId resolvedEntityId`. Priority: DIAGNOSTIC. (Row 11 — minted; no production producer until the tombstone substrate lands.) |
| `TriggerDurationStartedEvent` | record | `for_duration` timer started (AMD-25) | `AutomationId`, `int triggerIndex`, `String triggerId`, `EventId startingEventId`, `EntityId entityRef`, `long forDurationMs`. Priority: DIAGNOSTIC. (Row 12) |
| `TriggerDurationCancelledEvent` | record | `for_duration` timer cancelled | `AutomationId`, `int triggerIndex`, `String triggerId`, `EventId startingEventId`, `String reason`. Priority: DIAGNOSTIC. (Row 13) |
| `TriggerDurationExpiredEvent` | record | `for_duration` timer expired, fired | `AutomationId`, `int triggerIndex`, `String triggerId`, `EventId startingEventId`. Priority: DIAGNOSTIC. (Row 14) |
| `TriggerDurationStateValidatedEvent` | record | Expiry state-validation diverged | `AutomationId`, `int triggerIndex`, `String triggerId`, `EventId startingEventId`, `boolean predicateStillTrue`. Priority: DIAGNOSTIC. (Row 15) |
| `TriggerDurationLimitExceededEvent` | record | Timer rejected (concurrent-timer ceiling) | `AutomationId`, `int triggerIndex`, `String triggerId`, `int activeTimerCount`, `int maxConcurrentDurationTimers`. Priority: DIAGNOSTIC. (Row 16) |
| `AutomationCapabilityMismatchEvent` | record | Automation targets entities missing a capability | `AutomationId`, `List<EntityId> affectedEntities`, `List<String> missingCapabilityIds`. Priority: NORMAL. (Row 19 — constant pre-existed; record minted; no production producer in M7.1.) |

#### Presence Events (Tier 2 — no producer in MVP)
| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `PresenceSignalEvent` | record | Raw presence signal from integration | `signalType` ("wifi_probe", "ble_beacon", "gps_geofence"), `signalSource`, `signalData`. Priority: DIAGNOSTIC. **Tier 2 — reserved vocabulary, no MVP producer or consumer.** |
| `PresenceChangedEvent` | record | Derived presence state updated | `previousState`, `newState` ("home", "away", "unknown"). Priority: NORMAL. **Tier 2 — reserved vocabulary, no MVP producer or consumer.** |

#### System Events
| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `SystemStartedEvent` | record | HomeSynapse process startup | `version`, `startupDurationMs` (long). Priority: CRITICAL. |
| `SystemStoppedEvent` | record | HomeSynapse process shutdown | `reason`, `cleanShutdown` (boolean). Priority: CRITICAL. |
| `StoragePressureChangedEvent` | record | Storage pressure level transitions | `oldLevel`, `newLevel` ("HEALTHY"/"WARNING"/"CRITICAL"/"EMERGENCY"), `diskUsageBytes`, `thresholdBytes`. Priority: NORMAL. |

#### Configuration Events
| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `ConfigChangedEvent` | record | Configuration modified | `configPath`, `previousValue` (nullable), `newValue`. Priority: NORMAL. |
| `ConfigErrorEvent` | record | Configuration validation issue causing revert to default | `path`, `severity`, `message`, `appliedDefault`. Priority: DIAGNOSTIC. |
| `ConfigValidationCompletedEvent` | record (M6.1, AMD-70) | Config load/reload validation pass completed (observability-only, AMD-70-INV-01) | `configSchemaMajor` (int, ≥1), `configSchemaMinor` (int, ≥0), `issueCount` (int, ≥0), `severityCounts` (Map<String,Integer>, unmodifiable; keys are config `Severity.name()` strings — flattened per the E70-1 type-residency rule; values sum to `issueCount`, ctor-enforced). Priority: DIAGNOSTIC. Type string is dot-namespaced: `config.validation_completed`. |
| `ConfigSectionReloadedEvent` | record (M6.4, AMD-70) | One per configuration section actually changed by a reload (observability-only, AMD-70-INV-01; §12.4-safe — names + counts + classification, never values) | `sectionPath` (String, non-blank), `changeCount` (int, ≥1 — only changed sections publish), `issueCount` (int, ≥0 — the reload pass's warning count), `appliedClassification` (String, non-blank — config `ReloadClassification.name()` flattened per E70-1: `"HOT"`/`"INTEGRATION_RESTART"`/`"PROCESS_RESTART"`). Priority: DIAGNOSTIC. Type string: `config.section_reloaded`. |

#### Telemetry Events
| Type | Kind | Purpose | Key Fields |
|---|---|---|---|
| `TelemetrySummaryEvent` | record | Aggregated summary of raw telemetry samples | `attributeKey`, `min`, `max`, `mean`, `sum` (double), `count` (long), `periodStartEpochMs`, `periodEndEpochMs` (long), `partial` (boolean). Priority: DIAGNOSTIC. |

**Total: 49 public types + 1 package-info.java + 1 module-info.java = 51 Java files.** (M2.1 added the `EventType` annotation; M6.1/AMD-70 added `ConfigValidationCompletedEvent`; M6.4/AMD-70 added `ConfigSectionReloadedEvent`.)

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **value-model** (`com.homesynapse.value`) | `requires transitive` (M4.0b-4a) — peer-over-shared-leaf for the typed `StateChangedEvent` payload. **M4.0b-4b: now in use** — `StateChangedEvent` imports and references `AttributeValue`. Gradle scope `api`. | `AttributeValue` (the `StateChangedEvent.oldValue`/`newValue` field type). |
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types for event subjects and IDs | `Ulid`, `UlidFactory` (for EventId generation), `EntityId`, `DeviceId`, `IntegrationId`, `AutomationId`, `SystemId`, `PersonId` (for SubjectRef factory methods). |
| **SLF4J** (`slf4j.api`) | API dependency for logging | Logger interface for EventPublisher and EventStore implementations. |

## Consumers

### Current consumers (modules with completed Phase 2 specs):
- **event-bus** (`com.homesynapse.event.bus`) — `requires transitive com.homesynapse.event`. Uses: `EventEnvelope` (for filter matching in `SubscriptionFilter.matches()`), `EventPriority` (including `severity()` for filter comparison), `SubjectType`, `EventPublisher` (notified after publish), `EventStore` (subscribers pull events from store).
- **device-model** (`com.homesynapse.device`) — `requires transitive com.homesynapse.event`. Uses: `EventId` (in `CommandDefinition` and confirmation types), `CommandIdempotency` (in `IdempotencyClass` mapping).

### Planned consumers (from design doc dependency graph):
- **state-store** — Will subscribe to state events (`StateReportedEvent`, `StateChangedEvent`) and project materialized state.
- **persistence** — Will implement `EventPublisher` (SQLite WAL write path) and `EventStore` (SQLite query path).
- **integration-runtime** — Will use `EventPublisher` to publish device events, `EventDraft` to construct events.
- **automation** — Will subscribe to events via event-bus, consume `AutomationTriggeredEvent` / `AutomationCompletedEvent`.
- **rest-api** — Will query `EventStore` for event history endpoints. Will translate `HomeSynapseException` subclasses to HTTP error responses using `errorCode()` and `suggestedHttpStatus()`.
- **websocket-api** — Will stream events to connected clients.
- **observability** — Will subscribe to system events for metrics and health monitoring.

## Cross-Module Contracts

- **`EventPublisher.publish()` is synchronous — the event is durable before the method returns.** The WAL commit happens BEFORE subscriber notification. If the system crashes between publish and notification, recovery replays persisted-but-undelivered events. This is INV-ES-04. Do not treat publish as fire-and-forget.
- **Events are immutable facts (INV-ES-01).** Once an event is persisted, it is never modified or deleted during normal operation. The event log is append-only. All types in this module are records (immutable). `EventEnvelope` and `EventPage` defensively copy their collection fields.
- **`SubjectRef` carries the subject identity for per-entity sequencing.** The `(subjectRef, subjectSequence)` pair is unique across the entire event store. Two different entities can have the same sequence number, but the same entity cannot. This is enforced by a UNIQUE constraint in SQLite.
- **`EventDraft` is the caller's input; `EventEnvelope` is the publisher's output.** The publisher assigns `eventId`, `ingestTime`, `subjectSequence`, `globalPosition`, and `categories` — the caller does not provide these fields. The publisher copies `actorRef` from the draft to the envelope. The `EventDraft` → `EventEnvelope` transformation is the publisher's responsibility.
- **Actor attribution flows through `EventDraft.actorRef()` → `EventEnvelope.actorRef()`.** For root events, the caller sets actorRef on the draft. For derived events, the caller inherits actorRef from the causing event's `envelope.actorRef()`. The publisher copies actorRef from draft to envelope without transformation. actorRef is a top-level envelope field (not on CausalContext) to enable direct indexing for multi-user audit trails (INV-MU-01).
- **`ProcessingMode` governs side effects.** Subscribers MUST check `ProcessingMode` before executing side effects. During `REPLAY`, only state updates happen — no commands dispatch, no notifications send, no external writes.
- **`DomainEvent` is permanently non-sealed (AMD-33).** The marker interface does not restrict which records can implement it. It cannot be sealed because `IntegrationLifecycleEvent` extends `DomainEvent` from JPMS module `com.homesynapse.integration`, and JEP 409 requires all permitted subtypes to be in the same module. The `@EventType` annotation (LTD-19) provides type discovery without sealing.
- **`@EventType` is the type discriminator for registry-based deserialization (M2.1 / LTD-19).** Every serializable `DomainEvent` record carries `@EventType(EventTypes.CONSTANT)`. `EventTypeRegistry` in the persistence module (M2.4 — COMPLETE) reads the annotation via reflection at startup, builds a `String → Class<? extends DomainEvent>` map, and uses it to resolve the SQLite `eventType` column back to the concrete record class during deserialization through `EventPayloadCodec.decode()`. The annotation value is the stable type discriminator across schema versions (INV-ES-07): upcasters may transform payload fields between schema versions, but the `@EventType` value must not change. Consumers of this module that add new `DomainEvent` payload records MUST annotate them with `@EventType(EventTypes.CONSTANT)` and register an `EventTypes` constant — an unannotated record will be invisible to `EventTypeRegistry` and fail to deserialize. `DegradedEvent` is the single explicit exception: it wraps already-failed events and must never be registered.

## Configuration Reload Ordering Contract

The Configuration System uses a two-phase notification pattern for configuration changes:

1. **Direct callbacks fire synchronously.** `ConfigurationChangeListener` (direct callback) is the authoritative notification for subsystems that must act on config changes before other subsystems observe the change. The Automation Engine uses this path.
2. **`config_changed` event published.** After all direct listeners have completed, the `config_changed` event is published via `EventPublisher`. This is the audit record.
3. **Event bus subscribers notified asynchronously.** Subscribers to `config_changed` receive notification through the normal pull-based event bus mechanism.

Ordering is: direct callbacks fire synchronously → `config_changed` event published → event bus subscribers notified asynchronously. All other subsystems (except the Automation Engine) use the event path.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-04** | ULID for EventId and all subject identities. Generated by UlidFactory at publish time. actorRef on EventEnvelope uses raw Ulid (may be PersonId, AutomationId, or SystemId). |
| **LTD-05** | Per-entity sequences with global position. `subjectSequence` is per-subject monotonic; `globalPosition` is SQLite rowid (cross-entity). |
| **LTD-06** | Write-ahead persistence with at-least-once delivery. Events persisted before delivery to subscribers. Event store IS the outbox. |
| **LTD-08** | Jackson JSON for payload serialization. All payload records must be Jackson-serializable. Nullable Ulid (actorRef) serializes as `null` in JSON. |
| **INV-ES-01** | Events are immutable facts. Append-only log, no modifications during normal operation. |
| **INV-ES-02** | State is always derivable from events. All observable state reconstructable by replaying the event log from a checkpoint. |
| **INV-ES-03** | Per-entity ordering with causal consistency. Strict monotonic per-entity; cross-entity via timestamps and causal metadata. |
| **INV-ES-04** | Write-ahead persistence. Events durable before delivery to subscribers. |
| **INV-ES-05** | At-least-once delivery with subscriber idempotency. Duplicate delivery expected during recovery. |
| **INV-ES-06** | Every state change is explainable. CausalContext enables tracing any state to its triggering event chain. actorRef on the envelope enables per-user activity views. |
| **INV-ES-07** | Event schema evolution. `schemaVersion` field in EventEnvelope. Forward-compatible within major version. |
| **INV-MU-01** | Identity-aware device model. The event envelope's `actorRef` field is the foundation for multi-user audit trails. |

## Sealed Hierarchies

None. `DomainEvent` is permanently non-sealed (AMD-33). Event type discovery uses `@EventType` annotations with explicit registration in `EventTypeRegistry`, not sealed interface `getPermittedSubclasses()`.

## Key Design Decisions

1. **`EventEnvelope` is a record with 14 fields, not a builder pattern.** The envelope is constructed atomically by the publisher after assigning system-controlled fields. A builder was rejected because it would allow partially-constructed envelopes to exist. The compact constructor validates all fields eagerly. Reference: Doc 01 §4.

2. **`actorRef` is a top-level field on `EventEnvelope`, not on `CausalContext`.** Actor attribution was promoted from CausalContext (where it was bundled with causality metadata) to a first-class envelope field. This enables direct indexing and querying for multi-user audit trails (INV-MU-01), per-user activity views (INV-TO-01), and explainability (INV-ES-06). Raw Ulid is used (not a typed wrapper) because the actor may be a PersonId, AutomationId, or SystemId — the type is polymorphic. The consumer resolves the type from context. actorRef flows from caller → EventDraft → EventEnvelope via the publisher. Reference: Architecture Review Fixes, March 2026.

3. **`CausalContext` is a 2-field record (correlationId, causationId).** Reduced from 3 fields after actorRef was promoted to EventEnvelope. CausalContext now carries pure causality semantics only. Static factories: `root(Ulid)` (1 param), `chain(Ulid, Ulid)` (2 params). Reference: Architecture Review Fixes, March 2026.

4. **`EventPublisher.publishRoot(EventDraft)` is a single-parameter method.** The actorRef is always on the EventDraft. For root events the caller sets actorRef on the draft; the publisher constructs root CausalContext from the new eventId and copies actorRef from the draft to the envelope. This keeps the publisher API consistent between publish() and publishRoot() — actorRef always comes from the draft. Reference: Architecture Review Fixes, March 2026.

5. **Dynamic-typed payload fields use `String` (serialized JSON) in Phase 2.** Fields like `StateReportedEvent.value`, `CommandIssuedEvent.parameters`, `StateConfirmedEvent.expectedValue/actualValue` are `String` rather than typed values because the capability system (Doc 02) was not yet specified when these payload records were created. Phase 3 will introduce proper typed representations when the `AttributeValue` sealed interface from device-model is available for cross-module use. Reference: coder-lessons.md entry 2026-03-15.

6. **`EventTypes` uses string constants, not an enum.** Event type strings follow a dotted taxonomy (`device.state_changed`) and must be extensible by integrations (custom event types). An enum would create a closed set. The `EventTypes` class provides constants for the core set; custom types use String directly. Reference: Doc 01 §3.

7. **`EventPriority.severity()` provides ordinal-independent comparison.** CRITICAL=0, NORMAL=1, DIAGNOSTIC=2. Lower values indicate higher severity. Use `event.priority().severity() <= filter.minimumPriority().severity()` for filtering. Do not use `ordinal()` — ordinal is fragile against enum reordering. Reference: Architecture Review Fixes, March 2026.

8. **Two ordering systems coexist: `subjectSequence` and `globalPosition`.** Per-entity sequence for optimistic concurrency; global position (SQLite rowid) for subscriber checkpoints and catch-up reads. Neither is derived from the other. Reference: LTD-05.

9. **`HomeSynapseException` hierarchy in event-model is cross-cutting.** All modules depend on event-model for EventEnvelope, so placing the exception hierarchy here adds no new dependency edges. The REST API translates exceptions to HTTP responses using `errorCode()` and `suggestedHttpStatus()`. `SequenceConflictException` remains separate — it predates the hierarchy and has different semantics (optimistic concurrency signal, not domain error). Reference: Architecture Review Fixes, March 2026.

## Gotchas

**GOTCHA: EventEnvelope has 14 fields, not 13.** The 14th field is `actorRef` (Ulid, nullable), positioned after `causalContext` and before `payload`. This was promoted from CausalContext during the Architecture Review Fixes (March 2026). Do not use stale documentation that references 13 fields.

**GOTCHA: `CausalContext` has 2 fields, not 3.** `actorRef` was removed from CausalContext and promoted to EventEnvelope. CausalContext now contains only `correlationId` and `causationId`. The factory methods are `root(Ulid)` (1 param) and `chain(Ulid, Ulid)` (2 params). Do not use stale documentation that shows 3-param factories.

**GOTCHA: `EventPublisher.publishRoot()` takes 1 parameter, not 2.** The actorRef is on the EventDraft, not a separate parameter. Old references to `publishRoot(EventDraft, Ulid)` are stale.

**GOTCHA: `EventDraft` has 9 fields, not 7 or 8.** The 8th field is `actorRef` (Ulid, nullable), the 9th is `idempotencyKey` (String, nullable — added in M2-bridge Task 2, AMD-35). The caller sets actorRef on every draft — for root events from the initiating context, for derived events by inheriting from the causing event's `envelope.actorRef()`. `idempotencyKey` is null for most events; when non-null, it must be non-blank and at most 128 characters. The compact constructor validates these constraints. All existing `new EventDraft(...)` call sites pass `null` as the 9th argument unless idempotency deduplication is needed.

**GOTCHA: `EventEnvelope` remains at 14 fields — `idempotencyKey` is NOT on the envelope.** The `idempotencyKey` field lives on `EventDraft` (field 9) and is written directly to the SQLite `idempotency_key` column by `SqliteEventStore`. It is NOT surfaced on `EventEnvelope` because it is a write-path deduplication concern, not an event-consumption concern. Do not add it to `EventEnvelope`.

**GOTCHA: `value` fields on state event records are `String`, not typed.** `StateReportedEvent.value`, `CommandIssuedEvent.parameters`, etc. are serialized JSON strings in Phase 2. They will need typed wrappers (backed by `AttributeValue` from device-model) in Phase 3. Do not assume these are human-readable values — they're serialized form.

**GOTCHA: `EventPublisher` has TWO publish methods.** `publish(EventDraft, CausalContext)` for chained events (requires existing correlation), `publishRoot(EventDraft)` for root events (starts a new correlation chain where correlationId equals the new event's ID). Do not use the wrong one.

**GOTCHA: `SequenceConflictException` is a checked exception AND is separate from `HomeSynapseException`.** It extends `Exception` directly, not `HomeSynapseException`. Callers of `EventPublisher.publish()` MUST handle it. This is deliberate — sequence conflicts are expected during concurrent writes to the same entity and require explicit retry logic.

**GOTCHA: `EventPage.events()` returns an unmodifiable list.** `List.copyOf()` is used in the compact constructor. Attempting to modify the returned list throws `UnsupportedOperationException`.

**GOTCHA: `CausalContext.causationId` is nullable — but ONLY for root events.** For root events, `causationId` is null and `correlationId` equals the event's own ID. For chained events, `causationId` is non-null and points to the immediate cause. The `isRoot()` method checks `causationId == null`.

**GOTCHA: `EventPriority.severity()` — use this, not `ordinal()`.** CRITICAL=0, NORMAL=1, DIAGNOSTIC=2. Lower severity = higher priority. `SubscriptionFilter.matches()` uses `severity()` for comparison. Do not use `ordinal()` which is fragile against enum reordering.

**GOTCHA: Presence events (`PresenceSignalEvent`, `PresenceChangedEvent`) are Tier 2 — no MVP producer or consumer.** These event types are reserved vocabulary with payload records defined, but no integration adapter produces them and no subsystem consumes them in the MVP. They exist so the event type namespace is reserved and the payload schema is locked.

**GOTCHA: `readBySubject` pagination cursor uses `subjectSequence`, not `globalPosition`.** `EventPage.nextPosition` returned from `readBySubject` carries the last `subjectSequence` value, because `readBySubject` accepts `afterSequence` as its cursor. This is different from `readFrom` and `readByType`, which use `globalPosition` as the cursor. Mixing up cursor types across query methods silently returns wrong results — no exception is thrown.

**GOTCHA: `readByTimeRange` uses `COALESCE(event_time, ingest_time)` as effective time.** Range semantics are `[from, to)` — inclusive start, exclusive end. This matches the EventStore Javadoc. An event with `eventTime == null` falls back to its `ingestTime` for range filtering.

**GOTCHA: `readByCorrelation` returns `List<EventEnvelope>`, not `EventPage`.** This is deliberate — causal chains are bounded in practice (Doc 01 §4.5 warns at depth 50). No pagination is needed. Do not expect an `EventPage` return type from this method.

**GOTCHA: `DegradedEvent` is the ONLY `DomainEvent` implementor in event-model without `@EventType`.** All 24 core payload records carry `@EventType(EventTypes.CONSTANT)` (M2.1; 23rd added M6.1, 24th added M6.4). `DegradedEvent` is the fallback wrapper for events whose type is unknown or whose payload failed to upcast (Doc 01 §3.10) — it is never directly serialized under its own type discriminator but instead wraps an already-failed event. It must NOT be registered in `EventTypeRegistry`. The `EventTypeAnnotationTest.degradedEvent_doesNotHaveEventTypeAnnotation` test enforces this invariant. If you add a new `DomainEvent` payload record to this module, you MUST add `@EventType(EventTypes.YOUR_CONSTANT)` and extend `EventTypeAnnotationTest.EXPECTED_EVENT_RECORDS` plus bump `exactlyTwentyFourAnnotatedRecords`'s expected count (renamed from `exactlyTwentyThreeAnnotatedRecords` at M6.4) — the hardcoded list and count exist specifically to break loudly when the set changes.

**GOTCHA: `@EventType` values MUST reference `EventTypes.CONSTANT` — never a raw string literal.** Because `EventType`, `EventTypes`, and all event records live in the same `com.homesynapse.event` package, no imports are required. The `allEventTypeValues_matchEventTypesConstants` test verifies that every annotation value appears in the `EventTypes` constant set. A typo or a stray string literal will fail that test.

**GOTCHA: `@EventType` is NOT Jackson's `@JsonTypeInfo`.** HomeSynapse bans `com.fasterxml.jackson.annotation.JsonTypeInfo` via ArchUnit Rule 7 (`NO_JSON_TYPE_INFO_IN_EVENTS`). The custom `EventType` annotation in `com.homesynapse.event` is a completely separate type and does not trigger the rule. Do not introduce Jackson's polymorphic typing annotations on event records under any circumstances.

**GOTCHA: `IntegrationLifecycleEvent` subtypes in `com.homesynapse.integration` also implement `DomainEvent` but currently do NOT carry `@EventType`.** M2.1 scoped annotation application to event-model only; the 5 integration lifecycle event subtypes in integration-api will need `@EventType` before `EventTypeRegistry` (M2.4) can resolve them. This is a separate mini-task in a different JPMS module. Do not assume `Class.getAnnotation(EventType.class)` is non-null for every `DomainEvent` implementor across the codebase — only for the 22 core records in this module.

**GOTCHA: Planned decomposed interfaces (`EventAppender`, `EventReader`, `EventQuerier`) were never implemented.** The Refined Repo Architecture v2 planned to decompose the event store into three fine-grained interfaces. This decomposition was NOT implemented during Phase 2. The actual interfaces are `EventPublisher` (2 write methods) and `EventStore` (6 read methods). References to the decomposed names in planning documents are stale. `InMemoryEventStore` implements `EventPublisher` + `EventStore`.

- **S4-01 (RESOLVED by DECIDE-01):** SLF4J was previously `api()` scope in Gradle without `requires org.slf4j` in module-info. Resolved: SLF4J is now `implementation` scope per module. Each module that uses logging declares its own SLF4J dependency.

## Test Fixtures and Contract Tests

The `testFixtures` source set (`src/testFixtures/java/com/homesynapse/event/test/`) provides production-quality infrastructure that downstream modules consume for their own tests. It contains one abstract contract test, one in-memory implementation, and two factory helpers.

### testFixtures Type Inventory

| Type | Kind | Package | Purpose |
|---|---|---|---|
| `EventStoreContractTest` | abstract class (27 `@Test` methods) | `com.homesynapse.event.test` | Defines the behavioral contract for `EventPublisher` + `EventStore`. Both `InMemoryEventStore` and `SqliteEventStore` (M2.5, persistence module) pass this suite. Subclasses implement three factory methods (`publisher()`, `store()`, `resetStore()`) and inherit the full test suite via JUnit. Declares a public nested `TestPayload` record implementing `DomainEvent` as the fixture event type used by every contract test — annotated with `@EventType(TestEventTypes.TEST_EVENT)` as of M2.5 so that subclasses whose implementations use `EventTypeRegistry` (e.g., `SqliteEventStore` via `EventPayloadCodec`) can register and deserialize it through the annotation-driven path. The contract tests also publish drafts with unregistered raw `eventType` strings (`"event_1"`, `"state_changed"`, etc.) to exercise the DegradedEvent fallback on read — those paths deliberately do not round-trip to `TestPayload`. |
| `InMemoryEventStore` | class implementing `EventPublisher` + `EventStore` | `com.homesynapse.event.test` | Production-quality in-memory implementation using `ReentrantReadWriteLock` for concurrent reader / exclusive writer access. Thread-safe. `Clock`-injected for deterministic `ingestTime` assignment. `reset()` atomically clears events, global position, and per-subject sequence counters for test isolation. Constructor: `InMemoryEventStore(Clock clock)`. |
| `TestEventFactory` | final utility class (static factories + builders) | `com.homesynapse.event.test` | Static factory methods and builders for `EventDraft`, `EventEnvelope`, and `SubjectRef`. Public API: `draft()`, `draftFor(SubjectRef)`, `draftFor(SubjectRef, String)`, `draftBuilder()`, `envelope()`, `envelopeFor(SubjectRef)`, `envelopeBuilder()`, `subject()`, `deviceSubject()`, `integrationSubject()`, `automationSubject()`, `systemSubject()`, `personSubject()`. Contains public nested `Payload` record implementing `DomainEvent` for cross-module use, plus `DraftBuilder` and `EnvelopeBuilder` inner classes. `DraftBuilder` includes an `idempotencyKey(String)` builder method (defaults to null) — added in M2-bridge Task 2 to match EventDraft's 9-field expansion. |
| `TestCausalContext` | final utility class | `com.homesynapse.event.test` | Helpers for constructing `CausalContext` instances in tests. Methods: `root()` (fresh correlation ID), `rootFor(EventId)` (tied to specific event ID), `chainFrom(EventEnvelope)` (high-value helper — reduces 3-line derived-context boilerplate to one line). |
| `TestEventTypes` | final utility class (public, no instantiation) | `com.homesynapse.event.test` | Canonical event-type string constants for testFixtures payloads. **M2.5 — added 2026-04-10.** Holds `public static final String TEST_EVENT = "test_event"` — the single type discriminator referenced by the `@EventType` annotation on `EventStoreContractTest.TestPayload`. Lives in testFixtures so downstream consumers (e.g., `SqliteEventStoreTest` in `core/persistence`) can register the fixture type in their own `EventTypeRegistry` alongside `AllEventClasses.ALL_EVENTS` without needing to hardcode the string. Separate from `EventTypes` (the production constants in `com.homesynapse.event`) to keep the test namespace from polluting the production namespace. |

### EventStoreContractTest Coverage Summary

The 27 `@Test` methods are organized into 10 sections that together validate the complete `EventPublisher` + `EventStore` contract:

- **Section 1 — Basic append and read-back (4):** `publishRoot` returns envelope with publisher-assigned fields (`eventId`, `ingestTime`, `subjectSequence`, `globalPosition`, `categories`); `publishRoot` sets root causal context (`correlationId == eventId.value()`, `causationId == null`); `publish` sets derived causal context (inherits correlation, causation points to parent); published event is readable via store.
- **Section 2 — Global position ordering (2):** global position increases monotonically across subjects; `readFrom` returns events in global position order.
- **Section 3 — Per-subject sequence ordering (2):** subject sequence increments per subject (1, 2, 3…); subject sequence is independent across different subjects.
- **Section 4 — `readFrom` pagination (4):** respects `maxCount` limit; paginates correctly across multiple pages using `nextPosition`; `afterPosition` filters correctly; empty store returns empty page with `hasMore == false`.
- **Section 5 — `readBySubject` (3):** filters to correct subject; returns events ordered by `subjectSequence`; `afterSequence` cursor filters correctly.
- **Section 6 — `readByCorrelation` (2):** returns full causal chain; returns empty list for unknown correlation ID.
- **Section 7 — `readByType` (1):** filters to matching event type strings.
- **Section 8 — `readByTimeRange` (1):** filters within `[from, to)` range boundaries using effective event time.
- **Section 9 — `latestPosition` (2):** returns zero when store is empty; matches last appended event's `globalPosition`.
- **Section 10 — Parameter validation (6):** `readFrom` rejects negative `afterPosition`; `readFrom` rejects zero `maxCount`; `readBySubject` rejects null subject; `readByCorrelation` rejects null correlation ID; `readByType` rejects null event type; `readByTimeRange` rejects `from` after `to`.

### Consumption by Downstream Modules

Downstream modules that depend on these fixtures must declare **both** of the following in their `build.gradle.kts`:

```kotlin
testFixturesImplementation(testFixtures(project(":core:event-model")))
testImplementation(testFixtures(project(":core:event-model")))
```

**IMPORTANT:** Both declarations are required. The `testFixturesImplementation` line provides access from the consuming module's own `testFixtures` source set. The `testImplementation` line provides access from the consuming module's `test` source set. The `java-conventions` plugin adds JUnit and AssertJ only to `testImplementation`, not to `testFixturesImplementation`, so any module that writes contract tests in its own `testFixtures` source set must explicitly re-declare JUnit/AssertJ on `testFixturesImplementation` as well.

### Stale File Note (M1.9)

The stale `InMemoryEventStore` copy at `com.homesynapse.event.InMemoryEventStore` (444 lines, `ReentrantLock`-based) was deleted in M1.9. Only the canonical version at `com.homesynapse.event.test.InMemoryEventStore` (`ReentrantReadWriteLock`-based) exists in the repository. Do not recreate the deleted file — any new work should extend the canonical testFixtures copy, and the production SQLite implementation will live in the `persistence` module.

## Phase 3 Notes

- **`InMemoryEventStore` — IMPLEMENTED (2026-03-27).** Lives in `testFixtures` source set. Implements both `EventPublisher` and `EventStore`. Passes all 27 `EventStoreContractTest` methods. Uses `ReentrantReadWriteLock` for thread safety and `Clock` injection for testable time. Assigns `List.of(EventCategory.SYSTEM)` as a default category for all events — the full `eventType`→category mapping is deferred to the production `SQLiteEventPublisher`.
- **`SqliteEventStore` — IMPLEMENTED (M2.5, 2026-04-10, persistence module).** Single class implements both `EventPublisher` (publish/publishRoot) and `EventStore` (all 6 read methods) for the SQLite backing store. Uses `DatabaseExecutor.writeCoordinator()` for single-writer serialization, `DatabaseExecutor.readExecutor()` with a `ThreadLocal<Connection>` round-robin for concurrent reads, `EventPayloadCodec` for payload JSON, `EventCategoryMapping.categoriesFor(eventType)` for categories derivation at write time, and `TimeConversion` for microsecond-long time encoding. Subclasses `EventStoreContractTest` via `SqliteEventStoreTest` and passes all 27 inherited behavioral tests against a real file-backed SQLite database under `@TempDir`. See `core/persistence/MODULE_CONTEXT.md` §M2.5 Phase 3 Notes for the full implementation description and gotchas.
- **`TestEventTypes` — ADDED (M2.5, 2026-04-10).** New public final utility class in `com.homesynapse.event.test` holding the single constant `TEST_EVENT = "test_event"`. `EventStoreContractTest.TestPayload` is annotated with `@EventType(TestEventTypes.TEST_EVENT)` so that downstream `EventStoreContractTest` subclasses backed by an `EventTypeRegistry` (e.g., `SqliteEventStoreTest`) can register and deserialize the fixture payload through the annotation-driven path.
- **`TestEventFactory` — IMPLEMENTED (2026-03-28).** Lives in `testFixtures` source set (`com.homesynapse.event.test`). Provides static factory methods (`draft()`, `draftFor()`, `envelope()`, `envelopeFor()`) and builders (`DraftBuilder`, `EnvelopeBuilder`) for creating `EventDraft` and `EventEnvelope` instances with sensible defaults. Also provides `SubjectRef` helpers (`subject()`, `deviceSubject()`, etc.) and a public `Payload` record implementing `DomainEvent`. Consumed by downstream modules via `testFixtures(project(":core:event-model"))`.
- **`TestCausalContext` — IMPLEMENTED (2026-03-28).** Lives in `testFixtures` source set (`com.homesynapse.event.test`). Provides `root()`, `rootFor(EventId)`, and `chainFrom(EventEnvelope)` convenience methods for constructing causal contexts in tests. `chainFrom` is the high-value method — reduces 3-line CausalContext.chain() boilerplate to a single call.
- **testFixtures complete.** All three test fixtures (`InMemoryEventStore`, `TestEventFactory`, `TestCausalContext`) are implemented. The `package-info.java` remains as the package documentation.
- **`@EventType` annotation — IMPLEMENTED (M2.1, 2026-04-10).** Runtime-retained annotation (`com.homesynapse.event.EventType`) applied to all 22 core `DomainEvent` payload records. `DegradedEvent` is deliberately unannotated. Covered by `EventTypeAnnotationTest` (7 methods: annotation-presence iteration, DegradedEvent exclusion, value uniqueness, EventTypes-constant matching, count==22, RUNTIME retention, TYPE-only target). The hardcoded `EXPECTED_EVENT_RECORDS` list in that test is the authoritative set of registrable core events — update it whenever a record is added, removed, or renamed. Unblocks M2.2–M2.4 (payload serialization + `EventTypeRegistry`).
- **`EventTypeRegistry` — IMPLEMENTED (M2.4, 2026-04-10, persistence module).** `com.homesynapse.persistence.EventTypeRegistry` is a package-private final class built at startup from an explicit list of `Class<? extends DomainEvent>`. It reads `cls.getAnnotation(EventType.class).value()` per class, validates no duplicates and no unannotated classes, and exposes `classFor(String) → Optional<Class<?>>` and `typeFor(Class<?>) → Optional<String>`. Order is preserved via `LinkedHashMap` for deterministic warmup iteration. `DegradedEvent` is never registered — it carries no `@EventType` annotation, so construction would fail if attempted. The registry is consumed by `EventPayloadCodec.decode()` to resolve the `eventType` discriminator column to a concrete class and by `JacksonWarmup.warmup()` to enumerate types for cache pre-population. Downstream modules register via an explicit list — event-model contributes its 22 records and integration-api contributes its 5 `IntegrationLifecycleEvent` subtypes. See persistence/MODULE_CONTEXT.md for the full M2.4 type inventory.
- **Integration lifecycle events `@EventType` — IMPLEMENTED (M2.i, 2026-04-10).** The 5 `IntegrationLifecycleEvent` subtypes in `com.homesynapse.integration` (integration-api module) now carry `@EventType(EventTypes.INTEGRATION_*)`. Five new constants were added to `EventTypes` in this module (`INTEGRATION_STARTED`, `INTEGRATION_STOPPED`, `INTEGRATION_HEALTH_CHANGED`, `INTEGRATION_RESTARTED`, `INTEGRATION_RESOURCE_EXCEEDED`) taking the total from 41 to 46. `EventTypesTest.exactConstantCount` was updated from 41 to 46 in the same change. The sealed parent `IntegrationLifecycleEvent` is deliberately unannotated — only concrete subtypes are serialized. Coverage lives in `integration-api`'s `IntegrationEventTypeAnnotationTest` (6 methods, mirrors the core `EventTypeAnnotationTest` pattern). M2.4's `EventTypeRegistry` discovers these 5 classes via a second module-level registration call and round-trips them through `EventPayloadCodec` (5 dedicated tests in `EventPayloadCodecTest.IntegrationEvents`).
- **Payload serialization — IMPLEMENTED (M2.4, 2026-04-10, persistence module).** `EventPayloadCodec` in `com.homesynapse.persistence` provides `encode(DomainEvent) → byte[]` and `decode(String eventType, int schemaVersion, byte[] payload) → DomainEvent` using Jackson 2.18.6 isolated inside the persistence module. Polymorphic dispatch is resolved via the explicit `eventType` parameter through `EventTypeRegistry` — Jackson's `@JsonTypeInfo` remains banned by ArchUnit Rule 7. Nullable `actorRef` and other nullable fields serialize as absent keys (SNAKE_CASE naming, `NON_NULL` inclusion, ISO-8601 instants). `DegradedEvent` is the load-bearing fallback on the decode path: unknown event types and parse/validation failures produce a `DegradedEvent` rather than throwing. Encoding a `DegradedEvent` is explicitly rejected (DECIDE-M2-08). Jackson types do not appear in the persistence module's public API — the `byte[]` bridge is the only contract. See persistence/MODULE_CONTEXT.md `EventPayloadCodec` row and Jackson isolation invariant for details.
- **Schema evolution:** `DegradedEvent` is the fallback when upcast fails. Phase 3 must implement upcasters for payload migration between schema versions.
- **Testing strategy:** Unit tests for all record validation (null rejection, defensive copies). Integration tests for EventPublisher/EventStore round-trip through SQLite. Property-based tests for CausalContext chain integrity. Test that actorRef flows correctly from EventDraft through EventPublisher to EventEnvelope. `EventStoreContractTest` (27 methods) already exists and should be used for `SqliteEventStore` validation.
- **Performance targets (from Doc 01 §8):** EventPublisher.publish() must complete within 5ms at p99 including WAL sync. EventStore.readFrom() must handle 10K events/second for catch-up reads.

## Phase 3 Cross-Module Context

*Added 2026-05-17 (Post-M3.1 refresh). Phase 3 active — M3.1 `InProcessEventBus` landed 2026-05-17. Next milestone: M3.5a (StateProjection vertical slice). M3 governance: AMD-41/42/43 APPLIED. See `homesynapse-core-docs/design/HomeSynapse_Core_M3_Implementation_Plan_PLAN-M3-CONSOLIDATED-02.md` for the full M3 implementation plan.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: AMD-33 ratified permanently. Event dispatch uses `@EventType` registry lookup, not sealed pattern matching.
- **D-05** — *`@EventType` on every event record*: All 22+ event records carry `@EventType` annotations. EventTypeRegistry maps strings to classes.
- **AMD-35** — *EventDraft idempotency key*: EventDraft has 9 fields (9th is `idempotencyKey`, nullable, max 128 chars).

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for Phase 3 pattern discoveries (including M3.1 entries on default interface methods, contract test capability hooks, and JPMS-enforced JDBC-free constraints).
