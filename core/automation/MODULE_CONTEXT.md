# automation — `com.homesynapse.automation` — ~52 types — Trigger→Condition→Action rule engine, 4 sealed hierarchies, cascade governance

## Purpose

The Automation Engine is the most cross-cutting subsystem in HomeSynapse after the Event Model. It transforms the platform from passive device monitoring into an active automation platform by implementing the Trigger-Condition-Action (TCA) model. It simultaneously consumes contracts from the Event Bus (as a subscriber), the Device Model (for entity resolution and capability validation), the State Store (for condition evaluation), the Configuration System (for automation definition loading), and the Identity Model (for address resolution via selectors).

This module also claims ownership of two previously-orphaned components: the **Command Dispatch Service** (routing `command_issued` events to integration adapters) and the **Pending Command Ledger** (tracking in-flight commands and correlating them with state confirmations). These close the intent-to-observation loop that is the platform's core reliability differentiator.

This Phase 2 specification defines the public API contracts (sealed hierarchies, records, enums, and service interfaces) that external modules compile against. Implementation code (trigger evaluation, condition checking, action execution, command dispatch routing, pending command correlation, cascade governance, duration timers, REPLAY behavior, subscriber registration, virtual thread management, YAML parsing, identity file management) is Phase 3.

## Design Doc Reference

**Doc 07 — Automation Engine** is the governing design document (Locked + AMD-25 integrated):
- §3.3: Automation definition model — identity stability, slug matching, 30-day retention, concurrency mode, priority
- §3.4: Trigger evaluation — trigger index for O(1) event-type lookup, 5 Tier 1 trigger types, 4 Tier 2 reserved types, `for_duration` (AMD-25) on 4 subtypes
- §3.6: Concurrency modes — SINGLE (default), RESTART (interrupt active), QUEUED/PARALLEL (bounded by maxConcurrent)
- §3.7: Run lifecycle — EVALUATING → RUNNING → terminal (COMPLETED/FAILED/ABORTED/CONDITION_NOT_MET), deduplication by (automation_id, triggering_event_id) (C2), execution order priority-descending then automation_id-ascending (C3)
- §3.7.1: Cascade governance — cascadeDepth tracking, max_cascade_depth config (default 8, range 1–32)
- §3.8: Condition evaluation — boolean guards evaluated against a single StateSnapshot captured at trigger time (AMD-03), 6 Tier 1 + 1 Tier 2 condition types, logical combinators (and/or/not)
- §3.9: Action execution — sequential on virtual thread, 5 Tier 1 + 3 Tier 2 action types, UnavailablePolicy per command action
- §3.11.1: Command Dispatch Service — entity → integration routing, CommandValidator.validate(), command_dispatched DIAGNOSTIC events
- §3.11.2: Pending Command Ledger — command_issued → dispatched → acknowledged → confirmed/timed_out lifecycle, state_confirmed events, coalescing DISABLED
- §3.12: Selectors — 6 types (direct, slug, area, label, type, compound), resolved to Set<EntityId> at trigger time, compound uses intersection semantics (all_of)
- §3.13: Conflict detection — post-execution scan for contradictory commands, DIAGNOSTIC events, no automatic resolution in Tier 1 (D6)
- §4.1: Identity model — AutomationId assigned at first load, preserved across reloads via automations.ids.yaml companion file
- §4.3: Pending command data model — commandEventId, targetRef, expectation, deadline, idempotency, status lifecycle
- §8.1: Service interface specifications — AutomationRegistry, TriggerEvaluator, ConditionEvaluator, ActionExecutor, RunManager, CommandDispatchService, PendingCommandLedger, SelectorResolver, ConflictDetector
- §8.2: Type specifications — all enums, sealed hierarchies, and data records with field-level detail

## JPMS Module

```
module com.homesynapse.automation {
    requires transitive com.homesynapse.platform;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.device;
    requires transitive com.homesynapse.state;

    exports com.homesynapse.automation;
}
```

**Rationale for each `requires transitive`:**
- `com.homesynapse.platform` — `AutomationId`, `EntityId`, `EventId`, `Ulid` from `com.homesynapse.platform.identity` appear in record components and method signatures throughout (AutomationDefinition, RunContext, PendingCommand, DurationTimer, DirectRefSelector, RunManager, CommandDispatchService, SelectorResolver, PendingCommandLedger, ConflictDetector, TriggerEvaluator, AutomationRegistry).
- `com.homesynapse.event` — `EventEnvelope` in TriggerEvaluator.evaluate() and RunManager.initiateRun() parameters; `CommandIdempotency` in PendingCommand record component; `EventId` is also transitively available through this module but explicitly declared via platform.
- `com.homesynapse.device` — `Expectation` (sealed interface from device-model) in PendingCommand record component.
- `com.homesynapse.state` — `StateSnapshot` in ConditionEvaluator.evaluate() parameter; `Availability` in AvailabilityTrigger record component.

**NOT required in Phase 2:**
- `com.homesynapse.event.bus` — EventBus, SubscriptionFilter, CheckpointStore are Phase 3 implementation details (subscriber registration). Not in any public API signature.
- `com.homesynapse.config` — SchemaRegistry, ConfigurationService are Phase 3 implementation details (schema registration, reload callback). Not in any public API signature. The Gradle `api(project(":config:configuration"))` dependency remains for Phase 3 readiness but is NOT declared in module-info.java.

## Package Structure

- **`com.homesynapse.automation`** — All types in a single flat package. Contains: 5 enums (ConcurrencyMode, RunStatus, PendingStatus, UnavailablePolicy, MaxExceededSeverity), 1 typed ULID wrapper (RunId), 4 sealed interfaces (Selector, TriggerDefinition, ConditionDefinition, ActionDefinition), 27 sealed interface subtypes (6 selectors, 9 triggers, 7 conditions, 8 actions — including Tier 2 reserved empty records), 4 data records (AutomationDefinition, RunContext, PendingCommand, DurationTimer), 9 service interfaces (AutomationRegistry, TriggerEvaluator, ConditionEvaluator, ActionExecutor, RunManager, CommandDispatchService, PendingCommandLedger, SelectorResolver, ConflictDetector), and package-info.java.

## Complete Type Inventory

### Enums

| Type | Kind | Purpose | Values |
|---|---|---|---|
| `ConcurrencyMode` | enum (4 values) | Governs behavior when a trigger fires while a previous Run is active (§3.6) | `SINGLE` (default — subsequent triggers dropped), `RESTART` (cancel active via Thread.interrupt()), `QUEUED` (sequential, bounded by maxConcurrent), `PARALLEL` (concurrent, bounded by maxConcurrent) |
| `RunStatus` | enum (7 values) | Lifecycle state of an automation Run (§3.7) | `EVALUATING` (transient — evaluating conditions), `RUNNING` (transient — executing actions), `COMPLETED` (terminal — success), `FAILED` (terminal — error), `ABORTED` (terminal — cancelled by concurrency mode or shutdown), `CONDITION_NOT_MET` (terminal — does NOT consume mode slot), `INTERRUPTED` (terminal — Run did not complete due to external event, e.g. unclean shutdown; produced during REPLAY→LIVE transition per §3.10) |
| `PendingStatus` | enum (5 values) | Lifecycle state of a command tracked by PendingCommandLedger (§4.3) | `DISPATCHED` (initial), `ACKNOWLEDGED` (adapter receipt), `CONFIRMED` (state confirmed), `TIMED_OUT` (deadline expired), `EXPIRED` (NOT_IDEMPOTENT on restart) |
| `UnavailablePolicy` | enum (3 values) | Per-action behavior when targeting an offline entity (§3.9) | `SKIP` (default — skipped outcome), `ERROR` (Run transitions to FAILED), `WARN` (dispatch anyway with DIAGNOSTIC warning) |
| `MaxExceededSeverity` | enum (3 values) | Log severity when a trigger is dropped due to concurrency constraints (§3.3) | `SILENT` (suppressed), `INFO` (default), `WARNING` (elevated) |

### Typed ULID Wrapper

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `RunId` | record (1 field) | Automation-internal Run identifier | Fields: `value` (Ulid, non-null). Follows same pattern as platform-api wrappers (AutomationId, EntityId, etc.). Implements `Comparable<RunId>`. Compact constructor validates non-null. `toString()` and `compareTo()` delegate to Ulid. Unlike AutomationId (shared, in platform-api), RunId is automation-specific. |

### Sealed Hierarchies

#### Selector Hierarchy (6 permits, all Tier 1)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Selector` | sealed interface | Root of selector type hierarchy (§3.12) | Permits: DirectRefSelector, SlugSelector, AreaSelector, LabelSelector, TypeSelector, CompoundSelector. Resolved to `Set<EntityId>` at trigger evaluation time. |
| `DirectRefSelector` | record (1 field) | Entity reference by ULID | Fields: `entityId` (EntityId, non-null). Resolves to exactly one entity. |
| `SlugSelector` | record (1 field) | Human-readable slug reference | Fields: `slug` (String, non-null). Resolves to exactly one entity. |
| `AreaSelector` | record (1 field) | All entities in a named area | Fields: `areaSlug` (String, non-null). |
| `LabelSelector` | record (1 field) | All entities with a label | Fields: `label` (String, non-null). |
| `TypeSelector` | record (1 field) | All entities of a given type | Fields: `entityType` (String, non-null). |
| `CompoundSelector` | record (1 field) | Intersection of multiple selectors (all_of) | Fields: `selectors` (List<Selector>, unmodifiable via List.copyOf()). Resolved sets are intersected per §7.3 deduplication. |

#### TriggerDefinition Hierarchy (5 Tier 1 + 4 Tier 2 reserved)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `TriggerDefinition` | sealed interface | Root of trigger type hierarchy (§3.4) | Permits 9 subtypes. 4 subtypes support `forDuration` (AMD-25). |
| `StateChangeTrigger` | record (5 fields) | Edge-triggered on state transitions | Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `from` (String, nullable — any), `to` (String, nullable — any), `forDuration` (Duration, nullable — AMD-25). At least one of from/to must be non-null (validated at YAML load, not compact constructor). |
| `StateTrigger` | record (4 fields) | Level-triggered on state predicate | Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `value` (String, non-null), `forDuration` (Duration, nullable — AMD-25). |
| `EventTrigger` | record (2 fields) | Fires on specific event type | Fields: `eventType` (String, non-null), `payloadFilters` (Map<String, Object>, unmodifiable via Map.copyOf()). NO forDuration — event triggers are inherently instantaneous (AMD-25 deliberate design decision). |
| `AvailabilityTrigger` | record (3 fields) | Fires on availability_changed | Fields: `selector` (Selector, non-null), `targetAvailability` (Availability, non-null — from com.homesynapse.state), `forDuration` (Duration, nullable — AMD-25). |
| `NumericThresholdTrigger` | record (5 fields) | Fires on numeric threshold crossing | Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `above` (Double, nullable), `below` (Double, nullable), `forDuration` (Duration, nullable — AMD-25). At least one of above/below must be non-null. |
| `TimeTrigger` | record (0 fields) | **Tier 2 reserved** | Requires scheduler integration (Doc 05 §3.8). |
| `SunTrigger` | record (0 fields) | **Tier 2 reserved** | Requires location configuration and solar calculation. |
| `PresenceTrigger` | record (0 fields) | **Tier 2 reserved** | Requires Tier 2 presence infrastructure. |
| `WebhookTrigger` | record (0 fields) | **Tier 2 reserved** | Requires REST API (Doc 09). |

#### ConditionDefinition Hierarchy (6 Tier 1 + 1 Tier 2 reserved)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ConditionDefinition` | sealed interface | Root of condition type hierarchy (§3.8) | Permits 7 subtypes. Conditions check current state, not events. Evaluated against a StateSnapshot captured at trigger time (AMD-03). |
| `StateCondition` | record (3 fields) | Attribute equals value | Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `value` (String, non-null). |
| `NumericCondition` | record (4 fields) | Numeric range check | Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `above` (Double, nullable), `below` (Double, nullable). At least one of above/below must be non-null. |
| `TimeCondition` | record (2 fields) | Current time within window | Fields: `after` (String, nullable — HH:MM format), `before` (String, nullable — HH:MM format). At least one must be non-null. |
| `AndCondition` | record (1 field) | Logical conjunction | Fields: `conditions` (List<ConditionDefinition>, unmodifiable via List.copyOf()). Short-circuits on first false. |
| `OrCondition` | record (1 field) | Logical disjunction | Fields: `conditions` (List<ConditionDefinition>, unmodifiable via List.copyOf()). Short-circuits on first true. |
| `NotCondition` | record (1 field) | Logical negation | Fields: `condition` (ConditionDefinition, non-null). |
| `ZoneCondition` | record (0 fields) | **Tier 2 reserved** | Requires Tier 2 presence infrastructure and zone definition model. |

#### ActionDefinition Hierarchy (5 Tier 1 + 3 Tier 2 reserved)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `ActionDefinition` | sealed interface | Root of action type hierarchy (§3.9) | Permits 8 subtypes. Actions execute sequentially on a Run's virtual thread. |
| `CommandAction` | record (4 fields) | Issue command to target entities | Fields: `target` (Selector, non-null), `commandName` (String, non-null), `parameters` (Map<String, Object>, unmodifiable via Map.copyOf()), `onUnavailable` (UnavailablePolicy, non-null — default SKIP applied at YAML load). Non-blocking — dispatches via Command Pipeline (§3.11). |
| `DelayAction` | record (1 field) | Suspend Run's virtual thread | Fields: `duration` (Duration, non-null). Virtual threads don't consume platform threads during sleep (LTD-01). Cancellation via Thread.interrupt(). |
| `WaitForAction` | record (3 fields) | Block until condition becomes true or timeout | Fields: `condition` (ConditionDefinition, non-null), `timeout` (Duration, non-null), `pollInterval` (Duration, nullable — null means use config default). |
| `ConditionBranchAction` | record (3 fields) | Inline if/then/else branching | Fields: `condition` (ConditionDefinition, non-null), `thenActions` (List<ActionDefinition>, unmodifiable), `elseActions` (List<ActionDefinition>, unmodifiable — may be empty). |
| `EmitEventAction` | record (2 fields) | Produce custom event on event bus | Fields: `eventType` (String, non-null), `payload` (Map<String, Object>, unmodifiable via Map.copyOf()). |
| `ActivateSceneAction` | record (0 fields) | **Tier 2 reserved** | Scene system deferred to Tier 2. |
| `InvokeIntegrationAction` | record (0 fields) | **Tier 2 reserved** | Requires integration operation registry. |
| `ParallelAction` | record (0 fields) | **Tier 2 reserved** | Adds complexity to Run trace model. |

### Data Records

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `AutomationDefinition` | record (12 fields) | Complete parsed automation definition from automations.yaml (§3.3, §4.1) | Fields: `automationId` (AutomationId, non-null), `slug` (String, non-null), `name` (String, non-null), `description` (String, nullable), `enabled` (boolean), `mode` (ConcurrencyMode, non-null), `maxConcurrent` (int — default 10 for QUEUED/PARALLEL, 1 for SINGLE/RESTART), `maxExceededSeverity` (MaxExceededSeverity, non-null), `priority` (int — range -100 to 100), `triggers` (List<TriggerDefinition>, unmodifiable, non-empty), `conditions` (List<ConditionDefinition>, unmodifiable, may be empty), `actions` (List<ActionDefinition>, unmodifiable, non-empty). Identity assigned at first load, preserved across reloads via automations.ids.yaml. |
| `RunContext` | record (8 fields) | Execution context carried on a Run's virtual thread (§8.2) | Fields: `runId` (RunId, non-null), `automationId` (AutomationId, non-null), `triggeringEventId` (EventId, non-null), `matchedTriggers` (List<Integer>, unmodifiable), `resolvedTargets` (Map<String, Set<EntityId>>, unmodifiable — keyed by selector label), `definitionHash` (String, non-null — SHA-256 hex for replay verification), `cascadeDepth` (int — 0 for root Runs), `stateSnapshotPosition` (long — viewPosition from StateSnapshot). |
| `PendingCommand` | record (8 fields) | In-flight command tracking entry (§4.3) | Fields: `commandEventId` (EventId, non-null), `targetRef` (EntityId, non-null), `commandName` (String, non-null), `targetAttribute` (String, non-null), `expectation` (Expectation, non-null — from com.homesynapse.device), `deadline` (Instant, non-null), `idempotency` (CommandIdempotency, non-null — from com.homesynapse.event), `status` (PendingStatus, non-null). |
| `DurationTimer` | record (8 fields) | Active for_duration timer tracking (AMD-25, §8.2) | Fields: `automationId` (AutomationId, non-null), `triggerIndex` (int), `startingEventId` (EventId, non-null), `entityRef` (EntityId, non-null), `forDuration` (Duration, non-null), `startedAt` (Instant, non-null), `expiresAt` (Instant, non-null), `virtualThread` (Thread, non-null). Not persisted — rebuilt from events on REPLAY→LIVE. Keyed by (automationId, triggerIndex), at most one timer per key. |

### Service Interfaces

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `AutomationRegistry` | interface | In-memory registry of automation definitions (§8.1) | `load(List<AutomationDefinition>)`, `get(AutomationId)` → Optional, `getBySlug(String)` → Optional, `getAll()` → List (unmodifiable), `reload(List<AutomationDefinition>)` — hot-reload with in-progress Run preservation (C7). Thread-safe. |
| `TriggerEvaluator` | interface | Evaluates incoming events against trigger index (§3.4, §8.1) | `evaluate(EventEnvelope)` → List<AutomationId>, `cancelDurationTimer(AutomationId, int)`, `activeDurationTimerCount()` → int. Manages duration timers (AMD-25). Thread-safe. |
| `ConditionEvaluator` | interface | Evaluates conditions against state snapshots (§3.8, §8.1) | `evaluate(ConditionDefinition, StateSnapshot)` → boolean. All conditions within a Run use a single snapshot captured at trigger time (AMD-03). Thread-safe. |
| `ActionExecutor` | interface | Executes action sequence on Run's virtual thread (§3.9, §8.1) | `execute(List<ActionDefinition>, RunContext)`. Sequential execution, produces action started/completed events. Thread-safe per-Run. |
| `RunManager` | interface | Manages Run lifecycle and concurrency enforcement (§3.7, §8.1) | `initiateRun(AutomationDefinition, EventEnvelope, List<Integer>, Map<String, Set<EntityId>>, int)` → Optional<RunId>, `getActiveRun(RunId)` → Optional<RunContext>, `getStatus(RunId)` → RunStatus, `activeRunCount()` → int, `activeRunCount(AutomationId)` → int. Deduplication by (automation_id, triggering_event_id) (C2). Thread-safe. |
| `CommandDispatchService` | interface | Routes commands to integration adapters (§3.11.1, §8.1) | `dispatch(EventId, EntityId, String, Map<String, Object>)`. Entity → integration routing via DeviceRegistry. Validates via CommandValidator. Produces command_dispatched DIAGNOSTIC or command_result on failure. Thread-safe. |
| `PendingCommandLedger` | interface | Tracks in-flight commands and state confirmations (§3.11.2, §8.1) | `trackCommand(PendingCommand)`, `getCommand(EventId)` → Optional, `getPendingForEntity(EntityId)` → List, `pendingCount()` → int. Produces state_confirmed and command_confirmation_timed_out events. Coalescing DISABLED (correctness-critical). Thread-safe. |
| `SelectorResolver` | interface | Resolves selectors to entity ID sets (§3.12, §8.1) | `resolve(Selector)` → Set<EntityId>. Direct/slug → 0 or 1 entity. Area/label/type → 0+. Compound → intersection. Thread-safe. |
| `ConflictDetector` | interface | Detects contradictory commands across Runs (§3.13, §8.1) | `scanForConflicts(EventId, List<RunContext>)`. Post-execution only. Both commands execute in Tier 1 (D6). Produces automation_conflict_detected DIAGNOSTIC events. Thread-safe. |

**Total: ~52 public types + 1 module-info.java + 1 package-info.java = ~54 Java files.**

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types appear in record components and method signatures throughout | `AutomationId` (AutomationDefinition, RunContext, DurationTimer, RunManager, AutomationRegistry, TriggerEvaluator, ConflictDetector), `EntityId` (PendingCommand, DurationTimer, RunContext, DirectRefSelector, SelectorResolver, CommandDispatchService, PendingCommandLedger), `EventId` (RunContext, PendingCommand, DurationTimer, CommandDispatchService, PendingCommandLedger, ConflictDetector), `Ulid` (RunId value type) |
| **event-model** (`com.homesynapse.event`) | `requires transitive` — EventEnvelope and CommandIdempotency in public API signatures | `EventEnvelope` (TriggerEvaluator.evaluate(), RunManager.initiateRun() parameters), `CommandIdempotency` (PendingCommand record component) |
| **device-model** (`com.homesynapse.device`) | `requires transitive` — Expectation sealed interface in PendingCommand record component | `Expectation` (PendingCommand.expectation field) |
| **state-store** (`com.homesynapse.state`) | `requires transitive` — StateSnapshot and Availability in public API signatures | `StateSnapshot` (ConditionEvaluator.evaluate() parameter), `Availability` (AvailabilityTrigger.targetAvailability record component) |

### Gradle Dependencies

```kotlin
dependencies {
    api(project(":platform:platform-api"))
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))
    api(project(":config:configuration"))
}
```

All four upstream modules are `api` scope because their types appear in this module's public API signatures. The `config:configuration` dependency remains for Phase 3 readiness — ConfigurationService and SchemaRegistry will be needed for automation schema registration and config access. However, no config types appear in the Phase 2 public API, so `com.homesynapse.config` is NOT declared in module-info.java.

## Consumers

### Planned consumers (from design doc dependency graph):
- **rest-api** — Will use `AutomationRegistry` for automation CRUD endpoints, `RunManager` for Run status/trace endpoints, `PendingCommandLedger` for command status endpoints (Doc 09 §3.2, §7).
- **websocket-api** — Will use `RunManager` for live Run trace streaming.
- **observability** — Will use metrics interfaces from RunManager, TriggerEvaluator, PendingCommandLedger for dashboard metrics (activeRunCount, activeDurationTimerCount, pendingCount).
- **lifecycle** — Will use startup sequencing to ensure automation engine subscribes to event bus after state store is caught up (Doc 12).
- **integration-runtime** — Indirectly connected: CommandDispatchService routes to integration adapters via DeviceRegistry and CommandHandler. No direct compile dependency from automation → integration-api in Phase 2.

## Cross-Module Contracts

- **`AutomationId` is in platform-api, NOT in automation.** It is one of the 8 typed ULID wrappers in `com.homesynapse.platform.identity`. Do NOT create a duplicate. All automation types import it from platform-api.
- **`RunId` IS in automation.** Unlike AutomationId (shared across subsystems), RunId is automation-internal. It follows the same Ulid wrapper pattern (record, Comparable, compact constructor validates non-null).
- **Condition evaluation occurs at trigger time, before mode enforcement.** If conditions fail, the Run completes with `CONDITION_NOT_MET` and does NOT consume a concurrency mode slot. This prevents SINGLE-mode automations from blocking on failed conditions.
- **Three separate subscribers in Phase 3.** The automation engine uses three independent event bus subscribers: `automation_engine` (trigger evaluation), `command_dispatch_service` (command routing), `pending_command_ledger` (command tracking). Each has its own virtual thread and checkpoint. This is a Phase 3 implementation detail, not reflected in Phase 2 interfaces.
- **RunContext.resolvedTargets captures resolved entity sets at trigger time.** Per C4 and Identity Model §7.2, no re-resolution occurs during action execution. The map is keyed by selector label or position identifier.
- **RunContext.cascadeDepth is 0 for user/device-initiated Runs.** Cascade Runs set `cascadeDepth = parent.cascadeDepth + 1`. Maximum governed by `automation.max_cascade_depth` config (default 8, range 1–32). Exceeding max produces `cascade_depth_exceeded` DIAGNOSTIC event.
- **PendingCommand.expectation evaluates `state_reported` events.** The `Expectation.evaluate()` method (from device-model) returns a `ConfirmationResult`. When CONFIRMED, the ledger produces a `state_confirmed` event.
- **DurationTimer.virtualThread is the sleeping virtual thread.** Timer cancellation is via `Thread.interrupt()`. Timer expiry fires the trigger using the startingEventId for deduplication.
- **EventTrigger does NOT support for_duration.** Event triggers are inherently instantaneous — they match a specific event occurrence, not a sustained state. This is a deliberate AMD-25 design decision.
- **CompoundSelector uses intersection semantics (all_of).** Resolved sets from each sub-selector are intersected. Identity Model §7.3 deduplication ensures each entity appears at most once.
- **Conflict detection is post-execution, not pre-execution.** All commands execute. ConflictDetector scans after all Runs triggered by the same event have been initiated. Both contradictory commands reach their targets. Tier 2 may add priority-based suppression.
- **PendingCommandLedger coalescing is DISABLED.** This is correctness-critical per Doc 01 §3.6.
- **Run deduplication: (automation_id, triggering_event_id).** The same event cannot trigger the same automation twice (C2).
- **Run execution order: priority descending, then automation_id ascending (C3).** Deterministic ordering when multiple automations trigger on the same event.
- **No cross-module IntegrationContext update.** Unlike Blocks J and K, this block does not modify IntegrationContext. The automation module communicates with integrations through the Command Dispatch Service calling `CommandHandler.handleCommand()` — but this is a Phase 3 implementation detail.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-01** | Virtual threads for all blocking operations. DelayAction, WaitForAction, and DurationTimer use virtual thread sleep. Each Run executes on its own virtual thread. |

**SQLite operation exception.** "Virtual threads for all blocking operations" is correct for sleep, park, and network I/O but incorrect for SQLite JNI calls. EventStore reads, EventPublisher writes, and checkpoint writes route through the Persistence Layer's platform thread executor (LTD-03). The automation engine's three subscribers (automation_engine, command_dispatch_service, pending_command_ledger) park their virtual threads during database operations. DelayAction and WaitForAction virtual thread sleeps are unaffected — sleeping virtual threads do not pin carriers. DurationTimer wakeup evaluation reads from the State Store's ConcurrentHashMap (no SQLite), but if the timer fires and produces an event, that EventPublisher.publish() call routes through the executor. See Doc 07 §3.2 (Phase C correction) and AMD-25 §5 (implementation note S-12).

| **LTD-04** | Typed ULID wrappers for all identifiers. AutomationId (from platform-api), RunId (new in automation), EntityId, EventId. |
| **LTD-11** | No `synchronized` blocks. All concurrent state uses lock-free patterns or ReentrantLock (Phase 3). |
| **INV-ES-04** | Write-ahead persistence. Events produced by the automation engine (automation_triggered, automation_completed, command_dispatched, state_confirmed, etc.) are durable before subscribers are notified. |
| **Java 21** | Records, sealed interfaces, pattern matching, virtual threads. All types use modern Java idioms. |
| **-Xlint:all -Werror** | Zero warnings, zero unused imports. Enforced by Spotless and convention plugins. |

## Sealed Hierarchies

This module contains four sealed hierarchies — the largest concentration of sealed types in any HomeSynapse module:

1. **Selector** — 6 permits (all Tier 1): DirectRefSelector, SlugSelector, AreaSelector, LabelSelector, TypeSelector, CompoundSelector
2. **TriggerDefinition** — 9 permits (5 Tier 1 + 4 Tier 2): StateChangeTrigger, StateTrigger, EventTrigger, AvailabilityTrigger, NumericThresholdTrigger, TimeTrigger, SunTrigger, PresenceTrigger, WebhookTrigger
3. **ConditionDefinition** — 7 permits (6 Tier 1 + 1 Tier 2): StateCondition, NumericCondition, TimeCondition, AndCondition, OrCondition, NotCondition, ZoneCondition
4. **ActionDefinition** — 8 permits (5 Tier 1 + 3 Tier 2): CommandAction, DelayAction, WaitForAction, ConditionBranchAction, EmitEventAction, ActivateSceneAction, InvokeIntegrationAction, ParallelAction

**Tier 2 reserved types** are empty records (`public record TimeTrigger() implements TriggerDefinition {}`) with minimal Javadoc. They MUST be in the `permits` clause per MVP §10 ("every Tier 1 design must accommodate Tiers 2 and 3 without architectural rework"). They compile and are valid Java 21.

**Cross-hierarchy dependencies:** `WaitForAction` and `ConditionBranchAction` (in ActionDefinition hierarchy) reference `ConditionDefinition` (the other sealed hierarchy). `ConditionBranchAction` also references `List<ActionDefinition>` (recursive). This means ConditionDefinition MUST be defined before ActionDefinition in compilation order.

## Key Design Decisions

1. **AutomationId is NOT duplicated.** It already exists in `com.homesynapse.platform.identity` (created in Block A). The automation module imports it. `RunId` is the only new ULID wrapper in this module.

2. **TriggerDefinition sealed hierarchy includes Tier 2 reserved subtypes.** Doc 07 §8.2 explicitly lists TimeTrigger, SunTrigger, PresenceTrigger, and WebhookTrigger. Same pattern applied to ActionDefinition (3 reserved) and ConditionDefinition (1 reserved).

3. **PendingCommand uses `EventId` (not raw `Ulid`) for commandEventId.** Doc 07 §4.3 shows `ULID commandEventId`, but `EventId` is the correct typed wrapper per LTD-04. Similarly, `EntityId` is used for `targetRef` (the design doc's `EntityRef` maps to `EntityId` in the codebase — `EntityRef` doesn't exist as a type).

4. **DurationTimer record includes a `Thread` field.** Doc 07 §8.2 shows `virtualThread (Thread)`. In Phase 2, this is a type signature only — no code creates DurationTimer instances. `Thread` is `java.lang.Thread` (java.base), no additional requires needed.

5. **`for_duration` fields use `java.time.Duration` and are nullable.** Present on StateChangeTrigger, StateTrigger, NumericThresholdTrigger, AvailabilityTrigger. NOT on EventTrigger (inherently instantaneous). Nullability documented in Javadoc `{@code null}` patterns, not annotation imports.

6. **`config:configuration` is in build.gradle.kts but NOT in module-info.java.** The Gradle dependency stays for Phase 3 readiness. The JPMS module descriptor only declares modules whose types actually appear in public API signatures. Config types don't appear in automation's public API.

7. **Recursive type references in sealed hierarchies are used.** `AndCondition` has `List<ConditionDefinition>`, `CompoundSelector` has `List<Selector>`, `ConditionBranchAction` has `List<ActionDefinition>` and `ConditionDefinition`. All valid Java 21.

8. **Nullable fields are documented via Javadoc, not annotations.** Per project convention, `@Nullable` is documented using `{@code null} if...` patterns in `@param` tags. No external annotation import.

9. **Compact constructors only validate non-null on required fields.** Phase 3 validation handles cross-field constraints (e.g., "at least one of from/to must be non-null" on StateChangeTrigger, "at least one of above/below" on NumericThresholdTrigger/NumericCondition).

10. **All collections in records use defensive copying.** `List.copyOf()`, `Map.copyOf()`, and `Set.copyOf()` in compact constructors. This ensures immutability after construction.

## Gotchas

**GOTCHA: `AutomationId` is in platform-api, NOT in this module.** Do NOT create a duplicate ULID wrapper. Import from `com.homesynapse.platform.identity.AutomationId`. Same for `EntityId`, `EventId`, and `Ulid`.

**GOTCHA: `RunId` IS in this module, NOT in platform-api.** Unlike AutomationId (shared across subsystems), RunId is automation-specific. It follows the same pattern but lives in `com.homesynapse.automation`.

**GOTCHA: `Expectation` is a sealed interface in device-model.** Used as a field type in `PendingCommand`, which requires `requires transitive com.homesynapse.device`. The automation module uses it as a type but does not implement any of its subtypes.

**GOTCHA: `EntityRef` in the design doc maps to `EntityId` in the codebase.** Doc 07 references `EntityRef` for PendingCommand.targetRef. The codebase has no `EntityRef` type — use `EntityId` from platform-api.

**GOTCHA: `EventTrigger` has NO `forDuration` field.** Event triggers are inherently instantaneous (AMD-25). All other non-Tier-2 trigger types support it. Do not add forDuration to EventTrigger.

**GOTCHA: Sealed interface permits clause MUST list ALL subtypes, including Tier 2 reserved.** If a permitted subtype's Java file is missing, the sealed interface won't compile.

**GOTCHA: Empty Tier 2 records need the `implements` clause.** `public record TimeTrigger() implements TriggerDefinition {}` — the empty parentheses and implements clause are required.

**GOTCHA: Cross-hierarchy dependency ordering matters.** ConditionDefinition must be defined before ActionDefinition because WaitForAction and ConditionBranchAction reference ConditionDefinition. The Selector hierarchy has no cross-hierarchy dependencies and can be defined first.

**GOTCHA: `config:configuration` in build.gradle.kts but NOT in module-info.java.** JPMS ignores Gradle dependencies that aren't required in module-info. Config types don't appear in automation's public API. Adding `requires com.homesynapse.config` would work but is incorrect — it would imply the public API depends on config types.

**GOTCHA: `DurationTimer.virtualThread` is `java.lang.Thread`, not a custom type.** No additional `requires` needed in module-info. Thread is in java.base.

**GOTCHA: `Availability` is from state-store, NOT from automation.** `AvailabilityTrigger.targetAvailability` uses `com.homesynapse.state.Availability`. This drives the `requires transitive com.homesynapse.state` declaration.

**GOTCHA: `CommandIdempotency` is in event-model, NOT in device-model.** Despite being command-related, it lives in `com.homesynapse.event`. Used in PendingCommand.idempotency.

**GOTCHA: `package-info.java` was repurposed, not deleted.** The original scaffold was a bare placeholder. It was rewritten with full package-level Javadoc describing the module's purpose and key types.

## Phase 3 Notes

- **Three event bus subscribers needed:** `automation_engine` (trigger evaluation, Run initiation), `command_dispatch_service` (command routing to integration adapters), `pending_command_ledger` (command tracking and state confirmation correlation). Each subscribes independently with its own checkpoint.
- **AutomationRegistry implementation needed:** In-memory map + trigger index (event type → matching automations). Hot-reload with in-progress Run preservation — active Runs complete against their original definition snapshot.
- **TriggerEvaluator implementation needed:** O(1) event-type lookup via trigger index. Duration timer management (AMD-25) — start timers, cancel on state change, fire on expiry. Virtual thread per timer.
- **ConditionEvaluator implementation needed:** Pattern matching on ConditionDefinition subtypes. Recursive evaluation for And/Or/Not. StateSnapshot access for state/numeric conditions. Time checking for TimeCondition.
- **ActionExecutor implementation needed:** Sequential execution on Run's virtual thread. CommandAction → CommandDispatchService. DelayAction → Thread.sleep(). WaitForAction → polling loop. ConditionBranchAction → recursive evaluate + execute. EmitEventAction → EventPublisher.
- **RunManager implementation needed:** Concurrency mode enforcement, cascade depth checking, deduplication map, priority-based ordering, virtual thread spawning per Run. Active Run tracking for status queries.
- **CommandDispatchService implementation needed:** Entity → integration resolution via DeviceRegistry.getIntegrationForEntity(). Command validation via CommandValidator.validate(). Event production (command_dispatched, command_result).
- **PendingCommandLedger implementation needed:** ConcurrentHashMap<EventId, PendingCommand> for pending tracking. Expectation evaluation against state_reported events. Deadline timer management. Event production (state_confirmed, command_confirmation_timed_out).
- **SelectorResolver implementation needed:** Delegation to EntityRegistry for each selector type. Compound intersection logic.
- **ConflictDetector implementation needed:** Post-execution scan of RunContexts for contradictory commands targeting same entity. DIAGNOSTIC event production.
- **Identity file management needed:** `automations.ids.yaml` companion file — maps automation slug → AutomationId ULID. Retention of removed slugs for 30 days (configurable via `automation.identity_retention_days`).
- **REPLAY behavior needed:** Duration timers are not persisted — rebuilt from events on REPLAY→LIVE transition. RunContext.definitionHash enables replay verification.
- **Zombie Run finalization on REPLAY→LIVE (Doc 07 §3.10):** During REPLAY→LIVE transition, scan for Runs that were in EVALUATING or RUNNING state at the time of unclean shutdown (their `automation_triggered` event exists but no `automation_completed` event). For each zombie Run, produce an `automation_completed` event with `final_status: INTERRUPTED` and reason `interrupted_by_crash`. This ensures the Run lifecycle is closed and concurrency mode slots are freed. Test case: start a Run, simulate crash (skip normal shutdown), restart, verify INTERRUPTED completion event is produced.
- **Availability trigger suppression during planned restarts (Doc 07 §3.5, Doc 05 §3.14):** When the automation engine receives an `integration_stopped` event with reason `planned_restart`, add the integration's ID to an in-memory `Set<IntegrationId>` of integrations in planned restart. Suppress `AvailabilityTrigger` evaluation for entities owned by integrations in this set. Remove the integration from the set when `integration_restarted` is received or when the 60s timeout expires. The automation engine learns about planned restarts via events, NOT by reading `IntegrationHealthRecord.plannedRestart()` — JPMS prevents this dependency direction.
- **SelectorResolver must follow slug tombstone chains (Identity Model §7.5).** When resolving a `SlugSelector`, if the slug maps to a tombstone entry, follow the tombstone chain to the current slug and resolve that. Produce an `automation_slug_redirect` DIAGNOSTIC event documenting the redirect. This prevents automations from silently breaking when entities are renamed.
- **Schema registration needed:** Automation schema fragment registered via SchemaRegistry.registerCoreSchema() at startup.
- **Testing strategy:** Unit tests for record construction, field validation, defensive copying. Integration tests for trigger evaluation (event matching, duration timer lifecycle), condition evaluation (all subtypes + combinators), action execution (sequential ordering, branching, delay/wait-for), Run lifecycle (mode enforcement, deduplication, cascade governance), command dispatch (routing, validation), pending command tracking (confirmation, timeout, expiry). Performance targets from Doc 07 §10 should be investigation triggers.
- **`json-schema-validator` still needed in libs.versions.toml for Phase 3** (flagged in Block K, still outstanding).
