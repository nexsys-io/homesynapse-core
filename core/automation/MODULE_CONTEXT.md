# automation — `com.homesynapse.automation` — ~68 public types (+ package-private impl/value helpers incl. the M7.2b `ComputedValue` seam) — Trigger→Condition→Action rule engine, 4 sealed hierarchies, cascade governance

> **M7.1 status (trigger/condition path — DONE; M7.2/M7.3 pending).** The first production code landed: the four M7.1 service impls (`StandardAutomationRegistry`, `StandardTriggerEvaluator` incl. AMD-25 duration timers, `StandardConditionEvaluator`, `StandardSelectorResolver`), the `AutomationDefinitionLoader` (+`LoadResult`/`LoadFailure`) with §6.1 fail-closed validation (SD-9), the `AutomationIdentityStore`/`InMemoryAutomationIdentityStore` identity seam, and the package-private wiring seams `AutomationEngineSubscriber` + `AutomationConfigBridge`. AMD-88 took `TriggerDefinition` 9→12 permits (+`CalendarTrigger`/`ReachabilityTrigger`/`ManualTrigger`, `WebhookTrigger` promoted, `triggerId` on every Tier-1) and AMD-89 took `Selector` 6→7 (+`SemanticTagSelector`, `includedRoles` on the group permits) + new enums `MatchMode`/`CalendarEventTransition`. **Composition-root wiring of the `automation_engine` subscriber is RESOLVED (AB-3, 2026-06-19).** `HomeSynapseCore.start()` now assembles `ConfigurationService` (via `ConfigurationServiceFactory`) + the production `InMemory{Entity,Device,Area}Registry` impls, registers the automation core-schema in Phase 1 (before `config.load()`), loads definitions via `loader.load(rawMap().get("automation"))` (the section content — NOT the whole rawMap; the loader reads top-level `automations`), and subscribes `automation_engine` **after the state projection reaches LIVE** (catch-up ordering invariant). A new **public assembly seam `AutomationEngineAssembly.automationEngineSubscriber(StandardTriggerEvaluator) → event.bus.Subscriber`** exposes the still-package-private `AutomationEngineSubscriber` to the composition root. Returning `event.bus.Subscriber` on the exported API required bumping module-info to **`requires transitive com.homesynapse.event.bus`** (+ build.gradle `api(":core:event-bus")`) — the §authoring api↔requires-transitive lockstep (the instruction's "module-info UNCHANGED" was incorrect). `StandardConditionEvaluator` is NOT yet wired into the subscribe path (the subscriber wraps only the trigger evaluator; conditions are M7.2). `RunContext` is UNTOUCHED (the AMD-91 swap is M7.2).

> **M7.2a-2 status (execution/dispatch half — DONE; deferred build gate).** The differentiator engine now *acts*. New production impls (the run-lifecycle FSM consumes the real seams): **`StandardActionExecutor`** (public; the 5 Tier-1 actions sequential on the Run VT, row 5/6 emission, §6.2 fail-fast, `UnavailablePolicy`, returns the `ActionExecutionResult` tally); **`StandardCommandDispatchService`** (public; resolve→validate→emit `command_dispatched`/`command_result`); **`StandardCommandValidator`** (public) behind the NEW **`CommandValidator`** interface (+ nested `ValidationResult`); **`StandardConflictDetector`** (public; row 9, report-only); **`StandardRunConditionGate`** (public; `ConditionEvaluator` over the trigger-time snapshot, publishes row 4). New public record **`ActionExecutionResult(int actionCount, int commandCount, String failureReason)`** (DP-D tally channel) and package-private **`EmittedDomainEvent`** (the EmitEventAction custom-event payload). **Interface widenings:** `ActionExecutor.execute` now returns `ActionExecutionResult` and takes the triggering `EventEnvelope` (causal stamping of rows 5/6, AMD-92 §2.4); `RunConditionGate.conditionsHold` widened to `(AutomationDefinition, RunContext, EventEnvelope, StateSnapshot)` so the FSM captures ONE trigger-time snapshot, records its `viewPosition` in `RunContext`, and hands it + the envelope to the gate (single snapshot, correct row-4 causal context). `RunManagerAssembly.runManager(...)` gained a `StateQueryService` param. **`StandardRunManager` DP-A carries:** (1) **QUEUED is a real sequential single-flight drain** — at `maxConcurrent` a QUEUED Run *enqueues* (per-automation FIFO) and drains one-at-a-time as slots free (PARALLEL still drops `queue_full`); (2) row 4 carries the triggering event's CausalContext; (3) **dedup claim moved BEFORE the gate** (a raced duplicate cannot double-publish row 4); (4) real `stateSnapshotPosition` + real `actionCount`/`commandCount` on `automation_completed`. **module-info UNCHANGED** (the widened seams expose only `EventEnvelope`/`StateSnapshot`, already `requires transitive`). The composition-root wiring of the executor/dispatch subscribers into `start()` rides app-bootstrap (out of scope; the assembly seams are the handoff). **NOTE — instruction said `DeviceRegistry.getIntegrationForEntity()`; that method does not exist** — dispatch resolves entity→integration via `EntityRegistry.findEntity(...).deviceId()` → `DeviceRegistry.findDevice(...).integrationId()`. Deferred build gate: `./gradlew check` not run in-session.

> **M7.2b status (action-model FREEZE — DONE; deferred build gate).** Three things land now that the engine acts. (1) **A minimal computed-param resolution seam at run-init** (Doc 16 §3.2): a **package-private** `ComputedValue` sealed type (`permits LiteralValue, AttributeRef, AggregateValue`) + `ComputedValueContext(StateSnapshot snapshot, Instant resolutionTime)` + `AggregateOp {SUM,AVG,MIN,MAX,COUNT}` + the `ComputedValues` resolver helper. `StandardRunManager.initiateRun` resolves any `ComputedValue` occupying a `CommandAction.parameters()` value position against the **captured trigger-time snapshot** (AMD-03 — the SAME snapshot the gate uses, never a fresh read), **before** the FROZEN `ActionExecutor` — the executor (and `CommandDispatchService`/`CommandValidator`) never see a `ComputedValue`. **All six new types are package-private** so `module-info` stays UNCHANGED: a *public* `ComputedValue.resolve(): com.homesynapse.value.AttributeValue` on the exported package would trip `-Xlint:exports`/`-Werror` unless `requires com.homesynapse.value` were promoted to `transitive` (the instruction forbids that module-info change). They have no external consumer in V1 (loading is OUT; the executor never sees them), and same-package tests retain access. Resolution is total + side-effect-free (C-SA-2): **no I/O capability on the type**, deterministic over the single snapshot + injected time (INV-TO-02); absent entity/attribute → a typed-absent sentinel (`DegradedAttributeValue` via `ComputedValues.absent`), non-numeric/absent aggregate member → skip, empty fold → SUM/COUNT identities (`0.0`/`0`) and AVG/MIN/MAX → sentinel. **[REVIEW] `AggregateValue` carries a resolved `Set<EntityId>`, not a live `Selector`** (deviation from the instruction's literal `AggregateValue(Selector,…)`): a finite typed set keeps `ComputedValueContext` at exactly {snapshot, time} and resolution a pure fold with no resolver collaborator (a resolver = an I/O capability, which C-SA-2 forbids "on the type"); selectors already resolve to sets at trigger time (C4 `resolvedTargets`). (2) **The run-coupled fail-closed-read coupling** (Doc 16 §3.4 / C-SA-5; run-coupled half of app-bootstrap A3): a trigger-time `stateQuery.getSnapshot()` that throws (post-AB-2 the read path can throw) is caught at exactly that call site → the Run terminates **`FAILED`** with a `failureReason` (`"trigger-time state snapshot read failed closed: …"`) + the explainable `automation_triggered`/`automation_completed(FAILED, failureReason)` pair (Contract C1); no condition eval, no action execution, dedup-claimed so no autonomous retry (AMD-90-INV-01). (`StandardRunManager.failClosedRead`; the failed Run's `RunContext.stateSnapshotPosition` is the `-1` sentinel.) (3) **No-engine-retry frozen** (AMD-90-INV-01 / D2-REC-162): the action layer dispatches exactly once. **No new event type — counts stay 71/41/53; `module-info` UNCHANGED.** YAML/loader parsing of computed values + the component model + computed *conditions* are OUT (the seam is **test-exercised only** in V1, via programmatically-constructed `ComputedValue`s). Deferred build gate: `./gradlew check` not run in-session.

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
    requires com.homesynapse.value;

    // M7.1: the automation_engine bus subscriber imports event-bus (a legal
    // core->core edge); org.slf4j for engine-internal logging. Plain requires /
    // Gradle implementation scope (no such type on the exported API).
    requires com.homesynapse.event.bus;
    requires org.slf4j;

    exports com.homesynapse.automation;
}
```

**M7.1 update — FIX-07 PARTIALLY REVERSED (`event.bus` re-added; `config` NOT):** `requires com.homesynapse.event.bus` + `requires org.slf4j` were re-added when the Phase-3 implementations landed. **`requires com.homesynapse.config` was NOT re-added** — the gate's `assertAllowedModuleDependencies` task bans the `core:automation -> config:configuration` edge at EVERY scope (the "Core depends only on platform + other core" layer rule), which is a STRICTER gate than the exported-API `requires transitive`↔`api` §authoring check. So the FIX-07 config half is wrong (an instruction defect). The `automations.yaml` schema registration + definition-document load ride the composition root (`lifecycle`/`app`, allowed to depend on both core and config) at app-bootstrap; the config-free schema fragment lives in `AutomationSchema` (a plain-`String` constant holder near the automation module). The `AutomationDefinitionLoader` is config-agnostic (`load(Map<String,Object>)`). The `event.bus` half of FIX-07 was fine (`core->core`, used by `AutomationEngineSubscriber`).

**Rationale for each `requires transitive`:**
- `com.homesynapse.platform` — `AutomationId`, `EntityId`, `EventId`, `Ulid` from `com.homesynapse.platform.identity` appear in record components and method signatures throughout (AutomationDefinition, RunContext, PendingCommand, DurationTimer, DirectRefSelector, RunManager, CommandDispatchService, SelectorResolver, PendingCommandLedger, ConflictDetector, TriggerEvaluator, AutomationRegistry).
- `com.homesynapse.event` — `EventEnvelope` in TriggerEvaluator.evaluate() and RunManager.initiateRun() parameters; `CommandIdempotency` in PendingCommand record component; `EventId` is also transitively available through this module but explicitly declared via platform.
- `com.homesynapse.device` — `Expectation` (sealed interface from device-model) in PendingCommand record component.
- `com.homesynapse.state` — `StateSnapshot` in ConditionEvaluator.evaluate() parameter; `Availability` in AvailabilityTrigger record component.

**Plain `requires` (non-transitive), added M4.0b-4a:**
- `com.homesynapse.value` — `PendingCommand`'s Javadoc references `com.homesynapse.value.AttributeValue` (via `{@link Expectation#evaluate(AttributeValue)}`). No value type is on automation's public API in code, so the edge is plain (non-transitive). The type is also reachable transitively through `requires transitive com.homesynapse.device`; the edge is declared explicitly at its use site per the AttributeValue relocation design note. Gradle scope `implementation`.

**NOT required in Phase 2:**
- `com.homesynapse.event.bus` — EventBus, SubscriptionFilter, CheckpointStore are Phase 3 implementation details (subscriber registration). Not in any public API signature.
- `com.homesynapse.config` — SchemaRegistry, ConfigurationService are Phase 3 implementation details (schema registration, reload callback). Not in any public API signature. Configuration dependency removed pre-Phase 3 (FIX-07). Will be re-added when automation implementation imports configuration types.

## Package Structure

- **`com.homesynapse.automation`** — All types in a single flat package. Contains: 5 enums (ConcurrencyMode, RunStatus, PendingStatus, UnavailablePolicy, MaxExceededSeverity), 1 typed ULID wrapper (RunId), 4 sealed interfaces (Selector, TriggerDefinition, ConditionDefinition, ActionDefinition), 30 sealed interface subtypes (6 selectors, 9 triggers, 7 conditions, 8 actions — including Tier 2 reserved empty records; count corrected 2026-06-12, re-derived 6+9+7+8 from source — the prior "27" was a stale copied-forward count), 4 data records (AutomationDefinition, RunContext, PendingCommand, DurationTimer), 9 service interfaces (AutomationRegistry, TriggerEvaluator, ConditionEvaluator, ActionExecutor, RunManager, CommandDispatchService, PendingCommandLedger, SelectorResolver, ConflictDetector), and package-info.java.

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

#### Selector Hierarchy (7 permits, all Tier 1 — AMD-89)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Selector` | sealed interface | Root of selector type hierarchy (§3.12) | Permits: DirectRefSelector, SlugSelector, AreaSelector, LabelSelector, TypeSelector, **SemanticTagSelector (AMD-89)**, CompoundSelector. Resolved to `Set<EntityId>` at trigger evaluation time. The group-resolving permits (Area/Label/Type/SemanticTag) carry a non-null `Set<EntityRole> includedRoles` (PRIMARY-only default at YAML load); Direct/Slug are never role-filtered (AMD-89-INV-01). New enum `MatchMode { EXACT, NAMESPACE_PREFIX }`. |
| `DirectRefSelector` | record (1 field) | Entity reference by ULID | Fields: `entityId` (EntityId, non-null). Resolves to exactly one entity. |
| `SlugSelector` | record (1 field) | Human-readable slug reference | Fields: `slug` (String, non-null). Resolves to exactly one entity. |
| `AreaSelector` | record (1 field) | All entities in a named area | Fields: `areaSlug` (String, non-null). |
| `LabelSelector` | record (1 field) | All entities with a label | Fields: `label` (String, non-null). |
| `TypeSelector` | record (1 field) | All entities of a given type | Fields: `entityType` (String, non-null). |
| `CompoundSelector` | record (1 field) | Intersection of multiple selectors (all_of) | Fields: `selectors` (List<Selector>, unmodifiable via List.copyOf()). Resolved sets are intersected per §7.3 deduplication. |

#### TriggerDefinition Hierarchy (9 Tier 1 + 3 Tier 2 reserved — AMD-88)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `TriggerDefinition` | sealed interface | Root of trigger type hierarchy (§3.4) | Permits 12 subtypes (AMD-88: +`CalendarTrigger`/`ReachabilityTrigger`/`ManualTrigger`; `WebhookTrigger` promoted Tier-2→Tier-1 with fields; `PresenceTrigger` stays empty = M8.1). 5 subtypes support `forDuration` (AMD-25): StateChange/State/NumericThreshold/Availability/**Reachability**. Every Tier-1 permit carries a `String triggerId` (AMD-88 §2.5) — user-supplied or load-time ULID, stable across reloads; engine keying stays positional `(automationId, triggerIndex)`. New enum `CalendarEventTransition { EVENT_START, EVENT_END }`. |
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
| `RunContext` | record (8 fields) | Execution context carried on a Run's virtual thread (§8.2) | Fields: `runId` (RunId, non-null), `automationId` (AutomationId, non-null), `triggeringEventId` (EventId, non-null), `matchedTriggers` (List<Integer>, unmodifiable), `resolvedTargets` (Map<String, Set<EntityId>>, unmodifiable — keyed by selector label), `definitionHash` (String, non-null — SHA-256 hex for replay verification), `causalChain` (RunCausalChain, non-null — AMD-91; depth via `causalChain.depth()`, cycle via `containsAutomation`; **swapped from the AMD-04 `int cascadeDepth` in M7.2a-1**), `stateSnapshotPosition` (long — viewPosition from StateSnapshot). |
| `RunCausalChain` | record (1 field) + nested `ChainLink` | The immutable per-Run causal lineage (AMD-91 §2.1) — the authority for cascade depth + cycle suppression; the type the explainability projection reads (Doc 16 §3.3). | `ancestors` (List<ChainLink>, unmodifiable). `root()` → empty/depth 0; `extend(ChainLink)` → new chain; `depth()` (derived = ancestors.size()); `containsAutomation(AutomationId)` (cycle test). Nested `ChainLink(RunId runId, AutomationId automationId)`. **Automation-resident — NEVER in an event payload** (AMD-91-INV-02 / AMD-92-INV-01); crosses the boundary only flattened (int cascadeDepth, List<AutomationId> chain). |
| `RunManagerConfig` | record (3 fields) | Plain automation-resident config for the FSM (Doc 07 §9; `core→config` banned, so NOT a ConfigurationService). | `maxCascadeDepth` (int, 1–32, default 8), `autoDisableThreshold` (int, default 5), `autoDisableWindow` (Duration, default 10m). `defaults()` factory. The composition root reads YAML and constructs this. |
| `PendingCommand` | record (8 fields) | In-flight command tracking entry (§4.3) | Fields: `commandEventId` (EventId, non-null), `targetRef` (EntityId, non-null), `commandName` (String, non-null), `targetAttribute` (String, non-null), `expectation` (Expectation, non-null — from com.homesynapse.device), `deadline` (Instant, non-null), `idempotency` (CommandIdempotency, non-null — from com.homesynapse.event), `status` (PendingStatus, non-null). |
| `DurationTimer` | record (8 fields) | Active for_duration timer tracking (AMD-25, §8.2) | Fields: `automationId` (AutomationId, non-null), `triggerIndex` (int), `startingEventId` (EventId, non-null), `entityRef` (EntityId, non-null), `forDuration` (Duration, non-null), `startedAt` (Instant, non-null), `expiresAt` (Instant, non-null), `virtualThread` (Thread, non-null). Not persisted — rebuilt from events on REPLAY→LIVE. Keyed by (automationId, triggerIndex), at most one timer per key. |

### Service Interfaces

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `AutomationRegistry` | interface | In-memory registry of automation definitions (§8.1) | `load(List<AutomationDefinition>)`, `get(AutomationId)` → Optional, `getBySlug(String)` → Optional, `getAll()` → List (unmodifiable), `reload(List<AutomationDefinition>)` — hot-reload with in-progress Run preservation (C7). Thread-safe. |
| `TriggerEvaluator` | interface | Evaluates incoming events against trigger index (§3.4, §8.1) | `evaluate(EventEnvelope)` → List<AutomationId>, `cancelDurationTimer(AutomationId, int)`, `activeDurationTimerCount()` → int. Manages duration timers (AMD-25). Thread-safe. |
| `ConditionEvaluator` | interface | Evaluates conditions against state snapshots (§3.8, §8.1) | `evaluate(ConditionDefinition, StateSnapshot)` → boolean. All conditions within a Run use a single snapshot captured at trigger time (AMD-03). Thread-safe. |
| `ActionExecutor` | interface | Executes action sequence on Run's virtual thread (§3.9, §8.1) | `execute(List<ActionDefinition>, RunContext)`. Sequential execution, produces action started/completed events. Thread-safe per-Run. **Impl is M7.2a-2** (M7.2a-1's FSM drives an injected/stub executor). |
| `RunConditionGate` | interface (functional) | The EVALUATING-state decision seam (M7.2a-1) — conditions BEFORE mode enforcement (§3.6). | `conditionsHold(AutomationDefinition, RunContext)` → boolean. M7.2a-2 wires the real `ConditionEvaluator` + trigger-time `StateSnapshot` + the `automation_condition_evaluated` (row 4) diagnostic behind it, with zero FSM rework. |
| `RunManager` | interface | Manages Run lifecycle and concurrency enforcement (§3.7, §8.1) | `initiateRun(AutomationDefinition, EventEnvelope, List<Integer>, Map<String, Set<EntityId>>, RunCausalChain parentChain)` → Optional<RunId> (**last param swapped from `int cascadeDepth` in M7.2a-1**), `getActiveRun(RunId)` → Optional<RunContext>, `getStatus(RunId)` → RunStatus, `activeRunCount()` → int, `activeRunCount(AutomationId)` → int, `finalizeZombieRuns(List<ZombieRun>)` → int (**added M7.2a-1** — REPLAY→LIVE zombie finalization, §3.10). Nested `record ZombieRun(Ulid runId, AutomationId automationId, Ulid correlationId, Ulid causationId, Ulid actorRef, Instant eventTime)`. Deduplication by (automation_id, triggering_event_id) (C2). Thread-safe. **Impl `StandardRunManager` (package-private) landed M7.2a-1; exposed via `RunManagerAssembly.runManager(...)` → RunManager.** |
| `CommandDispatchService` | interface | Routes commands to integration adapters (§3.11.1, §8.1) | `dispatch(EventId, EntityId, String, Map<String, Object>)`. Entity → integration routing via DeviceRegistry. Validates via CommandValidator. Produces command_dispatched DIAGNOSTIC or command_result on failure. Thread-safe. |
| `PendingCommandLedger` | interface | Tracks in-flight commands and state confirmations (§3.11.2, §8.1) | `trackCommand(PendingCommand)`, `getCommand(EventId)` → Optional, `getPendingForEntity(EntityId)` → List, `pendingCount()` → int. Produces state_confirmed and command_confirmation_timed_out events. Coalescing DISABLED (correctness-critical). Thread-safe. |
| `SelectorResolver` | interface | Resolves selectors to entity ID sets (§3.12, §8.1) | `resolve(Selector)` → Set<EntityId>. Direct/slug → 0 or 1 entity. Area/label/type → 0+. Compound → intersection. Thread-safe. |
| `ConflictDetector` | interface | Detects contradictory commands across Runs (§3.13, §8.1) | `scanForConflicts(EventId, List<RunContext>)`. Post-execution only. Both commands execute in Tier 1 (D6). Produces automation_conflict_detected DIAGNOSTIC events. Thread-safe. |

**Total: 53 public types (5 enums + 1 wrapper + 4 sealed roots + 30 permits + 4 records + 9 interfaces) + package-info.java = 54 files in the package, + module-info.java (counts re-derived 2026-06-12 with the 30-permit correction; the 54-file source-tree count matches the 2026-05-22 v3 inspection).**

**M7.2a-1 additions (run lifecycle):** +`RunCausalChain` (record, + nested `ChainLink`), +`RunManagerConfig` (record), +`RunConditionGate` (functional interface), +`RunManagerAssembly` (public seam) — 4 new public types — plus the package-private impl `StandardRunManager` (`implements RunManager, AutoCloseable`). `RunContext` field 7 swapped (`int cascadeDepth` → `RunCausalChain causalChain`); `RunManager` gained `finalizeZombieRuns(...)` + nested `ZombieRun` and swapped `initiateRun`'s last param. **module-info UNCHANGED** (all new types ride existing edges: platform, event, event.bus, java.base). Still pending (M7.2a-2): `ActionExecutor` impl + action events (rows 5/6), real condition evaluation behind `RunConditionGate` + row 4 + `EvaluatedEntityState`, `CommandDispatchService`/`CommandValidator`, `ConflictDetector` + row 9 + `ConflictEntry`, run-trace.

**M7.2b additions (computed-param seam — all PACKAGE-PRIVATE):** +`ComputedValue` (sealed interface, `permits LiteralValue, AttributeRef, AggregateValue`; single method `AttributeValue resolve(ComputedValueContext)`), +`LiteralValue` (record `(AttributeValue value)`), +`AttributeRef` (record `(EntityId entity, String attribute)`), +`AggregateValue` (record `(Set<EntityId> entities, String attribute, AggregateOp op)`), +`AggregateOp` (enum `{SUM, AVG, MIN, MAX, COUNT}`), +`ComputedValueContext` (record `(StateSnapshot snapshot, Instant resolutionTime)`), +`ComputedValues` (utility: `resolveActions(List<ActionDefinition>, ComputedValueContext)` + the `absent(String)` typed-absent sentinel factory) — **6 new package-private types + 1 helper; ZERO new public types** (so the public-type count and `module-info` are both unchanged). `StandardRunManager` gained: a `try/catch` around the trigger-time `getSnapshot()` (fail-closed → `failClosedRead`), a resolution step `(5a)` that builds `resolvedActions` via `ComputedValues.resolveActions(...)`, a `resolvedActions` field on the private `ActiveRun` (the VT executes it instead of `automation.actions()`), and two constants (`FAILED_READ_SNAPSHOT_POSITION = -1`, `DEGRADED_READ_REASON`). The `ActionExecutor` seam is UNCHANGED (resolution runs before it). **module-info UNCHANGED** (all new types ride the existing plain `requires com.homesynapse.value` + `requires transitive com.homesynapse.state` edges; package-private keeps value-model off the exported API).

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **platform-api** (`com.homesynapse.platform`) | `requires transitive` — Identity types appear in record components and method signatures throughout | `AutomationId` (AutomationDefinition, RunContext, DurationTimer, RunManager, AutomationRegistry, TriggerEvaluator, ConflictDetector), `EntityId` (PendingCommand, DurationTimer, RunContext, DirectRefSelector, SelectorResolver, CommandDispatchService, PendingCommandLedger), `EventId` (RunContext, PendingCommand, DurationTimer, CommandDispatchService, PendingCommandLedger, ConflictDetector), `Ulid` (RunId value type) |
| **event-model** (`com.homesynapse.event`) | `requires transitive` — EventEnvelope and CommandIdempotency in public API signatures | `EventEnvelope` (TriggerEvaluator.evaluate(), RunManager.initiateRun() parameters), `CommandIdempotency` (PendingCommand record component) |
| **device-model** (`com.homesynapse.device`) | `requires transitive` — Expectation sealed interface in PendingCommand record component | `Expectation` (PendingCommand.expectation field) |
| **state-store** (`com.homesynapse.state`) | `requires transitive` — StateSnapshot and Availability in public API signatures | `StateSnapshot` (ConditionEvaluator.evaluate() parameter), `Availability` (AvailabilityTrigger.targetAvailability record component) |
| **value-model** (`com.homesynapse.value`) | `requires` (non-transitive, M4.0b-4a) — `PendingCommand` Javadoc `{@link}` only; not on the public API in code | `AttributeValue` (Javadoc reference via `Expectation#evaluate`). Reachable transitively via device-model too; declared explicitly per the relocation design note. |

### Gradle Dependencies

```kotlin
dependencies {
    api(project(":platform:platform-api"))
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))
    implementation(project(":core:value-model"))   // M4.0b-4a — PendingCommand Javadoc {@link} to AttributeValue
}
```

The four `api`-scoped upstream modules surface their types on this module's public API signatures; `com.homesynapse.value` is `implementation` scope (non-transitive — only a Javadoc reference, no public-API use). Configuration dependency removed pre-Phase 3 (FIX-07). Will be re-added when automation implementation imports configuration types (SchemaRegistry, ConfigurationService for schema registration and config access).

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
- **RunContext.cascadeDepth is 0 for user/device-initiated Runs.** Cascade Runs set `cascadeDepth = parent.cascadeDepth + 1`. Maximum governed by `automation.max_cascade_depth` config (default 8, range 1–32). Exceeding max produces `cascade_depth_exceeded` DIAGNOSTIC event. **Governing model AMD-91 (supersedes AMD-04, RATIFIED 2026-06-12):** depth-limiting unchanged (same key/default/range/`cascade_depth_exceeded`), but cycle detection is now deterministic **chain-membership** over a `RunCausalChain` emitting a distinct `cascade_loop_detected` (no windowed/evictable suppression set; AMD-91-INV-01 — suppression is a pure function of the causal chain + config). The field-swap `cascadeDepth (int)` → `causalChain (RunCausalChain)` is **M7.2** (AMD-91 §2.2/§8) — not yet applied; do not build the depth-only model.
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

1. **Selector** — 7 permits (all Tier 1, AMD-89): DirectRefSelector, SlugSelector, AreaSelector, LabelSelector, TypeSelector, SemanticTagSelector, CompoundSelector
2. **TriggerDefinition** — 12 permits (9 Tier 1 + 3 Tier 2, AMD-88): StateChangeTrigger, StateTrigger, EventTrigger, AvailabilityTrigger, NumericThresholdTrigger, CalendarTrigger, ReachabilityTrigger, ManualTrigger, WebhookTrigger, TimeTrigger, SunTrigger, PresenceTrigger
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

6. **`config:configuration` dependency removed pre-Phase 3 (FIX-07).** Will be re-added when automation implementation imports configuration types. The JPMS module descriptor only declares modules whose types actually appear in public API signatures.

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

**GOTCHA: `config:configuration` dependency removed pre-Phase 3 (FIX-07).** Will be re-added as `implementation` scope when automation Phase 3 implementation imports ConfigurationService and SchemaRegistry. Config types don't appear in automation's public API, so JPMS `requires` is also not needed until then.

**GOTCHA: `DurationTimer.virtualThread` is `java.lang.Thread`, not a custom type.** No additional `requires` needed in module-info. Thread is in java.base.

**GOTCHA: `Availability` is from state-store, NOT from automation.** `AvailabilityTrigger.targetAvailability` uses `com.homesynapse.state.Availability`. This drives the `requires transitive com.homesynapse.state` declaration.

**GOTCHA: `CommandIdempotency` is in event-model, NOT in device-model.** Despite being command-related, it lives in `com.homesynapse.event`. Used in PendingCommand.idempotency.

**GOTCHA: `package-info.java` was repurposed, not deleted.** The original scaffold was a bare placeholder. It was rewritten with full package-level Javadoc describing the module's purpose and key types.

**GOTCHA (M7.1): event-payload type residency is a hard JPMS compile cycle, not a lint (SD-1 / AMD-92-INV-01).** No `com.homesynapse.automation`-resident type (`RunId`/`RunStatus`/`MatchMode`/selectors/triggers/`RunContext`) may appear in any `com.homesynapse.event` record — that inverts the edge to `event→automation` and the build fails with a cycle. The M7.1 event records flatten everything: `runId`=bare `Ulid`, `cascadeDepth`=`int`, statuses=`String`. The reshaped `AutomationTriggeredEvent.matchedTriggers` carries trigger IDs (`List<String>`), not indices.

**GOTCHA (M7.1): `EntityRole`, NOT `EntityCategory` (AMD-89 §1.2).** `EntityCategory` does not exist in source; `EntityRole {PRIMARY, DIAGNOSTIC, CONFIG}` (`com.homesynapse.device`, AMD-44) is the role-filter substrate. Distinct from `EventCategory` in event-model — do not conflate.

**RESOLVED (M7.2a-1): the C1-interim publish hold (SD-3) is CLOSED.** M7.1 deliberately did NOT publish `automation_triggered` (no completing side existed). M7.2a-1's `StandardRunManager` gives it its completing partner: it publishes `automation_triggered` at Run initiation and `automation_completed` at the terminal transition — both publish sites now exist (DP-E). The `automation_triggered` publish site is `StandardRunManager.publishTriggered(...)`; `automation_completed` is `publishCompleted(...)`. (M7.1's `trigger_duration_*` diagnostics already published; unchanged.)

**GOTCHA (M7.1) — three substrate gaps flagged for follow-up (not collapsed):**
- **Slug-tombstone (Identity Model §7.5):** no slug-tombstone substrate exists at this baseline, so `SlugSelector` resolves current slugs only and `automation_slug_redirect` (row 11) is minted/registered but has no production publish site (dormant, like the Calendar/Webhook permits). Tombstone-following + the redirect publish await the identity substrate.
- **Area addressing (AMD-44 Stage-1 minimal `Area`):** `Area` carries no first-class slug, so `AreaSelector` keys on the area's display name (case-insensitive + space→underscore slugify); entity area inherits the owning device's area via `DeviceRegistry`. A first-class area slug is the robust fix.
- **`Availability` granularity (R-δ AX-8):** the enum is `AVAILABLE/UNAVAILABLE/UNKNOWN` only — it cannot distinguish a battery device legitimately asleep from a mains device that is dead, so `ReachabilityTrigger` evaluation can false-alarm. An integration/state-store gap, flagged, not collapsed in M7.1.

**GOTCHA (M7.1): the duration-timer expiry seam is clock-gated, not wall-sleep-gated (REC-156).** A timer's `expiresAt` is computed from the injected `Clock` at start; expiry is decided by `pollExpirations()` comparing the clock to `expiresAt`. The per-timer virtual thread (LTD-01) wakes after a wall estimate and delegates the decision to `pollExpirations()`, so tests step a `MutableClock` and call `pollExpirations()` deterministically. `Instant + Duration` is DST-agnostic (REC-167). Always call `StandardTriggerEvaluator.close()` to interrupt lingering timer VTs.

## Phase 3 Notes

- **Three event bus subscribers needed:** `automation_engine` (trigger evaluation, Run initiation), `command_dispatch_service` (command routing to integration adapters), `pending_command_ledger` (command tracking and state confirmation correlation). Each subscribes independently with its own checkpoint.
- **AutomationRegistry implementation needed:** In-memory map + trigger index (event type → matching automations). Hot-reload with in-progress Run preservation — active Runs complete against their original definition snapshot.
- **TriggerEvaluator implementation needed:** O(1) event-type lookup via trigger index. Duration timer management (AMD-25) — start timers, cancel on state change, fire on expiry. Virtual thread per timer.
- **ConditionEvaluator implementation needed:** Pattern matching on ConditionDefinition subtypes. Recursive evaluation for And/Or/Not. StateSnapshot access for state/numeric conditions. Time checking for TimeCondition.
- **ActionExecutor implementation needed:** Sequential execution on Run's virtual thread. CommandAction → CommandDispatchService. DelayAction → Thread.sleep(). WaitForAction → polling loop. ConditionBranchAction → recursive evaluate + execute. EmitEventAction → EventPublisher.
- **RunManager implementation — LANDED M7.2a-1 (`StandardRunManager`).** Concurrency-mode enforcement (the 4 modes; QUEUED admission == PARALLEL-bounded, true sequential draining deferred — see the M7.2a-1 [REVIEW]), cascade governance (depth + AMD-91 chain-membership cycle), C2 dedup map, auto-disable window, VT-per-Run, active-Run + terminal-status tracking, and the run-lifecycle event publishes. Exposed via `RunManagerAssembly`. `RunConditionGate`/`ActionExecutor` are injected seams (real impls in M7.2a-2). Static `byExecutionOrder()` comparator provides C3 ordering for the subscriber. **Condition-before-mode** (the locked cross-module contract) is honored: the gate runs in `initiateRun` before mode admission, so a `CONDITION_NOT_MET` Run never consumes a slot.
- **CommandDispatchService implementation needed:** Entity → integration resolution via DeviceRegistry.getIntegrationForEntity(). Command validation via CommandValidator.validate(). Event production (command_dispatched, command_result).
- **PendingCommandLedger implementation needed:** ConcurrentHashMap<EventId, PendingCommand> for pending tracking. Expectation evaluation against state_reported events. Deadline timer management. Event production (state_confirmed, command_confirmation_timed_out).
- **SelectorResolver implementation needed:** Delegation to EntityRegistry for each selector type. Compound intersection logic.
- **ConflictDetector implementation needed:** Post-execution scan of RunContexts for contradictory commands targeting same entity. DIAGNOSTIC event production.
- **Identity file management needed:** `automations.ids.yaml` companion file — maps automation slug → AutomationId ULID. Retention of removed slugs for 30 days (configurable via `automation.identity_retention_days`).
- **REPLAY behavior needed:** Duration timers are not persisted — rebuilt from events on REPLAY→LIVE transition. RunContext.definitionHash enables replay verification.
- **Zombie Run finalization on REPLAY→LIVE (Doc 07 §3.10) — LANDED M7.2a-1 (`RunManager.finalizeZombieRuns(List<ZombieRun>)`).** The composition root reconstructs the zombie set from the **immutable event log only** (the E91-1 pin — never a windowed/in-memory map): each `automation_triggered` with no matching `automation_completed` becomes a `ZombieRun` descriptor. The FSM publishes `automation_completed` with `finalStatus = "INTERRUPTED"` + `abortReason = "interrupted_by_crash"`, freeing the slot and restoring C1 — **re-derived, never re-executed** (the executor is not invoked). Wiring of the reconstruction (log scan) rides the composition root post-2a-2.
- **Availability trigger suppression during planned restarts (Doc 07 §3.5, Doc 05 §3.14):** When the automation engine receives an `integration_stopped` event with reason `planned_restart`, add the integration's ID to an in-memory `Set<IntegrationId>` of integrations in planned restart. Suppress `AvailabilityTrigger` evaluation for entities owned by integrations in this set. Remove the integration from the set when `integration_restarted` is received or when the 60s timeout expires. The automation engine learns about planned restarts via events, NOT by reading `IntegrationHealthRecord.plannedRestart()` — JPMS prevents this dependency direction.
- **SelectorResolver must follow slug tombstone chains (Identity Model §7.5).** When resolving a `SlugSelector`, if the slug maps to a tombstone entry, follow the tombstone chain to the current slug and resolve that. Produce an `automation_slug_redirect` DIAGNOSTIC event documenting the redirect. This prevents automations from silently breaking when entities are renamed.
- **Schema registration needed:** Automation schema fragment registered via SchemaRegistry.registerCoreSchema() at startup.
- **Testing strategy:** Unit tests for record construction, field validation, defensive copying. Integration tests for trigger evaluation (event matching, duration timer lifecycle), condition evaluation (all subtypes + combinators), action execution (sequential ordering, branching, delay/wait-for), Run lifecycle (mode enforcement, deduplication, cascade governance), command dispatch (routing, validation), pending command tracking (confirmation, timeout, expiry). Performance targets from Doc 07 §10 should be investigation triggers.
- **`json-schema-validator` still needed in libs.versions.toml for Phase 3** (flagged in Block K, still outstanding).

### Critical Review Integration (AUTOMATION_ENGINE_CRITICAL_REVIEW.md)

The external critical review identified 28 issues. Four are resolved by amendments (AMD-03, AMD-04, AMD-25, AMD-31). Ten are deferred to Tier 2. The remaining implementation-level findings below must be addressed during Phase 3 coding.

**Already resolved by amendments (no further action):**
- Issue 2.1 (atomic condition snapshot) → AMD-03: StateSnapshot via `StateQueryService.getSnapshot()` at trigger time
- Issue 2.4 / 1.3 (replay determinism) → Doc 07 §3.10: replay is reconstructive, not re-evaluative
- Issue 10.1 (cascade loops) → **AMD-04 SUPERSEDED by AMD-91** (RATIFIED 2026-06-12): depth-limiting unchanged (max 8), but duplicate/cycle suppression is now deterministic chain-membership over a `RunCausalChain` + distinct `cascade_loop_detected` (replacing AMD-04's windowed suppression set); field-swap to `causalChain` is M7.2 (AMD-91 §2.2/§8)
- Issues 3.1 / 3.2 (command execution order) → AMD-31: sequential within Run, ULID ascending multi-target, log-order dispatch

**Implementation-level findings for Phase 3:**

| Issue | Summary | Implementation Guidance |
|---|---|---|
| 1.1 | Event-time vs current-time state semantics | Condition evaluation uses StateSnapshot captured at trigger time (AMD-03). Document this clearly in ConditionEvaluator Javadoc. |
| 1.2 | Atomic trigger evaluation | TriggerEvaluator.evaluate() runs per-event sequentially within the subscriber's pull loop. Document thread-safety guarantee: all triggers for one event evaluated atomically before the next event is processed. |
| 1.4 | Startup behavior of level-triggered automations | Triggers fire on `state_changed` events only, not on initial state load. No catch-up evaluation at startup. Document in AutomationRegistry Javadoc. |
| 2.2 | Read-time version tracking | Extend `automation_condition_evaluated` event payload with `last_changed_at` and `last_changed_by_event_id` for each evaluated entity state. Enables debugging "why didn't the automation run?" |
| 2.3 | Short-circuit evaluation order | Implement left-to-right depth-first evaluation for `and`/`or`/`not` conditions. Order is stable across restarts and replays. Unevaluated conditions (due to short-circuit) do NOT produce `automation_condition_evaluated` events. |
| 3.3 | No action rollback | Fail-fast semantics: if action N fails, actions N+1..M do not execute. No rollback of completed actions. Document in ActionExecutor Javadoc. |
| 3.4 | Unavailable target semantics | `UnavailablePolicy` applies per-target: SKIP skips only the unavailable entity (others still receive commands), ERROR fails the Run, WARN dispatches anyway with DIAGNOSTIC event. |
| 3.5 | Command dispatch timeout | Confirmation timeout is tracked by PendingCommandLedger (default 30s), not ActionExecutor. The Run completes independently of command confirmation. `command_confirmation_timed_out` is DIAGNOSTIC, not an error. |
| 4.1 | Restart mode cancellation | When `restart` mode cancels an active Run, in-flight commands from the cancelled Run are NOT recalled. Commands are fire-and-forget at dispatch. Document in RunManager Javadoc. |
| 4.2 | Run completion vs command status | Run status reflects automation lifecycle (COMPLETED/FAILED/ABORTED), NOT command confirmation status. A Run can be COMPLETED while its commands are still pending confirmation. |
| 5.1 | Conflict detection timing | ConflictDetector runs after Run initiation, scanning for contradictory commands within the same correlation chain. Produces `conflict_detected` DIAGNOSTIC events. |
| 5.2 | No conflict suppression | Tier 1: both conflicting commands execute. Conflict events are informational only. No automatic resolution. |
| 6.1 | Tier 2 trigger graceful fallback | When a Tier 2 trigger type (zone, scheduled) is loaded in Tier 1, produce `config_warning` event (not error). Mark the trigger as inactive, set automation health to DEGRADED. |
| 7.1/7.2 | Retry and auto-disable | No per-action retry in Tier 1. Auto-disable threshold (default 5 failures) treats all failure types equally. Document root cause analysis guidance in operator docs. |
| 10.4 | Automation versioning | No built-in versioning. Recommend external Git for `automations.yaml`. `definition_hash` in RunContext detects config-change-vs-Run consistency. |
| 10.8 | Selector performance | Selector resolution is cached at trigger time. Large selections (100+ entities) should be avoided in high-frequency automations. Future: build indices in EntityRegistry. |

**Deferred to Tier 2 (no Phase 3 action):**
Issues 4.3 (run cancellation API), 5.3 (cascade-aware conflict detection), 6.2/6.3 (location/scheduled catch-up), 7.3 (dead letter queue), 10.2 (automation dependencies), 10.3 (templating), 10.5 (dry-run mode), 10.6 (rate limiting), 10.7 (automation templates).


---


## Phase 3 Cross-Module Context

*Updated 2026-05-17 (Post-M3.1 refresh). Phase 3 active — M3.1 `InProcessEventBus` landed 2026-05-17. Next milestone: M3.5a (StateProjection vertical slice). M3 governance: AMD-41/42/43 APPLIED. See `homesynapse-core-docs/design/HomeSynapse_Core_M3_Implementation_Plan_PLAN-M3-CONSOLIDATED-02.md` for the full M3 implementation plan.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: automation trigger evaluation dispatches on event types via `@EventType` registry lookup, not sealed interface pattern matching
- **D-05** — *`@EventType` on every event record*: automation trigger matching uses event type strings from the registry

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for Phase 3 pattern discoveries (including M3.1 entries on default interface methods, contract test capability hooks, and JPMS-enforced JDBC-free constraints).
