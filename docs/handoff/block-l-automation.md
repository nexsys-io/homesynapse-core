# Block L — Automation Engine

**Module:** `core/automation`
**Package:** `com.homesynapse.automation`
**Design Doc:** Doc 07 — Automation Engine (§3, §4, §8) — Locked + AMD-25 integrated
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :core:automation:compileJava`

---

## Strategic Context

The Automation Engine is the most cross-cutting subsystem in HomeSynapse after the Event Model. It transforms the platform from passive device monitoring into an active automation platform by implementing the Trigger-Condition-Action (TCA) model. It simultaneously consumes contracts from the Event Bus (as a subscriber), the Device Model (for entity resolution and capability validation), the State Store (for condition evaluation), the Configuration System (for automation definition loading), and the Identity Model (for address resolution via selectors).

This block also claims ownership of two previously-orphaned components: the **Command Dispatch Service** (routing `command_issued` events to integration adapters) and the **Pending Command Ledger** (tracking in-flight commands and correlating them with state confirmations). These close the intent-to-observation loop that is the platform's core reliability differentiator.

The automation module contains **public API interfaces, sealed hierarchies, records, and enums** consumed by other modules (REST API for automation CRUD, WebSocket API for live run traces, Observability for metrics, Lifecycle for startup sequencing). The **implementation** (trigger evaluation, condition checking, action execution, command dispatch routing, pending command correlation, cascade governance, duration timers, REPLAY behavior) is Phase 3. This block defines the contracts that other modules compile against.

## Scope

**IN:** All public-facing interfaces, sealed type hierarchies, records, and enums from Doc 07 §4 and §8 that external modules consume. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references. `module-info.java` with correct exports and requires. Four sealed hierarchies (TriggerDefinition, ConditionDefinition, ActionDefinition, Selector). Six enums. Multiple data records. Nine service interfaces. AMD-25 types (DurationTimer, for_duration fields on trigger subtypes).

**OUT:** Implementation code. Tests. Trigger evaluation logic. Condition evaluation logic. Action execution logic. Command dispatch routing. Pending command correlation. Cascade governance logic. Duration timer lifecycle management. REPLAY behavior. Subscriber registration. Virtual thread management. YAML parsing. Identity file management (`automations.ids.yaml`). Conflict detection algorithm. Health indicator composite computation.

---

## Locked Decisions

1. **AutomationId already exists in platform-api.** `AutomationId` is one of the 8 typed ULID wrappers in `com.homesynapse.platform.identity` (created in Block A). Do NOT create a duplicate. Import it from `com.homesynapse.platform.identity.AutomationId`. Similarly, `EventId` and `EntityId` already exist there.

2. **RunId is a NEW typed ULID wrapper in the automation module.** Unlike `AutomationId` (which is shared across subsystems), `RunId` is automation-internal. It follows the same pattern as the platform-api ULID wrappers: `public record RunId(Ulid value) implements Comparable<RunId>`. Place it in `com.homesynapse.automation`. The compact constructor validates non-null. `toString()`, `equals()`, `hashCode()`, `compareTo()` follow the `Ulid` delegation pattern.

3. **TriggerDefinition sealed hierarchy includes Tier 2 reserved subtypes.** Doc 07 §8.2 explicitly lists `TimeTrigger`, `SunTrigger`, `PresenceTrigger`, and `WebhookTrigger` as reserved types. These MUST be created as empty record shells in the sealed interface's `permits` clause. This satisfies MVP §10: "every Tier 1 design must accommodate Tiers 2 and 3 without architectural rework." The Tier 2 records have no fields — they are placeholder permits only, with Javadoc noting "Tier 2 — schema defined, implementation deferred."

4. **ActionDefinition sealed hierarchy includes Tier 2 reserved subtypes.** Same pattern: `ActivateSceneAction`, `InvokeIntegrationAction`, `ParallelAction` are empty record shells in the `permits` clause.

5. **ConditionDefinition sealed hierarchy does NOT include Tier 2 `ZoneCondition`.** Unlike triggers and actions, conditions have only one Tier 2 type (`zone`). Include it as an empty record shell for consistency.

6. **Selector sealed hierarchy includes all 6 types.** `DirectRefSelector`, `SlugSelector`, `AreaSelector`, `LabelSelector`, `TypeSelector`, `CompoundSelector`. These are ALL Tier 1 — no reserved types. `CompoundSelector` holds a `List<Selector>` for the `all_of` intersection pattern.

7. **PendingCommand uses `Ulid` (not `EventId`) for commandEventId.** Doc 07 §4.3 shows `ULID commandEventId`. However, the existing event-model has `EventId` as a typed ULID wrapper. Use `EventId` instead — it is the correct typed wrapper for event identifiers per LTD-04. Similarly, use `EntityId` for `targetRef` (not `EntityRef` — that type doesn't exist; the design doc's `EntityRef` maps to `EntityId` in the codebase).

8. **DurationTimer record includes a Thread field.** Doc 07 §8.2 shows `virtualThread` (Thread) as a field. In Phase 2, this is a type signature — the record is declared with `Thread virtualThread` but no implementation creates instances. The Thread type is `java.lang.Thread` (java.base), no additional requires needed.

9. **`for_duration` fields on trigger subtypes use `java.time.Duration`.** The `forDuration` field is nullable (`Duration forDuration` with `@Nullable` documented in Javadoc). Present on `StateChangeTrigger`, `StateTrigger`, `NumericThresholdTrigger`, and `AvailabilityTrigger`. NOT on `EventTrigger` (event triggers are inherently instantaneous — Doc 07 §8.2).

10. **Module requires: five module dependencies, multiple transitive.** The automation module's public API signatures expose types from event-model, device-model, state-store, and platform-api. Each must be analyzed for `requires transitive` vs plain `requires`. See Module Descriptor section for the full analysis.

11. **No cross-module IntegrationContext update.** Unlike Blocks J and K, this block does not modify IntegrationContext. The automation module is a consumer of integration-api types (CommandHandler), not a provider to it. The automation module communicates with integrations through the Command Dispatch Service, which calls `CommandHandler.handleCommand()` — but this is a Phase 3 implementation detail, not a Phase 2 interface.

12. **Existing build.gradle.kts dependencies are incomplete.** The scaffold has `api(project(":core:event-model"))`, `api(project(":core:device-model"))`, `api(project(":core:state-store"))`, `api(project(":config:configuration"))`. Additional dependencies needed: `api(project(":core:event-bus"))` (for SubscriptionFilter, EventBus, CheckpointStore types) and `api(project(":platform:platform-api"))` (for AutomationId, EntityId, EventId, Ulid). The platform-api dependency may be transitively available through event-model, but explicit declaration is cleaner for JPMS.

13. **Existing package-info.java scaffold should be deleted or repurposed.** Per Block K lesson: if bash is unavailable, repurpose with package-level Javadoc rather than leaving a bare scaffold.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Enums (no internal dependencies)

| File | Type | Notes |
|------|------|-------|
| `ConcurrencyMode.java` | enum (4 values) | Doc 07 §3.6, §8.2: `SINGLE`, `RESTART`, `QUEUED`, `PARALLEL`. Javadoc: governs behavior when a trigger fires while a previous Run is still active. `SINGLE` is the default. `RESTART` cancels the active Run. `QUEUED` and `PARALLEL` are bounded by `max_concurrent`. `@see RunManager` |
| `RunStatus.java` | enum (6 values) | Doc 07 §3.7, §8.2: `EVALUATING`, `RUNNING`, `COMPLETED`, `FAILED`, `ABORTED`, `CONDITION_NOT_MET`. Javadoc: terminal states for a Run's lifecycle. `EVALUATING` and `RUNNING` are transient (active Run). `COMPLETED`, `FAILED`, `ABORTED`, `CONDITION_NOT_MET` are terminal. `@see RunManager` |
| `PendingStatus.java` | enum (5 values) | Doc 07 §4.3, §8.2: `DISPATCHED`, `ACKNOWLEDGED`, `CONFIRMED`, `TIMED_OUT`, `EXPIRED`. Javadoc: lifecycle state of a command tracked by the PendingCommandLedger. `DISPATCHED` is the initial state after `command_issued`. `ACKNOWLEDGED` after adapter acknowledgment. `CONFIRMED` after state confirmation. `TIMED_OUT` after deadline expiry. `EXPIRED` for NOT_IDEMPOTENT commands across restart. `@see PendingCommandLedger` |
| `UnavailablePolicy.java` | enum (3 values) | Doc 07 §3.9, §8.2: `SKIP`, `ERROR`, `WARN`. Javadoc: per-action behavior when a command targets an entity whose availability is `offline`. `SKIP` (default) produces `automation_action_completed` with outcome `skipped`. `ERROR` transitions Run to `FAILED`. `WARN` dispatches the command anyway with a DIAGNOSTIC warning. `@see ActionExecutor`, `@see CommandAction` |
| `MaxExceededSeverity.java` | enum (3 values) | Doc 07 §3.3, §8.2: `SILENT`, `INFO`, `WARNING`. Javadoc: log severity when a trigger is dropped due to concurrency mode constraints. Per-automation configuration field `max_exceeded_severity`. `INFO` is the default. `@see AutomationDefinition` |

### Group 2: Sealed Hierarchies — Trigger, Condition, Action, Selector

These are the core type hierarchies. Each sealed interface permits its Tier 1 + Tier 2 subtypes.

#### 2A: Selector Hierarchy (no dependency on other sealed hierarchies)

| File | Type | Notes |
|------|------|-------|
| `Selector.java` | sealed interface | Doc 07 §3.12, §8.2. Root of selector type hierarchy. Permits: `DirectRefSelector`, `SlugSelector`, `AreaSelector`, `LabelSelector`, `TypeSelector`, `CompoundSelector`. Javadoc: selector expressions written by users in automation targets, conditions, and triggers. Resolved to `Set<EntityId>` at trigger evaluation time per Identity Model §7.2. Thread-safe and immutable. `@see SelectorResolver` |
| `DirectRefSelector.java` | record (1 field) | `entityId` (EntityId). Direct entity reference by ULID. Resolves to exactly one entity. |
| `SlugSelector.java` | record (1 field) | `slug` (String, non-null). Human-readable slug reference (e.g., `"kitchen.overhead_light"`). Resolves to exactly one entity via slug lookup. |
| `AreaSelector.java` | record (1 field) | `areaSlug` (String, non-null). Resolves to all entities in the named area. |
| `LabelSelector.java` | record (1 field) | `label` (String, non-null). Resolves to all entities with the label. |
| `TypeSelector.java` | record (1 field) | `entityType` (String, non-null). Resolves to all entities of the given type. |
| `CompoundSelector.java` | record (1 field) | `selectors` (List<Selector>, unmodifiable via `List.copyOf()`). Intersection of resolved sets (§3.12 `all_of` pattern). `@see` Identity Model §7.3 deduplication. |

#### 2B: TriggerDefinition Hierarchy (AMD-25 `forDuration` on 4 subtypes)

| File | Type | Notes |
|------|------|-------|
| `TriggerDefinition.java` | sealed interface | Doc 07 §3.4, §8.2. Root of trigger type hierarchy. Permits 5 Tier 1 + 4 Tier 2. Javadoc: trigger definitions parsed from `automations.yaml`. Each subtype matches a specific event pattern. Types supporting `for_duration` (AMD-25): StateChangeTrigger, StateTrigger, NumericThresholdTrigger, AvailabilityTrigger. `@see TriggerEvaluator` |
| `StateChangeTrigger.java` | record | Edge-triggered: fires on `state_changed` transitions. Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `from` (String, **@Nullable** — null means any), `to` (String, **@Nullable** — null means any), `forDuration` (Duration, **@Nullable** — AMD-25). At least one of `from`/`to` must be non-null (validated at YAML load time, not in compact constructor — Phase 3 validation). |
| `StateTrigger.java` | record | Level-triggered: fires on every `state_changed` where predicate is true. Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `value` (String, non-null), `forDuration` (Duration, **@Nullable** — AMD-25). |
| `EventTrigger.java` | record | Fires on specific event type, optionally filtered by payload. Fields: `eventType` (String, non-null), `payloadFilters` (Map<String, Object>, unmodifiable, possibly empty). NO `forDuration` — event triggers are inherently instantaneous. |
| `AvailabilityTrigger.java` | record | Fires on `availability_changed`. Fields: `selector` (Selector, non-null), `targetAvailability` (Availability, non-null — from `com.homesynapse.state.Availability`), `forDuration` (Duration, **@Nullable** — AMD-25). |
| `NumericThresholdTrigger.java` | record | Fires when numeric attribute crosses threshold. Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `above` (Double, **@Nullable**), `below` (Double, **@Nullable**), `forDuration` (Duration, **@Nullable** — AMD-25). At least one of `above`/`below` must be non-null. |
| `TimeTrigger.java` | record (0 fields) | **Tier 2 reserved.** Javadoc: "Tier 2 — schema defined, implementation deferred. Requires scheduler integration (Doc 05 §3.8 SchedulerService)." |
| `SunTrigger.java` | record (0 fields) | **Tier 2 reserved.** Javadoc: "Tier 2 — schema defined, implementation deferred. Requires location configuration and solar calculation." |
| `PresenceTrigger.java` | record (0 fields) | **Tier 2 reserved.** Javadoc: "Tier 2 — schema defined, implementation deferred. Requires Tier 2 presence infrastructure." |
| `WebhookTrigger.java` | record (0 fields) | **Tier 2 reserved.** Javadoc: "Tier 2 — schema defined, implementation deferred. Requires REST API (Doc 09)." |

#### 2C: ConditionDefinition Hierarchy

| File | Type | Notes |
|------|------|-------|
| `ConditionDefinition.java` | sealed interface | Doc 07 §3.8, §8.2. Root of condition type hierarchy. Permits 6 Tier 1 + 1 Tier 2. Javadoc: boolean guards evaluated after trigger fires, before actions execute. Conditions check current state, not events. Evaluated against a `StateSnapshot` captured at trigger time (AMD-03). `@see ConditionEvaluator` |
| `StateCondition.java` | record | Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `value` (String, non-null). Whether an entity's attribute equals a specified value. |
| `NumericCondition.java` | record | Fields: `selector` (Selector, non-null), `attribute` (String, non-null), `above` (Double, **@Nullable**), `below` (Double, **@Nullable**). At least one of `above`/`below` must be non-null. |
| `TimeCondition.java` | record | Fields: `after` (String, **@Nullable** — HH:MM format), `before` (String, **@Nullable** — HH:MM format). Whether current time falls within a window. At least one must be non-null. |
| `AndCondition.java` | record | Fields: `conditions` (List<ConditionDefinition>, unmodifiable). Logical conjunction. Short-circuits on first false. |
| `OrCondition.java` | record | Fields: `conditions` (List<ConditionDefinition>, unmodifiable). Logical disjunction. Short-circuits on first true. |
| `NotCondition.java` | record | Fields: `condition` (ConditionDefinition, non-null). Logical negation. |
| `ZoneCondition.java` | record (0 fields) | **Tier 2 reserved.** Javadoc: "Tier 2 — schema defined, implementation deferred. Requires Tier 2 presence infrastructure and zone definition model." |

#### 2D: ActionDefinition Hierarchy

| File | Type | Notes |
|------|------|-------|
| `ActionDefinition.java` | sealed interface | Doc 07 §3.9, §8.2. Root of action type hierarchy. Permits 5 Tier 1 + 3 Tier 2. Javadoc: action steps that execute sequentially within a Run's virtual thread. Each step produces events before and after execution. `@see ActionExecutor` |
| `CommandAction.java` | record | Fields: `target` (Selector, non-null), `commandName` (String, non-null), `parameters` (Map<String, Object>, unmodifiable), `onUnavailable` (UnavailablePolicy, non-null — default `SKIP` applied at YAML load time). Issues a command to one or more target entities via the Command Pipeline (§3.11). Non-blocking. |
| `DelayAction.java` | record | Fields: `duration` (Duration, non-null). Suspends the Run's virtual thread. Virtual threads don't consume platform threads during sleep (LTD-01). Cancellation via Thread.interrupt() on `restart` mode. |
| `WaitForAction.java` | record | Fields: `condition` (ConditionDefinition, non-null), `timeout` (Duration, non-null), `pollInterval` (Duration, **@Nullable** — null means use `condition.wait_for_poll_interval_ms` config default). Blocks until condition becomes true or timeout expires. |
| `ConditionBranchAction.java` | record | Fields: `condition` (ConditionDefinition, non-null), `thenActions` (List<ActionDefinition>, unmodifiable), `elseActions` (List<ActionDefinition>, unmodifiable — may be empty). Inline decision branching. |
| `EmitEventAction.java` | record | Fields: `eventType` (String, non-null), `payload` (Map<String, Object>, unmodifiable). Produces a custom event on the event bus. |
| `ActivateSceneAction.java` | record (0 fields) | **Tier 2 reserved.** Javadoc: "Tier 2 — schema defined, implementation deferred. Scene system deferred to Tier 2." |
| `InvokeIntegrationAction.java` | record (0 fields) | **Tier 2 reserved.** Javadoc: "Tier 2 — schema defined, implementation deferred. Requires integration operation registry." |
| `ParallelAction.java` | record (0 fields) | **Tier 2 reserved.** Javadoc: "Tier 2 — schema defined, implementation deferred. Adds complexity to Run trace model." |

### Group 3: Data Records (depends on Groups 1 and 2)

| File | Type | Notes |
|------|------|-------|
| `AutomationDefinition.java` | record | Doc 07 §3.3, §4.1, §8.2. The complete parsed automation definition. Fields: `automationId` (AutomationId, non-null), `slug` (String, non-null), `name` (String, non-null), `description` (String, **@Nullable**), `enabled` (boolean), `mode` (ConcurrencyMode, non-null), `maxConcurrent` (int — default 10 for queued/parallel, 1 for single/restart, applied at YAML load), `maxExceededSeverity` (MaxExceededSeverity, non-null), `priority` (int — range -100 to 100), `triggers` (List<TriggerDefinition>, unmodifiable, non-empty), `conditions` (List<ConditionDefinition>, unmodifiable — may be empty), `actions` (List<ActionDefinition>, unmodifiable, non-empty). Compact constructor: validate non-null on `automationId`, `slug`, `name`, `mode`, `maxExceededSeverity`, `triggers`, `conditions`, `actions`. Make all lists unmodifiable. Javadoc: complete, validated definition loaded from `automations.yaml`. Identity assigned at first load, preserved across reloads via `automations.ids.yaml` companion file. |
| `RunContext.java` | record | Doc 07 §8.2. Mutable context for an active Run (Phase 2 declares it; mutability is Phase 3 implementation). Fields: `runId` (RunId, non-null), `automationId` (AutomationId, non-null), `triggeringEventId` (EventId, non-null), `matchedTriggers` (List<Integer>, unmodifiable — trigger indices), `resolvedTargets` (Map<String, Set<EntityId>>, unmodifiable — keyed by selector label), `definitionHash` (String, non-null — SHA-256 hex), `cascadeDepth` (int), `stateSnapshotPosition` (long — viewPosition from StateSnapshot). Compact constructor validates non-null, makes collections unmodifiable. Javadoc: carried on the Run's virtual thread throughout execution. `definitionHash` enables replay verification (§3.7). `cascadeDepth` governs cascade governance (§3.7.1). |
| `PendingCommand.java` | record | Doc 07 §4.3, §8.2. Fields: `commandEventId` (EventId, non-null), `targetRef` (EntityId, non-null), `commandName` (String, non-null), `targetAttribute` (String, non-null), `expectation` (Expectation, non-null — from `com.homesynapse.device`), `deadline` (Instant, non-null), `idempotency` (CommandIdempotency, non-null — from `com.homesynapse.event`), `status` (PendingStatus, non-null). Compact constructor validates all non-null. Javadoc: in-flight command tracking entry in the PendingCommandLedger. The expectation is evaluated against incoming `state_reported` events. |
| `DurationTimer.java` | record | Doc 07 §8.2, AMD-25. Fields: `automationId` (AutomationId, non-null), `triggerIndex` (int), `startingEventId` (EventId, non-null), `entityRef` (EntityId, non-null), `forDuration` (Duration, non-null), `startedAt` (Instant, non-null), `expiresAt` (Instant, non-null), `virtualThread` (Thread, non-null). Compact constructor validates non-null except `virtualThread` which is set at construction. Javadoc: tracks an active `for_duration` timer. Not persisted — rebuilt from events on REPLAY→LIVE transition. Managed by TriggerEvaluator. Keyed by `(automationId, triggerIndex)`. `@see TriggerEvaluator` |

### Group 4: Service Interfaces

| File | Type | Notes |
|------|------|-------|
| `AutomationRegistry.java` | interface | Doc 07 §8.1. Manages automation definitions. Methods: `void load(List<AutomationDefinition> definitions)` — load validated definitions from Configuration System; `Optional<AutomationDefinition> get(AutomationId id)` — lookup by ID; `Optional<AutomationDefinition> getBySlug(String slug)` — lookup by slug; `List<AutomationDefinition> getAll()` — return all loaded definitions (unmodifiable); `void reload(List<AutomationDefinition> definitions)` — hot-reload with in-progress Run preservation (C7). Javadoc: in-memory registry populated from `automations.yaml`. Maintains trigger index (event type → matching automations). Identity managed via `automations.ids.yaml` companion file. Thread-safe. `@see ConfigurationService`, `@see SchemaRegistry` |
| `TriggerEvaluator.java` | interface | Doc 07 §3.4, §8.1. Methods: `List<AutomationId> evaluate(EventEnvelope event)` — evaluate a single event against the trigger index and return matching automation IDs; `void cancelDurationTimer(AutomationId automationId, int triggerIndex)` — cancel a duration timer (AMD-25); `int activeDurationTimerCount()` — current active timer count (AMD-25). Javadoc: evaluates incoming events against registered trigger definitions. Maintains the trigger index for O(1) event-type-to-automation lookup. Manages duration timers for `for_duration` triggers (AMD-25). Thread-safe. `@see AutomationRegistry`, `@see DurationTimer` |
| `ConditionEvaluator.java` | interface | Doc 07 §3.8, §8.1. Methods: `boolean evaluate(ConditionDefinition condition, StateSnapshot snapshot)` — evaluate a single condition against the provided state snapshot. Javadoc: evaluates condition predicates against current state. All conditions within a Run evaluate against a single `StateSnapshot` captured at trigger time (AMD-03). Thread-safe. `@see StateSnapshot`, `@see StateQueryService` |
| `ActionExecutor.java` | interface | Doc 07 §3.9, §8.1. Methods: `void execute(List<ActionDefinition> actions, RunContext context)` — execute action sequence within the Run's virtual thread. Javadoc: executes action steps sequentially. Each step produces `automation_action_started` and `automation_action_completed` events. Command actions route through the Command Pipeline (§3.11). Delay actions suspend the virtual thread. Thread-safe per-Run (each Run executes on its own virtual thread). `@see CommandDispatchService`, `@see RunManager` |
| `RunManager.java` | interface | Doc 07 §3.7, §8.1. Methods: `Optional<RunId> initiateRun(AutomationDefinition automation, EventEnvelope triggeringEvent, List<Integer> matchedTriggers, Map<String, Set<EntityId>> resolvedTargets, int cascadeDepth)` — initiate a Run after mode enforcement; returns empty if mode rejects; `Optional<RunContext> getActiveRun(RunId runId)` — get active Run context; `RunStatus getStatus(RunId runId)` — get Run's current or terminal status; `int activeRunCount()` — total active Runs across all automations; `int activeRunCount(AutomationId automationId)` — active Runs for a specific automation. Javadoc: manages Run lifecycle, concurrency mode enforcement (§3.6), and cascade governance (§3.7.1). Deduplication by `(automation_id, triggering_event_id)` (C2). Execution order: priority descending, then automation_id ascending (C3). Thread-safe. `@see ConcurrencyMode`, `@see RunStatus`, `@see RunContext` |
| `CommandDispatchService.java` | interface | Doc 07 §3.11.1, §8.1. Methods: `void dispatch(EventId commandEventId, EntityId targetRef, String commandName, Map<String, Object> parameters)` — route command to the correct integration adapter. Javadoc: thin routing resolver. Resolves entity → integration via `DeviceRegistry.getIntegrationForEntity()`. Validates command via `CommandValidator.validate()`. Produces `command_dispatched` DIAGNOSTIC event on successful handoff. Produces `command_result` with status `invalid` or `unroutable` on failure. Runs as a separate subscriber (`command_dispatch_service`) on its own virtual thread. Thread-safe. `@see PendingCommandLedger`, `@see DeviceRegistry`, `@see CommandValidator` |
| `PendingCommandLedger.java` | interface | Doc 07 §3.11.2, §8.1. Methods: `void trackCommand(PendingCommand command)` — add a command to the pending map; `Optional<PendingCommand> getCommand(EventId commandEventId)` — lookup by command event ID; `List<PendingCommand> getPendingForEntity(EntityId entityRef)` — all pending commands for an entity; `int pendingCount()` — total pending commands. Javadoc: tracks in-flight commands and correlates them with state confirmations. Subscribes to `command_issued`, `command_result`, `state_reported`, `state_confirmed` events. Produces `state_confirmed` when expected outcome matches incoming `state_reported`. Produces `command_confirmation_timed_out` when deadline expires. Coalescing DISABLED (correctness-critical per Doc 01 §3.6). Thread-safe. `@see PendingCommand`, `@see Expectation`, `@see CommandDispatchService` |
| `SelectorResolver.java` | interface | Doc 07 §3.12, §8.1. Methods: `Set<EntityId> resolve(Selector selector)` — resolve a selector expression to a set of entity IDs using current registry state. Javadoc: resolution uses Identity Model primitives. Direct and slug selectors resolve to exactly one entity (empty set if not found). Area, label, type selectors may resolve to zero or more. Compound selectors use intersection (§7.3 deduplication). Thread-safe. `@see Selector`, `@see EntityRegistry` |
| `ConflictDetector.java` | interface | Doc 07 §3.13, §8.1. Methods: `void scanForConflicts(EventId triggeringEventId, List<RunContext> triggeredRuns)` — scan Runs triggered by the same event for contradictory commands targeting the same entity. Javadoc: produces `automation_conflict_detected` DIAGNOSTIC events when contradictory commands are detected. No automatic resolution in Tier 1 — both commands execute (D6). Priority-based suppression deferred to Tier 2. Thread-safe. `@see RunManager` |

### Group 5: Module Descriptor + Build Configuration

| File | Notes |
|------|-------|
| `module-info.java` | See "Module Descriptor" section below for full analysis. |
| `build.gradle.kts` | Add missing dependencies: `api(project(":core:event-bus"))`, `api(project(":platform:platform-api"))`. See "Build Configuration" section below. |

---

## Module Descriptor — JPMS Analysis

The automation module's public API exposes types from multiple upstream modules. The Block K JPMS lesson (expanded: `requires transitive` needed for superclass types, throws clause types, record component types, method parameter types, method return types) must be applied rigorously.

**Types from other modules appearing in public API signatures:**

| Upstream Module | Types Used in Public API | How Used | Transitive? |
|---|---|---|---|
| `com.homesynapse.platform` | `AutomationId`, `EntityId`, `EventId`, `Ulid` | Record fields (AutomationDefinition, RunContext, PendingCommand, DurationTimer, DirectRefSelector), method params/returns (RunManager, CommandDispatchService, SelectorResolver, etc.) | **YES — `requires transitive`** |
| `com.homesynapse.event` | `EventEnvelope`, `CommandIdempotency`, `EventPriority` | Method params (TriggerEvaluator.evaluate, RunManager.initiateRun), record fields (PendingCommand) | **YES — `requires transitive`** |
| `com.homesynapse.device` | `Expectation`, `CommandIdempotency` (no — this is in event-model) | Record field (PendingCommand.expectation uses `Expectation` from device-model) | **YES — `requires transitive`** |
| `com.homesynapse.state` | `StateSnapshot`, `Availability` | Method param (ConditionEvaluator.evaluate), record field (AvailabilityTrigger.targetAvailability) | **YES — `requires transitive`** |
| `com.homesynapse.event.bus` | `SubscriptionFilter`, `EventBus`, `CheckpointStore` | NOT in public API — these are Phase 3 implementation details (subscriber registration). | **NO — `requires` only** |
| `com.homesynapse.config` | `SchemaRegistry`, `ConfigurationService` | NOT in public API — these are Phase 3 implementation details (schema registration, reload callback). | **NO — not required at all in Phase 2** |

**Resulting module-info.java:**

```java
module com.homesynapse.automation {
    requires transitive com.homesynapse.platform;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.device;
    requires transitive com.homesynapse.state;

    exports com.homesynapse.automation;
}
```

**Rationale for each:**
- `requires transitive com.homesynapse.platform` — AutomationId, EntityId, EventId, Ulid appear in record components and method signatures everywhere.
- `requires transitive com.homesynapse.event` — EventEnvelope in TriggerEvaluator.evaluate(), CommandIdempotency in PendingCommand, EventPriority may appear in Javadoc. Note: event-model already `requires transitive com.homesynapse.platform`, so platform is transitively available via event-model. However, declaring it explicitly is correct for JPMS clarity.
- `requires transitive com.homesynapse.device` — Expectation (from device-model) in PendingCommand record component.
- `requires transitive com.homesynapse.state` — StateSnapshot in ConditionEvaluator.evaluate() parameter, Availability in AvailabilityTrigger record component.

**Note:** `com.homesynapse.event.bus` and `com.homesynapse.config` are NOT required in the Phase 2 module descriptor. The automation module's Phase 2 interfaces do not reference EventBus, SubscriptionFilter, CheckpointStore, SchemaRegistry, or ConfigurationService in their public API. Those are Phase 3 implementation dependencies that will be added when implementation code is written.

**WAIT — verify the build.gradle.kts:** The build.gradle.kts needs dependencies that match the module-info. The scaffold currently has event-model, device-model, state-store, and configuration. We need to:
- Add `api(project(":platform:platform-api"))` — for direct access to identity types
- Remove `api(project(":config:configuration"))` — not needed in Phase 2 (no config types in public API). HOWEVER, leaving it doesn't hurt compilation and the module WILL need it in Phase 3. **Decision: leave it for Phase 3 readiness, but do NOT add `requires com.homesynapse.config` to module-info.java — JPMS will ignore Gradle dependencies that aren't required.**
- Do NOT add event-bus dependency — not needed in Phase 2.

**Updated build.gradle.kts:**

```kotlin
plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Automation engine: trigger-condition-action rules, cascade governor"

dependencies {
    api(project(":platform:platform-api"))
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))
    api(project(":config:configuration"))
}
```

---

## File Placement

All automation types go in: `core/automation/src/main/java/com/homesynapse/automation/`
Module info: `core/automation/src/main/java/module-info.java` (create new)

Delete or repurpose the existing `package-info.java` scaffold at `core/automation/src/main/java/com/homesynapse/automation/package-info.java`.

---

## Cross-Module Type Dependencies

The automation module imports types from four existing modules:

**From `com.homesynapse.platform.identity` (platform-api):**
- `AutomationId` — used in AutomationDefinition, RunContext, DurationTimer, RunManager, AutomationRegistry, TriggerEvaluator, ConflictDetector
- `EntityId` — used in PendingCommand, DurationTimer, RunContext, DirectRefSelector, SelectorResolver, CommandDispatchService, PendingCommandLedger
- `EventId` — used in RunContext, PendingCommand, DurationTimer, CommandDispatchService, PendingCommandLedger, ConflictDetector
- `Ulid` — used in RunId value type

**From `com.homesynapse.event` (event-model):**
- `EventEnvelope` — parameter to TriggerEvaluator.evaluate() and RunManager.initiateRun()
- `CommandIdempotency` — field in PendingCommand record
- (Javadoc references: EventPublisher, EventStore, CausalContext, EventPriority, ProcessingMode)

**From `com.homesynapse.device` (device-model):**
- `Expectation` — sealed interface, field in PendingCommand record

**From `com.homesynapse.state` (state-store):**
- `StateSnapshot` — parameter to ConditionEvaluator.evaluate()
- `Availability` — field in AvailabilityTrigger record

**Exported to (downstream consumers):**
- `com.homesynapse.rest` (rest-api) — AutomationRegistry, RunManager, PendingCommandLedger for CRUD/trace/status endpoints (Doc 09 §3.2, §7)
- `com.homesynapse.websocket` (websocket-api) — RunManager for live run trace streaming
- `com.homesynapse.observability` (observability) — metrics interfaces
- `com.homesynapse.lifecycle` (lifecycle) — startup sequencing (Doc 12)

---

## Javadoc Standards

Per Sprint 1–2 cumulative lessons:
1. Every `@param` documents nullability (`never {@code null}` or `{@code null} if...`)
2. Every type has `@see` cross-references to related types
3. Thread-safety explicitly stated on all interfaces
4. Class-level Javadoc explains the "why" — what role this type plays in HomeSynapse
5. Reference Doc 07 sections in class-level Javadoc
6. Sealed interface Javadoc should list permits clause and explain the hierarchy's purpose
7. Tier 2 reserved types have minimal Javadoc: "Tier 2 — schema defined, implementation deferred. [reason]."
8. TriggerDefinition subtypes with `forDuration` should document: minimum PT1S, maximum per `automation.trigger.max_for_duration_ms` config, ISO 8601 PT format only (no P1D)
9. RunContext Javadoc should document `definitionHash` as SHA-256 hex for replay verification
10. PendingCommand Javadoc should document the full lifecycle (§3.11.2): issued → dispatched → acknowledged → confirmed/timed_out
11. AutomationDefinition Javadoc should document: identity stability across reloads (§4.1), 30-day retention for removed automations, `triggers` and `actions` are non-empty
12. DurationTimer Javadoc should document: not persisted, rebuilt from events on REPLAY→LIVE, tracking key is `(automationId, triggerIndex)`, at most one timer per key

---

## Key Design Details for Javadoc Accuracy

1. **AutomationId is assigned at first load and preserved across reloads.** The `automations.ids.yaml` companion file maps automation names to ULIDs. On reload, automations are matched by name. Removed names are retained for 30 days (configurable via `automation.identity_retention_days`).

2. **RunContext.resolvedTargets captures the resolved entity sets for ALL selectors at trigger time.** Per C4 and Identity Model §7.2, no re-resolution occurs during action execution. The map is keyed by selector label or position identifier.

3. **RunContext.cascadeDepth is 0 for user/device-initiated Runs.** For cascade Runs, `cascade_depth = parent_run.cascade_depth + 1`. Maximum governed by `automation.max_cascade_depth` (default 8, range 1-32). Exceeding the max produces `cascade_depth_exceeded` DIAGNOSTIC event.

4. **Condition evaluation at trigger time, before mode enforcement.** If conditions fail, Run completes with `CONDITION_NOT_MET` status and does NOT consume a mode slot. This prevents `single`-mode automations from blocking on failed conditions.

5. **Three separate subscribers in Phase 3.** The automation engine uses three independent subscribers: `automation_engine` (trigger evaluation), `command_dispatch_service` (command routing), `pending_command_ledger` (command tracking). Each has its own virtual thread and checkpoint. This is a Phase 3 implementation detail, not a Phase 2 interface.

6. **PendingCommand.expectation evaluates `state_reported` events.** The `Expectation.evaluate()` method (from device-model) returns a `ConfirmationResult`. When `CONFIRMED`, the ledger produces a `state_confirmed` event.

7. **DurationTimer.virtualThread is the sleeping virtual thread.** Timer cancellation is via `Thread.interrupt()`. Timer expiry fires the trigger using the `startingEventId` as the triggering event for deduplication purposes.

8. **EventTrigger does NOT support for_duration.** Event triggers are inherently instantaneous — they match a specific event occurrence, not a sustained state. This is a deliberate design decision from AMD-25.

9. **CompoundSelector uses intersection semantics (all_of).** The resolved sets from each sub-selector are intersected. Identity Model §7.3 deduplication ensures each entity appears at most once.

10. **Conflict detection is post-execution, not pre-execution.** All commands execute. The ConflictDetector scans after all Runs triggered by the same event have been initiated. Both contradictory commands reach their targets. Tier 2 may add priority-based suppression.

---

## Constraints

1. **Java 21** — use records, sealed interfaces, enums as appropriate
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies in public API** — only types from existing HomeSynapse modules and Java standard library
5. **Javadoc on every public type, method, and constructor**
6. **All types go in `com.homesynapse.automation` package** within core/automation module
7. **Do NOT create implementations** — interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files** except `build.gradle.kts` in core/automation (adding platform-api dependency)
10. **Collections in records must be unmodifiable** — use `Map.copyOf()`, `List.copyOf()`, `Set.copyOf()` in compact constructors
11. **`requires transitive` must be verified for every upstream module whose types appear in public API signatures** — apply the expanded JPMS rule from Block K (record components, method params, method returns, exception superclasses, throws clause types)
12. **Tier 2 reserved types are empty records** — no fields, minimal Javadoc, but they MUST be in the `permits` clause

---

## Compile Gate

```bash
./gradlew :core:automation:compileJava
```

Must pass with `-Xlint:all -Werror`. Then run full project gate:

```bash
./gradlew compileJava
```

All modules must still compile (no regressions from module-info changes).

**Common pitfalls:**
- `AutomationId`, `EntityId`, `EventId` are in `com.homesynapse.platform.identity` — NOT in `com.homesynapse.automation`. Do not create duplicates.
- `Expectation` is a sealed interface in `com.homesynapse.device` — the automation module uses it as a field type in `PendingCommand`, which requires `requires transitive com.homesynapse.device`.
- `StateSnapshot` is in `com.homesynapse.state` — used as a parameter in `ConditionEvaluator.evaluate()`, requiring `requires transitive com.homesynapse.state`.
- `Availability` is an enum in `com.homesynapse.state` — used as a field in `AvailabilityTrigger`, same module.
- `EventEnvelope` is in `com.homesynapse.event` — used as a parameter in `TriggerEvaluator.evaluate()`, requiring `requires transitive com.homesynapse.event`.
- `CommandIdempotency` is in `com.homesynapse.event` — used as a field in `PendingCommand`.
- `java.time.Duration` is in java.base — no additional requires needed.
- `java.lang.Thread` is in java.base — no additional requires needed.
- The `config:configuration` dependency in build.gradle.kts stays for Phase 3 readiness but is NOT in the module-info.java `requires` clause — config types don't appear in public API signatures.
- `@Nullable` documentation: use Javadoc `{@code null}` patterns, not annotation imports.
- Empty Tier 2 records compile fine — `public record TimeTrigger() implements TriggerDefinition {}` is valid Java 21.

---

## Execution Order

1. Create `ConcurrencyMode.java`
2. Create `RunStatus.java`
3. Create `PendingStatus.java`
4. Create `UnavailablePolicy.java`
5. Create `MaxExceededSeverity.java`
6. Create `RunId.java`
7. Create `Selector.java` (sealed interface)
8. Create `DirectRefSelector.java`
9. Create `SlugSelector.java`
10. Create `AreaSelector.java`
11. Create `LabelSelector.java`
12. Create `TypeSelector.java`
13. Create `CompoundSelector.java`
14. Create `TriggerDefinition.java` (sealed interface)
15. Create `StateChangeTrigger.java`
16. Create `StateTrigger.java`
17. Create `EventTrigger.java`
18. Create `AvailabilityTrigger.java`
19. Create `NumericThresholdTrigger.java`
20. Create `TimeTrigger.java` (Tier 2 reserved)
21. Create `SunTrigger.java` (Tier 2 reserved)
22. Create `PresenceTrigger.java` (Tier 2 reserved)
23. Create `WebhookTrigger.java` (Tier 2 reserved)
24. Create `ConditionDefinition.java` (sealed interface)
25. Create `StateCondition.java`
26. Create `NumericCondition.java`
27. Create `TimeCondition.java`
28. Create `AndCondition.java`
29. Create `OrCondition.java`
30. Create `NotCondition.java`
31. Create `ZoneCondition.java` (Tier 2 reserved)
32. Create `ActionDefinition.java` (sealed interface)
33. Create `CommandAction.java`
34. Create `DelayAction.java`
35. Create `WaitForAction.java`
36. Create `ConditionBranchAction.java`
37. Create `EmitEventAction.java`
38. Create `ActivateSceneAction.java` (Tier 2 reserved)
39. Create `InvokeIntegrationAction.java` (Tier 2 reserved)
40. Create `ParallelAction.java` (Tier 2 reserved)
41. Create `AutomationDefinition.java`
42. Create `RunContext.java`
43. Create `PendingCommand.java`
44. Create `DurationTimer.java`
45. Create `AutomationRegistry.java`
46. Create `TriggerEvaluator.java`
47. Create `ConditionEvaluator.java`
48. Create `ActionExecutor.java`
49. Create `RunManager.java`
50. Create `CommandDispatchService.java`
51. Create `PendingCommandLedger.java`
52. Create `SelectorResolver.java`
53. Create `ConflictDetector.java`
54. Create `module-info.java`
55. Update `build.gradle.kts` — add `api(project(":platform:platform-api"))`
56. Delete/repurpose `package-info.java` scaffold
57. Compile gate: `./gradlew :core:automation:compileJava`
58. Full compile gate: `./gradlew compileJava`

---

## Summary of New Files

| File | Module | Kind | Key Details |
|------|--------|------|-------------|
| `ConcurrencyMode.java` | core/automation | enum (4 values) | SINGLE, RESTART, QUEUED, PARALLEL |
| `RunStatus.java` | core/automation | enum (6 values) | EVALUATING, RUNNING, COMPLETED, FAILED, ABORTED, CONDITION_NOT_MET |
| `PendingStatus.java` | core/automation | enum (5 values) | DISPATCHED, ACKNOWLEDGED, CONFIRMED, TIMED_OUT, EXPIRED |
| `UnavailablePolicy.java` | core/automation | enum (3 values) | SKIP, ERROR, WARN |
| `MaxExceededSeverity.java` | core/automation | enum (3 values) | SILENT, INFO, WARNING |
| `RunId.java` | core/automation | record (1 field) | Typed ULID wrapper |
| `Selector.java` | core/automation | sealed interface | 6 permits (all Tier 1) |
| `DirectRefSelector.java` | core/automation | record (1 field) | entityId (EntityId) |
| `SlugSelector.java` | core/automation | record (1 field) | slug (String) |
| `AreaSelector.java` | core/automation | record (1 field) | areaSlug (String) |
| `LabelSelector.java` | core/automation | record (1 field) | label (String) |
| `TypeSelector.java` | core/automation | record (1 field) | entityType (String) |
| `CompoundSelector.java` | core/automation | record (1 field) | selectors (List<Selector>) |
| `TriggerDefinition.java` | core/automation | sealed interface | 5 Tier 1 + 4 Tier 2 permits |
| `StateChangeTrigger.java` | core/automation | record (5 fields) | selector, attribute, from, to, forDuration |
| `StateTrigger.java` | core/automation | record (4 fields) | selector, attribute, value, forDuration |
| `EventTrigger.java` | core/automation | record (2 fields) | eventType, payloadFilters |
| `AvailabilityTrigger.java` | core/automation | record (3 fields) | selector, targetAvailability, forDuration |
| `NumericThresholdTrigger.java` | core/automation | record (5 fields) | selector, attribute, above, below, forDuration |
| `TimeTrigger.java` | core/automation | record (0 fields) | Tier 2 reserved |
| `SunTrigger.java` | core/automation | record (0 fields) | Tier 2 reserved |
| `PresenceTrigger.java` | core/automation | record (0 fields) | Tier 2 reserved |
| `WebhookTrigger.java` | core/automation | record (0 fields) | Tier 2 reserved |
| `ConditionDefinition.java` | core/automation | sealed interface | 6 Tier 1 + 1 Tier 2 permits |
| `StateCondition.java` | core/automation | record (3 fields) | selector, attribute, value |
| `NumericCondition.java` | core/automation | record (4 fields) | selector, attribute, above, below |
| `TimeCondition.java` | core/automation | record (2 fields) | after, before |
| `AndCondition.java` | core/automation | record (1 field) | conditions (List<ConditionDefinition>) |
| `OrCondition.java` | core/automation | record (1 field) | conditions (List<ConditionDefinition>) |
| `NotCondition.java` | core/automation | record (1 field) | condition (ConditionDefinition) |
| `ZoneCondition.java` | core/automation | record (0 fields) | Tier 2 reserved |
| `ActionDefinition.java` | core/automation | sealed interface | 5 Tier 1 + 3 Tier 2 permits |
| `CommandAction.java` | core/automation | record (4 fields) | target, commandName, parameters, onUnavailable |
| `DelayAction.java` | core/automation | record (1 field) | duration (Duration) |
| `WaitForAction.java` | core/automation | record (3 fields) | condition, timeout, pollInterval |
| `ConditionBranchAction.java` | core/automation | record (3 fields) | condition, thenActions, elseActions |
| `EmitEventAction.java` | core/automation | record (2 fields) | eventType, payload |
| `ActivateSceneAction.java` | core/automation | record (0 fields) | Tier 2 reserved |
| `InvokeIntegrationAction.java` | core/automation | record (0 fields) | Tier 2 reserved |
| `ParallelAction.java` | core/automation | record (0 fields) | Tier 2 reserved |
| `AutomationDefinition.java` | core/automation | record (12 fields) | Complete parsed automation definition |
| `RunContext.java` | core/automation | record (8 fields) | Active Run execution context |
| `PendingCommand.java` | core/automation | record (8 fields) | In-flight command tracking |
| `DurationTimer.java` | core/automation | record (8 fields) | AMD-25 duration timer tracking |
| `AutomationRegistry.java` | core/automation | interface | load(), get(), getBySlug(), getAll(), reload() |
| `TriggerEvaluator.java` | core/automation | interface | evaluate(), cancelDurationTimer(), activeDurationTimerCount() |
| `ConditionEvaluator.java` | core/automation | interface | evaluate() |
| `ActionExecutor.java` | core/automation | interface | execute() |
| `RunManager.java` | core/automation | interface | initiateRun(), getActiveRun(), getStatus(), activeRunCount() ×2 |
| `CommandDispatchService.java` | core/automation | interface | dispatch() |
| `PendingCommandLedger.java` | core/automation | interface | trackCommand(), getCommand(), getPendingForEntity(), pendingCount() |
| `SelectorResolver.java` | core/automation | interface | resolve() |
| `ConflictDetector.java` | core/automation | interface | scanForConflicts() |
| `module-info.java` | core/automation | module descriptor | 4 × requires transitive, exports com.homesynapse.automation |

**Modified files (1):**

| File | Module | Change |
|------|--------|--------|
| `build.gradle.kts` | core/automation | Add `api(project(":platform:platform-api"))` |

**Deleted/repurposed files (1):**

| File | Module | Reason |
|------|--------|--------|
| `package-info.java` | core/automation | Scaffold placeholder — delete or repurpose with package-level Javadoc |

**Total: ~53 new files + 1 modified + 1 deleted/repurposed = ~55 file operations.**

---

## Estimated Size

~53 types + module-info, approximately 2000–2800 lines. This is the **largest Phase 2 block by a significant margin** — nearly 2.5× Block K (24 files) and close to Block G (57 files). The primary complexity comes from:
1. Four sealed hierarchies with multiple subtypes (10 triggers, 7 conditions, 8 actions, 6 selectors = 31 subtypes)
2. Each subtype needs correct fields, compact constructors, and design-doc-accurate Javadoc
3. Multiple upstream module dependencies requiring careful JPMS `requires transitive` analysis
4. AMD-25 integration across trigger types, DurationTimer, and TriggerEvaluator

Expect 3.5–4.5 hours. Consider splitting into two execution passes if needed: pass 1 (enums + sealed hierarchies + RunId), pass 2 (data records + interfaces + module descriptor + build config).

---

## Gotchas

1. **AutomationId is in platform-api, not automation.** Do NOT create a duplicate ULID wrapper. Import from `com.homesynapse.platform.identity.AutomationId`.

2. **RunId IS in automation.** Unlike AutomationId, RunId is automation-specific and not shared. Create it as a new type following the same Ulid wrapper pattern.

3. **Sealed interface permits clause must list ALL subtypes.** Including Tier 2 reserved types. If a permitted subtype doesn't compile (because it's missing), the sealed interface won't compile.

4. **Empty Tier 2 records need the `implements` clause.** `public record TimeTrigger() implements TriggerDefinition {}` — the empty parentheses and implements clause are required.

5. **`config:configuration` is in build.gradle.kts but NOT in module-info.java.** The Gradle dependency stays for Phase 3 readiness. The JPMS module descriptor only declares modules whose types are actually used. Config types don't appear in automation's public API.

6. **Recursive type references in sealed hierarchies.** `AndCondition` has `List<ConditionDefinition>` — a recursive reference to the parent sealed interface. This is fine in Java 21. Similarly, `CompoundSelector` has `List<Selector>`.

7. **`ConditionBranchAction` references `ConditionDefinition` (cross-hierarchy).** An action type that contains a condition and action lists. This creates a dependency: ActionDefinition hierarchy depends on ConditionDefinition hierarchy. Create ConditionDefinition BEFORE ActionDefinition.

8. **`WaitForAction` also references `ConditionDefinition`.** Same cross-hierarchy dependency. Reinforces: conditions before actions in execution order.

9. **`Availability` enum is from state-store, not automation.** `AvailabilityTrigger` uses `com.homesynapse.state.Availability` as a field type. This requires `requires transitive com.homesynapse.state`.

10. **`EventEnvelope` is a 14-field record.** It's used as a method parameter, not imported for its fields. The automation module just passes it through.

11. **DurationTimer has `Thread virtualThread` — this is unusual for a Phase 2 record.** It's a type specification, not implementation. The record signature includes Thread because it IS part of the type's contract. In Phase 2, no code creates DurationTimer instances.

12. **build.gradle.kts already exists with 4 dependencies.** Only ONE dependency needs to be added: `api(project(":platform:platform-api"))`. Do not duplicate the existing four.

---

## Notes

- This is the last core subsystem Phase 2 block before Sprint 3 moves to integration-runtime, API, and observability modules.
- After Block L, the next items on the critical path are: integration-api MODULE_CONTEXT.md population, automation MODULE_CONTEXT.md population, and then Blocks M/N (REST/WebSocket API).
- AMD-25 is fully integrated into Doc 07. All `for_duration` types and behaviors are specified in the design doc. The Coder should not need to refer to the AMD-25 amendment separately.
- Doc 07 is the most complex design document in the project (1070 lines). The Coder should read it section by section as needed, not attempt to absorb it all at once. The handoff distills the Phase 2-relevant parts.
- The automation module will have the most downstream consumers of any module — REST API, WebSocket API, Observability, and Lifecycle all depend on its interfaces. Getting the Phase 2 interfaces right is critical.
- `json-schema-validator` (flagged in Block K) still needs to be added to `libs.versions.toml` for Phase 3. Not blocking for Block L.

---

## Context Delta (post-completion)

_To be filled in by the Coder after execution._

**Files created:**
_List all files created_

**Files modified:**
_List all files modified_

**Decisions made during execution:**
_Document any deviations from the handoff, with rationale_

**What the next block needs to know:**
_Cross-block context for future consumers_
