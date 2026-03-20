# Block Q — Observability & Debugging

**Module:** `observability/observability`
**Package:** `com.homesynapse.observability`
**Design Doc:** Doc 11 — Observability & Debugging (§3, §4, §5, §7, §8) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :observability:observability:compileJava`

---

## Strategic Context

The Observability & Debugging subsystem is the system's diagnostic backbone — the infrastructure that every other subsystem's §11 reports into. It owns health aggregation (composing 10 per-subsystem health indicators into an actionable tiered picture), trace query (making the causal chain metadata on every EventEnvelope navigable), JFR metrics infrastructure (continuous recording, custom event registry, streaming bridge), and dynamic log level control.

The competitive gap is concrete: no existing smart home platform answers "why did this happen?" with a full end-to-end causal chain. HomeSynapse's event-sourced architecture, with `correlationId` and `causationId` on every EventEnvelope (Doc 01 §4.1), makes the chain a stored fact. This subsystem makes it queryable.

Block Q produces the observability module's Phase 2 interface specification: all data records (health model, trace model, metric snapshots, log level overrides), all enums (HealthStatus, HealthTier, LifecycleState), the TraceCompleteness sealed interface hierarchy, and the six service interfaces (HealthAggregator, HealthContributor, TraceQueryService, MetricsRegistry, MetricsStreamBridge, LogLevelController). This is a medium-large block — approximately 22 files, ~800 lines.

**Strategic importance:** This module serves INV-TO-01 (system behavior is observable), INV-ES-06 (every state change is explainable through the causal chain), and INV-PR-03 (bounded overhead for always-on diagnostics). The HealthAggregator is consumed by the Startup/Lifecycle module (Doc 12) for boot sequencing, the REST API (Doc 09) for the system health endpoint, and the Web UI (Doc 13) for the three-tier health dashboard.

## Scope

**IN:** 3 enums (HealthStatus, HealthTier, LifecycleState), 6 data records (SystemHealth, TierHealth, SubsystemHealth, TraceChain, TraceEvent, TraceNode), 1 sealed interface hierarchy (TraceCompleteness — 3 permitted records: Complete, InProgress, PossiblyIncomplete), 2 additional data records (MetricSnapshot, LogLevelOverride), 6 service interfaces (HealthAggregator, HealthContributor, TraceQueryService, MetricsRegistry, MetricsStreamBridge, LogLevelController), module-info.java, updated package-info.java, build.gradle.kts verification. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references for all types.

**OUT:** Implementation code. Tests. JFR recording lifecycle management. JFR RecordingStream processing. Logback LoggerContext manipulation. Health state machine transition logic. Flapping prevention timer logic. Trace chain assembly SQL queries. JFR custom event class definitions (these are Phase 3 internal types, not API-surface types). Tiered composition algorithm implementation. Startup grace period tracking. Health history persistence. Metric snapshot persistence or aggregation.

---

## Files to Read Before Starting

Read these files in this order before writing any code:

1. **NexSys Coder skill** — Coding standards, Java patterns, Javadoc conventions
2. **Doc 11 — Observability & Debugging** (`homesynapse-core-docs/design/11-observability-and-debugging.md`) — §4 (Data Model), §5 (Contracts), §7 (Interactions), §8 (Key Interfaces). The design doc is authoritative for all type shapes, behavioral contracts, and cross-subsystem interactions.
3. **`platform/platform-api/MODULE_CONTEXT.md`** — Identity types (Ulid, EntityId, IntegrationId) used in trace query parameters
4. **`core/event-model/MODULE_CONTEXT.md`** — EventEnvelope, CausalContext, EventStore, SubjectRef, EventId — the trace query service reads from EventStore and references causal chain metadata
5. **`core/state-store/MODULE_CONTEXT.md`** — StateQueryService referenced in interaction patterns (§7.4 trace data source)
6. **`integration/integration-runtime/MODULE_CONTEXT.md`** — IntegrationSupervisor.allHealth() is consumed by the observability module for composite health indicators

---

## Locked Decisions

1. **Health model uses three statuses, not four.** Doc 11 §4.1: `HealthStatus` has exactly three values — `HEALTHY`, `DEGRADED`, `UNHEALTHY`. Doc 01's "CRITICAL" maps to UNHEALTHY (§7.1). Do not add CRITICAL or STARTING as health status values.

2. **Three-tier health aggregation model.** Doc 11 §3.3, decision D-02: Tiered composition with worst-of per tier, worst-of across tiers. Tier assignments per D-03:
   - Tier 1 (CRITICAL_INFRASTRUCTURE): Event Bus, State Store, Persistence
   - Tier 2 (CORE_SERVICES): Automation Engine, Integration Runtime, Configuration, Device Model, Observability (self)
   - Tier 3 (INTERFACE_SERVICES): REST API, WebSocket API

3. **Three lifecycle states.** Doc 11 §3.3, decision D-04: `LifecycleState` has exactly `STARTING`, `RUNNING`, `SHUTTING_DOWN`. The lifecycle FSM is one-directional: STARTING → RUNNING → SHUTTING_DOWN. No reverse transitions.

4. **TraceCompleteness is a sealed interface with three permitted records.** Doc 11 §4.2: `Complete(String terminalEventType)`, `InProgress(Instant lastEventTime, Duration elapsed)`, `PossiblyIncomplete(String reason)`. All three are records, not interfaces — use `record ... implements TraceCompleteness` like all prior sealed hierarchies in the project.

5. **TraceEvent uses `String` for identifiers, not typed ULID wrappers.** Doc 11 §4.2 specifies `TraceEvent` fields as `String eventId`, `String correlationId`, `String causationId`, `String entityId`. This is deliberate — the trace query service operates on the text representation of identifiers as returned from SQL queries and EventStore reads. The trace model is a presentation/query-layer concern, not a domain model concern. Do NOT change these to typed wrappers.

6. **TraceEvent.payload is `Map<String, Object>`.** Doc 11 §4.2: the payload field carries event-type-specific data in a schemaless map. This is a query-result representation — the trace service extracts relevant payload fields from the DomainEvent for display. Phase 3 determines which fields to include per event type.

7. **MetricSnapshot is a new record not fully specified in the design doc.** Doc 11 §8.3 references it as the output of the MetricsStreamBridge, and §3.2 describes what it contains (min, max, count, sum per flush window for each metric). Define it with: `metricName` (String), `min` (double), `max` (double), `count` (long), `sum` (double), `windowStart` (Instant), `windowEnd` (Instant). This matches the pre-aggregation semantics described in §3.2. All non-null.

8. **LogLevelOverride is a new record.** Doc 11 §8.3 references it as the return type of `LogLevelController.listOverrides()`. Define it with: `loggerName` (String), `originalLevel` (String), `overrideLevel` (String), `appliedAt` (Instant). All non-null. The level strings use SLF4J names: "TRACE", "DEBUG", "INFO", "WARN", "ERROR".

9. **HealthContributor is a callback interface, not a data type.** Doc 11 §8.1–§8.2: subsystems receive a `HealthContributor` instance and call `reportHealth(HealthStatus status, String reason)` when their health changes. It also has `getSubsystemId()` which returns the subsystem identifier for tier classification. The HealthAggregator provides HealthContributor instances to each subsystem during initialization.

10. **JPMS Default Rule (LD#10 from Block N):** All inter-module `requires` directives default to `requires transitive`. Use non-transitive `requires` ONLY when you can confirm that NO types from the required module appear in any record component, method parameter, return type, exception superclass, or throws clause in this module's exported API.

    **Analysis for this module:**
    - `event-model` types appear in the exported API: `TraceQueryService` methods use `Ulid` (for correlationId parameter via platform-api, transitively), `Instant` (JDK), and `String`. The `EventStore` interface is consumed internally by TraceQueryService Phase 3 but does NOT appear in Phase 2 signatures. However, `EntityId` (from platform-api, transitive through event-model) appears in `TraceQueryService.findRecentChain(EntityId)` and `findChains(EntityId, ...)`. Therefore: `requires transitive com.homesynapse.event` — this provides transitive access to platform-api and its typed ID wrappers.
    - `state-store` types: No state-store types appear in the Phase 2 exported API. StateQueryService is a Phase 3 internal dependency only. Therefore: keep `implementation` in build.gradle.kts, no `requires` in module-info yet. Phase 3 will add `requires com.homesynapse.state` (non-transitive).
    - `integration-runtime` types: `IntegrationSupervisor.allHealth()` is consumed in Phase 3 by the observability implementation, not in Phase 2 API signatures. No integration-runtime types appear in the exported API. **Do not add integration-runtime as a dependency.** Phase 3 will add it.

    **build.gradle.kts update required:** Change `api(project(":core:event-model"))` — this is correct (event-model types transitively appear in API via identity types). Change `implementation(project(":core:state-store"))` — keep as-is (Phase 3 only). **No new dependencies needed for Phase 2.**

    Module-info:
    ```java
    module com.homesynapse.observability {
        requires transitive com.homesynapse.event;

        exports com.homesynapse.observability;
    }
    ```

    Note: `com.homesynapse.event` already `requires transitive com.homesynapse.platform`, so all typed ID wrappers (EntityId, etc.) are transitively available.

11. **HealthAggregator.onHealthChange() receives the subsystemId as a parameter.** Doc 11 §8.2: `onHealthChange(String subsystemId, HealthStatus status, String reason)`. The subsystemId is a string like "event-bus", "state-store" — it is NOT a typed ULID. This is deliberate: subsystem identifiers are compile-time constants, not domain objects. The tier classification uses this string to look up the subsystem's tier assignment.

12. **HealthContributor is a per-subsystem callback.** Each subsystem receives its own HealthContributor instance from the HealthAggregator during initialization. The HealthContributor knows its subsystemId — it was constructed with it. When a subsystem calls `reportHealth(status, reason)`, the contributor forwards to `HealthAggregator.onHealthChange(subsystemId, status, reason)` internally. This indirection keeps subsystems decoupled from the aggregation mechanism.

13. **TraceQueryService methods use standard Java types in signatures.** Methods accept `Ulid` (for correlationId — from platform-api), `EntityId` (for entity lookups — from platform-api), `String` (for eventType), `Instant` (for time ranges), and `int` (for limits). Return types are `Optional<TraceChain>`, `List<TraceChain>`. No custom query parameter objects.

14. **No cross-module updates.** Block Q does not modify any files in other modules. All work is new file creation within the observability module, plus updating the existing package-info.java.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Enums (3 files — no inter-type dependencies)

#### Step 1: `HealthStatus.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/HealthStatus.java`

```java
public enum HealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY
}
```

Javadoc: Three-state health model for subsystem and system-wide health reporting. Doc 11 §4.1. `HEALTHY` — subsystem operating normally within all parameters. `DEGRADED` — subsystem functional but with reduced capability or performance (e.g., stale data, dropped metrics, partial connectivity). `UNHEALTHY` — subsystem unable to perform its primary function. Note: Doc 01's `CRITICAL` health state maps to `UNHEALTHY` in this model (Doc 11 §7.1). Thread-safe (enum).

#### Step 2: `HealthTier.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/HealthTier.java`

```java
public enum HealthTier {
    CRITICAL_INFRASTRUCTURE,
    CORE_SERVICES,
    INTERFACE_SERVICES
}
```

Javadoc: Tiered classification for health aggregation composition. Doc 11 §3.3, decision D-02/D-03. `CRITICAL_INFRASTRUCTURE` (Tier 1) — Event Bus, State Store, Persistence. Any UNHEALTHY → system UNHEALTHY; any DEGRADED → system DEGRADED. `CORE_SERVICES` (Tier 2) — Automation Engine, Integration Runtime, Configuration, Device Model, Observability. ≥2 DEGRADED or any UNHEALTHY → system DEGRADED. `INTERFACE_SERVICES` (Tier 3) — REST API, WebSocket API. All UNHEALTHY → system DEGRADED. System-wide health is worst-of across tier results. Thread-safe (enum).

Document each constant's Javadoc with the tier number, the subsystems assigned to it, and the aggregation rule.

#### Step 3: `LifecycleState.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/LifecycleState.java`

```java
public enum LifecycleState {
    STARTING,
    RUNNING,
    SHUTTING_DOWN
}
```

Javadoc: System-wide lifecycle state governing health aggregation behavior. Doc 11 §3.3, decision D-04. `STARTING` — system is initializing; per-subsystem startup grace periods apply; subsystems reporting DEGRADED within grace period are excluded from aggregate composition. `RUNNING` — normal operation; all tier rules apply. `SHUTTING_DOWN` — system is shutting down; health transitions are still tracked but the system is expected to degrade. Transitions are one-directional: `STARTING → RUNNING → SHUTTING_DOWN`. Thread-safe (enum).

### Group 2: Health Model Records (3 files — depend on Group 1 enums)

#### Step 4: `SubsystemHealth.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/SubsystemHealth.java`

```java
public record SubsystemHealth(
    String subsystemId,
    HealthTier tier,
    HealthStatus status,
    Instant since,
    String reason,
    boolean inGracePeriod
) {}
```

Javadoc: Individual subsystem health snapshot within the aggregated system health model. Doc 11 §4.1. `subsystemId` — subsystem identifier string (e.g., "event-bus", "state-store", "automation-engine"). NOT a typed ULID — subsystem identifiers are compile-time constants. `tier` — the health tier this subsystem is assigned to, per decision D-03. `status` — current health status as reported by the subsystem via `HealthContributor`. `since` — timestamp when the subsystem entered its current status; advances on every status transition. `reason` — human-readable reason string provided by the subsystem explaining its current status (e.g., "JFR recording stalled: no flush in 45 seconds"). `inGracePeriod` — true during startup when the subsystem is still within its configured grace period; subsystems in grace period are excluded from aggregate composition.

Compact constructor: validate all fields non-null except none — all fields are non-null (boolean is primitive). `Objects.requireNonNull` on subsystemId, tier, status, since, reason.

#### Step 5: `TierHealth.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/TierHealth.java`

```java
public record TierHealth(
    HealthTier tier,
    HealthStatus status,
    List<String> degradedSubsystems,
    List<String> unhealthySubsystems
) {}
```

Javadoc: Per-tier health summary within the aggregated system health model. Doc 11 §4.1. `tier` — which health tier this summary covers. `status` — the tier's aggregate health status, computed from tier-specific rules (see `HealthTier` Javadoc). `degradedSubsystems` — list of subsystem IDs currently reporting DEGRADED within this tier; may be empty. `unhealthySubsystems` — list of subsystem IDs currently reporting UNHEALTHY within this tier; may be empty.

Compact constructor: `Objects.requireNonNull` on all fields. Defensive copy: `degradedSubsystems = List.copyOf(degradedSubsystems)`, `unhealthySubsystems = List.copyOf(unhealthySubsystems)`.

#### Step 6: `SystemHealth.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/SystemHealth.java`

```java
public record SystemHealth(
    HealthStatus status,
    LifecycleState lifecycle,
    Instant since,
    String reason,
    Map<HealthTier, TierHealth> tiers,
    Map<String, SubsystemHealth> subsystems
) {}
```

Javadoc: Complete system health snapshot with tiered breakdown and per-subsystem detail. Doc 11 §4.1. This is the top-level health data structure returned by `HealthAggregator.getSystemHealth()` and consumed by the REST API's `/api/v1/system/health` endpoint (Doc 09) and the Web UI's three-tier health dashboard (Doc 13 §3.8). `status` — system-wide health, computed as worst-of across all tier results. `lifecycle` — current system lifecycle state. `since` — timestamp when the system entered its current health status. `reason` — human-readable reason explaining the current system status (e.g., "Tier 1 degraded: state-store reporting DEGRADED"). `tiers` — per-tier health summaries keyed by `HealthTier`. `subsystems` — per-subsystem health details keyed by subsystem ID string.

Compact constructor: `Objects.requireNonNull` on all fields. Defensive copy: `tiers = Map.copyOf(tiers)`, `subsystems = Map.copyOf(subsystems)`.

### Group 3: Trace Model (4 files — TraceCompleteness sealed hierarchy, then TraceEvent, TraceNode, TraceChain)

#### Step 7: `TraceCompleteness.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/TraceCompleteness.java`

```java
public sealed interface TraceCompleteness
        permits TraceCompleteness.Complete,
                TraceCompleteness.InProgress,
                TraceCompleteness.PossiblyIncomplete {

    record Complete(String terminalEventType) implements TraceCompleteness {}
    record InProgress(Instant lastEventTime, Duration elapsed) implements TraceCompleteness {}
    record PossiblyIncomplete(String reason) implements TraceCompleteness {}
}
```

Javadoc on sealed interface: Completeness status of a causal chain query result. Doc 11 §3.4, §4.2, decision D-14. Terminal event types (`state_confirmed`, `automation_completed`, `command_failed`, `command_timed_out`) signal chain completion. Chains without a terminal event are in-progress. Chains with gaps (missing events due to retention purge) are possibly incomplete.

Javadoc on `Complete`: `terminalEventType` — the event type of the terminal event (e.g., `EventTypes.STATE_CONFIRMED`). Non-null.

Javadoc on `InProgress`: `lastEventTime` — timestamp of the most recent event in the chain. Non-null. `elapsed` — duration since `lastEventTime`. Non-null. Chains in-progress for over 30 seconds show a warning indicator; over 5 minutes, a potential failure indicator (Doc 11 §3.4, configurable via `observability.trace.incomplete_warning_seconds` and `incomplete_failure_seconds`).

Javadoc on `PossiblyIncomplete`: `reason` — explanation for incompleteness (e.g., "retention purged: 3 events missing"). Non-null.

Compact constructors: `Objects.requireNonNull` on all fields in all three records.

#### Step 8: `TraceEvent.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/TraceEvent.java`

```java
public record TraceEvent(
    String eventId,
    String eventType,
    String entityId,
    String correlationId,
    String causationId,
    Instant eventTime,
    Instant ingestTime,
    Map<String, Object> payload
) {}
```

Javadoc: Single event within a causal chain query result. Doc 11 §4.2. This is a query-layer representation of an event — it uses String identifiers (not typed ULID wrappers) because the trace service operates on text representations from EventStore queries. `eventId` — the event's unique identifier. Non-null. `eventType` — dotted event type string (e.g., "device.state_changed"). Non-null. `entityId` — the subject entity's identifier. Non-null. `correlationId` — shared across all events in the causal chain. Non-null. `causationId` — the eventId of the event that caused this event; **null for the root event** of the chain. `eventTime` — when the event occurred (may differ from ingestTime for physical events). Non-null. `ingestTime` — when the event was appended to the event store. Non-null. `payload` — event-type-specific data extracted from the DomainEvent payload; may be empty but never null.

Compact constructor: `Objects.requireNonNull` on eventId, eventType, entityId, correlationId, eventTime, ingestTime, payload. `causationId` is explicitly **nullable** — null for the root event. Defensive copy: `payload = Map.copyOf(payload)`.

**IMPORTANT:** `Map.copyOf()` does not accept null values in the map. If Phase 3 needs nullable values in the payload map, this will need adjustment. For Phase 2, the contract states no null values in payload — all values are present or the key is absent.

#### Step 9: `TraceNode.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/TraceNode.java`

```java
public record TraceNode(
    TraceEvent event,
    List<TraceNode> children
) {}
```

Javadoc: Tree node in the causal chain structure, wrapping a `TraceEvent` with its direct children. Doc 11 §4.2. The root `TraceNode` of a `TraceChain` represents the triggering event; children represent events caused by the parent (linked via `causationId`). Children are ordered by `eventTime` ascending. `event` — the trace event at this node. Non-null. `children` — direct children of this node in the causal chain; may be empty for leaf nodes (e.g., terminal events like state_confirmed). Non-null.

Compact constructor: `Objects.requireNonNull` on both fields. Defensive copy: `children = List.copyOf(children)`.

#### Step 10: `TraceChain.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/TraceChain.java`

```java
public record TraceChain(
    String correlationId,
    TraceEvent rootEvent,
    List<TraceEvent> orderedEvents,
    TraceNode tree,
    TraceCompleteness completeness,
    Instant firstTimestamp,
    Instant lastTimestamp
) {}
```

Javadoc: Complete causal chain for a single correlation_id, assembled by the `TraceQueryService`. Doc 11 §4.2. This is the primary data structure returned by trace queries and consumed by the REST API (Doc 09) and the Web UI's trace visualization (Doc 13 §3.6.3). `correlationId` — the shared correlation identifier linking all events in this chain. Non-null. `rootEvent` — the event that initiated the chain (the event with no causationId). Non-null. `orderedEvents` — all events in the chain, ordered by timestamp ascending. Non-null, non-empty. `tree` — hierarchical tree structure built from causation_id parent-child relationships. Non-null. `completeness` — whether the chain is complete, in-progress, or possibly incomplete. Non-null. `firstTimestamp` — timestamp of the earliest event. Non-null. `lastTimestamp` — timestamp of the most recent event. Non-null.

Compact constructor: `Objects.requireNonNull` on all fields. Defensive copy: `orderedEvents = List.copyOf(orderedEvents)`. Validate: `!orderedEvents.isEmpty()`.

### Group 4: Utility Records (2 files — MetricSnapshot and LogLevelOverride)

#### Step 11: `MetricSnapshot.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/MetricSnapshot.java`

```java
public record MetricSnapshot(
    String metricName,
    double min,
    double max,
    long count,
    double sum,
    Instant windowStart,
    Instant windowEnd
) {}
```

Javadoc: Aggregated metric values for one flush window, produced by the `MetricsStreamBridge` from JFR event pre-aggregation. Doc 11 §3.2, §8.2. Each snapshot represents the aggregation of all JFR events of a specific metric type within a single flush window (~1 second). `metricName` — the metric identifier string (e.g., "hs_events_append_latency_ms"). Non-null. `min` — minimum observed value in the window. `max` — maximum observed value in the window. `count` — number of events aggregated in the window. Must be ≥ 0. `sum` — sum of all observed values in the window. `windowStart` — timestamp of the window's start. Non-null. `windowEnd` — timestamp of the window's end. Non-null.

Compact constructor: `Objects.requireNonNull` on metricName, windowStart, windowEnd. Validate: `count >= 0`.

#### Step 12: `LogLevelOverride.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/LogLevelOverride.java`

```java
public record LogLevelOverride(
    String loggerName,
    String originalLevel,
    String overrideLevel,
    Instant appliedAt
) {}
```

Javadoc: Active dynamic log level override, representing a runtime adjustment to a logger's effective level. Doc 11 §3.6, §8.2. Dynamic log level changes are applied via `LogLevelController.setLevel()` and persist only until the next restart — they are not written to configuration files. `loggerName` — the fully-qualified logger name (e.g., "com.homesynapse.integration.zigbee"). Non-null. Must match a configured allowed prefix (default: "com.homesynapse"). `originalLevel` — the logger's configured default level before the override. Non-null. Uses SLF4J level names: "TRACE", "DEBUG", "INFO", "WARN", "ERROR". `overrideLevel` — the currently active override level. Non-null. Same level name convention. `appliedAt` — timestamp when the override was applied. Non-null.

Compact constructor: `Objects.requireNonNull` on all fields.

### Group 5: Service Interfaces (6 files — depend on Groups 1–4)

#### Step 13: `HealthContributor.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/HealthContributor.java`

```java
public interface HealthContributor {
    void reportHealth(HealthStatus status, String reason);
    String getSubsystemId();
}
```

Javadoc: Callback interface for per-subsystem health reporting. Doc 11 §7.1, §8.1–§8.2. Each subsystem receives its own `HealthContributor` instance from the `HealthAggregator` during initialization and calls `reportHealth()` whenever its health state changes. The contributor forwards reports to the aggregator for tier composition.

`reportHealth(HealthStatus status, String reason)` — Report the subsystem's current health state. Called by subsystems when their health transitions (not on a timer — reactive, event-driven). `status` — the subsystem's current health. `reason` — human-readable explanation (e.g., "JFR recording stalled: no flush in 45 seconds"). Both non-null. Thread-safe: implementations must handle concurrent calls from any thread.

`getSubsystemId()` — Returns the subsystem identifier string used for tier classification (e.g., "event-bus", "state-store"). Non-null. The value is fixed at construction time and does not change.

`@see HealthAggregator`
`@see SubsystemHealth`

#### Step 14: `HealthAggregator.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/HealthAggregator.java`

```java
public interface HealthAggregator {
    SystemHealth getSystemHealth();
    Optional<SubsystemHealth> getSubsystemHealth(String subsystemId);
    void onHealthChange(String subsystemId, HealthStatus status, String reason);
    List<SubsystemHealth> getHealthHistory(Instant since, Instant until);
}
```

Javadoc: Composes per-subsystem health indicators into a tiered system health model. Doc 11 §3.3, §8.1–§8.2. Evaluates reactively — triggered by health-change events from subsystems via `onHealthChange()`, not by polling on a timer. Health evaluation is deterministic and reproducible (INV-TO-02): given the same set of per-subsystem health states, the aggregator produces the same system health result.

`getSystemHealth()` — Returns the current complete system health snapshot, including tier breakdown and per-subsystem detail. Non-null. Thread-safe: returns an immutable snapshot consistent at the time of the call. Evaluation is O(10) with no I/O — must complete within 1 ms on Pi 5 (Doc 11 §10). Consumed by the REST API's `GET /api/v1/system/health` endpoint and the Web UI's health dashboard.

`getSubsystemHealth(String subsystemId)` — Returns health for a specific subsystem, or empty if the subsystem is not registered. `subsystemId` — non-null.

`onHealthChange(String subsystemId, HealthStatus status, String reason)` — Called by `HealthContributor` instances when a subsystem's health state changes. Triggers re-evaluation of tier and system-wide health. All parameters non-null. Thread-safe: must handle concurrent calls from multiple subsystem threads. Each call produces a `HealthTransitionEvent` JFR event and a structured log entry (INV-TO-04).

`getHealthHistory(Instant since, Instant until)` — Returns health state transitions in the given time range. Both parameters non-null. Returns an unmodifiable list ordered by timestamp ascending. Reads from the health transition log (JFR events or structured logs, implementation-specific).

`@see HealthContributor`
`@see SystemHealth`
`@see HealthTier`

#### Step 15: `TraceQueryService.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/TraceQueryService.java`

```java
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

public interface TraceQueryService {
    Optional<TraceChain> getChain(Ulid correlationId);
    Optional<TraceChain> findRecentChain(EntityId entityId);
    List<TraceChain> findChains(EntityId entityId, Instant from, Instant until);
    List<TraceChain> findChainsByType(String eventType, Instant from, Instant until);
    List<TraceChain> findChainsByTimeRange(Instant from, Instant until, int limit);
}
```

Javadoc: Assembles and queries causal chains from the EventStore, making the correlation_id / causation_id metadata on every EventEnvelope (Doc 01 §4.1) navigable. Doc 11 §3.4, §8.1–§8.2. This is the mechanism that satisfies INV-ES-06 (every state change is explainable through the causal chain) at the query level. The competitive differentiator: no existing smart home platform provides a full end-to-end causal chain query.

`getChain(Ulid correlationId)` — Assemble the full causal chain for a correlation_id. Doc 11 §3.4 query pattern #1. Returns empty if no events exist with the given correlationId. `correlationId` — non-null. Implementation (Phase 3): single SQL query + O(n) hash map tree build.

`findRecentChain(EntityId entityId)` — Reverse lookup: find the most recent causal chain affecting an entity. Doc 11 §3.4 query pattern #2. This is the primary "why did this happen?" diagnostic query. Returns empty if no state-affecting events exist for the entity. `entityId` — non-null.

`findChains(EntityId entityId, Instant from, Instant until)` — Find all chains involving an entity in a time range. Doc 11 §3.4 query pattern #3. Returns an unmodifiable list ordered by first event timestamp descending. All parameters non-null. May return an empty list.

`findChainsByType(String eventType, Instant from, Instant until)` — Find chains containing a specific event type in a time range. Doc 11 §3.4 query pattern #4. `eventType` — dotted event type string (e.g., `EventTypes.AUTOMATION_TRIGGERED`). All parameters non-null.

`findChainsByTimeRange(Instant from, Instant until, int limit)` — Find all chains in a time range. Doc 11 §3.4 query pattern #5. `limit` — maximum number of chains to return; must be > 0. All parameters non-null. Results ordered by first event timestamp descending.

All methods are read-only — the TraceQueryService never writes to the EventStore (INV-PR-02). Thread-safe. Results are immutable.

`@see TraceChain`
`@see TraceCompleteness`

#### Step 16: `MetricsRegistry.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/MetricsRegistry.java`

```java
public interface MetricsRegistry {
    void register(String eventClassName, String category);
    boolean isRegistered(String eventClassName);
    Set<String> registeredEventClasses();
}
```

Javadoc: Registry for custom JFR event types used by HomeSynapse subsystems. Doc 11 §3.2, §4.3, §8.1. All application-level JFR event types are registered through this interface at subsystem initialization. This is a compile-time registry — the set of custom event types is fixed for a given release. The registry enforces that all JFR event fields use primitives or String (enums are silently ignored by JFR, decision D-09). Budget: 15–25 custom event types with a safe ceiling of 50–100 (Doc 11 §3.2).

`register(String eventClassName, String category)` — Register a custom JFR event type. `eventClassName` — fully-qualified class name of the JFR event class. Non-null. `category` — JFR event category string (e.g., "HomeSynapse.Device"). Non-null. Phase 3 validates field types at registration time. Throws `IllegalStateException` if already registered. Throws `IllegalArgumentException` if the category is empty.

`isRegistered(String eventClassName)` — Check if a JFR event class is registered. Non-null parameter. Thread-safe.

`registeredEventClasses()` — Returns an unmodifiable set of all registered JFR event class names. Thread-safe.

`@see MetricSnapshot`

#### Step 17: `MetricsStreamBridge.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/MetricsStreamBridge.java`

```java
import java.util.function.Consumer;

public interface MetricsStreamBridge {
    void start();
    void stop();
    List<MetricSnapshot> getLatestSnapshot();
    void subscribe(Consumer<List<MetricSnapshot>> consumer);
    void unsubscribe(Consumer<List<MetricSnapshot>> consumer);
}
```

Javadoc: Bridges JFR continuous recording to real-time metric consumers via the RecordingStream API (JEP 349). Doc 11 §3.2, §8.1–§8.2. Reads JFR events from the disk repository with ~1–2 second latency from `event.commit()` to `onEvent()` callback. Pre-aggregates values into per-metric `MetricSnapshot` instances (min, max, count, sum per flush window). Pushes aggregated snapshots to subscribers on each `onFlush()` callback (~1 per second). Bounded internal queue (capacity: 60 snapshots, decision D-10). Drops oldest snapshot on overflow and increments a dropped counter.

`start()` — Begin streaming from the JFR recording. Registers `onEvent()` and `onFlush()` callbacks. Must be called after JFR recording has started. Thread-safe.

`stop()` — Stop streaming. Releases RecordingStream resources. Thread-safe.

`getLatestSnapshot()` — Return the most recent aggregated metric snapshot batch (for REST API polling). Doc 11 §8.2. Returns a list of `MetricSnapshot` instances — one per metric — representing the most recent flush window. Returns an empty list if no snapshots have been produced yet. Non-null return.

`subscribe(Consumer<List<MetricSnapshot>> consumer)` — Register a push consumer for real-time metric snapshots. Consumers receive a batch of MetricSnapshot instances (one per metric) on each flush window. `consumer` — non-null. Thread-safe.

`unsubscribe(Consumer<List<MetricSnapshot>> consumer)` — Remove a previously registered push consumer. `consumer` — non-null. Thread-safe.

`@see MetricSnapshot`
`@see MetricsRegistry`

#### Step 18: `LogLevelController.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/LogLevelController.java`

```java
public interface LogLevelController {
    String getLevel(String loggerName);
    void setLevel(String loggerName, String level);
    void resetLevel(String loggerName);
    List<LogLevelOverride> listOverrides();
}
```

Javadoc: Runtime log level adjustment interface for per-package SLF4J/Logback log levels. Doc 11 §3.6, §8.1–§8.2, decision D-11. Changes take effect immediately via Logback's `LoggerContext` API — no restart, no config file reload. Dynamic adjustments persist only until the next restart (they are not written to configuration files). Exposed through the REST API (Doc 09) as an authenticated endpoint. Inspired by openHAB's Karaf console `log:set` capability, but GUI-accessible.

`getLevel(String loggerName)` — Return the current effective log level for a logger. `loggerName` — fully-qualified logger name (e.g., "com.homesynapse.integration.zigbee"). Non-null. Returns the effective level as an SLF4J level string: "TRACE", "DEBUG", "INFO", "WARN", "ERROR". Non-null return.

`setLevel(String loggerName, String level)` — Set the effective log level for a logger. `loggerName` — must match a configured allowed prefix (default: "com.homesynapse.*", configurable via `observability.logging.dynamic_level_allowed_prefixes`). Throws `IllegalArgumentException` if the logger name does not match any allowed prefix. `level` — SLF4J level name. Throws `IllegalArgumentException` if not a valid level. Thread-safe. Produces a structured log entry and a `LogLevelChangeEvent` JFR event (Doc 11 §11.2).

`resetLevel(String loggerName)` — Restore a logger to its configured default level. `loggerName` — non-null. If no override is active for this logger, this is a no-op. Thread-safe.

`listOverrides()` — Return all currently active dynamic log level overrides. Returns an unmodifiable list. Thread-safe.

`@see LogLevelOverride`

### Group 6: Module Infrastructure (2 files)

#### Step 19: `module-info.java`

Location: `observability/observability/src/main/java/module-info.java`

```java
/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Observability and debugging infrastructure for HomeSynapse Core.
 *
 * <p>This module provides system-wide health aggregation (composing per-subsystem
 * health indicators into an actionable tiered model), causal chain trace queries
 * (making the correlation_id/causation_id metadata navigable), JFR metrics
 * infrastructure (continuous recording, custom event registry, streaming bridge),
 * and dynamic log level control.</p>
 *
 * @see com.homesynapse.observability
 */
module com.homesynapse.observability {
    requires transitive com.homesynapse.event;

    exports com.homesynapse.observability;
}
```

JPMS analysis: `requires transitive com.homesynapse.event` because `Ulid` and `EntityId` (from platform-api, transitively through event-model) appear in `TraceQueryService` method signatures. The transitive declaration means consumers of this module automatically get access to platform-api identity types and event-model types.

No `requires` for state-store — Phase 3 will add `requires com.homesynapse.state` (non-transitive) when the implementation imports StateQueryService.

No `requires` for integration-runtime — Phase 3 will add it when the implementation imports IntegrationSupervisor.allHealth().

#### Step 20: Update `package-info.java`

Location: `observability/observability/src/main/java/com/homesynapse/observability/package-info.java`

Replace the existing stub with comprehensive package-level Javadoc:

```java
/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Observability and debugging infrastructure for HomeSynapse Core.
 *
 * <p>This package provides four capabilities:</p>
 *
 * <ul>
 *   <li><strong>Health Aggregation</strong> — {@link HealthAggregator} composes
 *       per-subsystem health indicators (reported via {@link HealthContributor})
 *       into a tiered {@link SystemHealth} model with three tiers
 *       ({@link HealthTier}) and three lifecycle states ({@link LifecycleState}).
 *       Health evaluation is deterministic, reactive, and O(10).</li>
 *   <li><strong>Trace Queries</strong> — {@link TraceQueryService} makes the
 *       causal chain metadata (correlation_id, causation_id) on every event
 *       envelope navigable. Returns {@link TraceChain} results with hierarchical
 *       {@link TraceNode} tree structures and {@link TraceCompleteness}
 *       status.</li>
 *   <li><strong>JFR Metrics Infrastructure</strong> — {@link MetricsRegistry}
 *       manages custom JFR event type registration. {@link MetricsStreamBridge}
 *       reads JFR events via RecordingStream and produces aggregated
 *       {@link MetricSnapshot} instances for real-time consumer push.</li>
 *   <li><strong>Dynamic Log Level Control</strong> — {@link LogLevelController}
 *       adjusts per-package SLF4J/Logback log levels at runtime without restart.
 *       Active overrides are tracked as {@link LogLevelOverride} records.</li>
 * </ul>
 *
 * <p>Design authority: Doc 11 — Observability &amp; Debugging (Locked).</p>
 *
 * @see HealthAggregator
 * @see TraceQueryService
 * @see MetricsStreamBridge
 * @see LogLevelController
 */
package com.homesynapse.observability;
```

### Group 7: Compile Gate

Run `./gradlew :observability:observability:compileJava` from the repository root. Must pass with zero errors and zero warnings (`-Xlint:all -Werror`).

**Common pitfalls:**
- Missing imports: `java.time.Instant`, `java.time.Duration`, `java.util.Optional`, `java.util.List`, `java.util.Map`, `java.util.Set`, `java.util.Objects`, `java.util.function.Consumer`
- `Ulid` import: `com.homesynapse.platform.identity.Ulid` — available transitively through `com.homesynapse.event` → `com.homesynapse.platform`
- `EntityId` import: `com.homesynapse.platform.identity.EntityId` — same transitive path
- `Map.copyOf()` on `SystemHealth.tiers` — the map keys are enum values (`HealthTier`), which is fine for `Map.copyOf()`
- Package-info Javadoc uses `{@link}` references — ensure all referenced types exist before compiling
- `TraceCompleteness` nested records: the `permits` clause uses the outer class qualifier (`TraceCompleteness.Complete`). The records themselves use simple names in their declarations.

Then run the full project compile gate: `./gradlew compileJava` to verify no regressions.

---

## Constraints

1. **Java 21** — use records, sealed interfaces, pattern matching as appropriate
2. **`-Xlint:all -Werror`** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies** — only types from existing modules (platform-api, event-model via transitive)
5. **Javadoc on every public type, method, and constructor** — including record components via `@param`
6. **All types go in `com.homesynapse.observability` package** — single flat package
7. **Do NOT create implementations** — interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files in other modules** — all work is new file creation in the observability module plus updating existing package-info.java
10. **No `@Nullable` annotations** — use Javadoc `{@code null} if...` patterns per project convention
11. **All ZCL/identity numeric identifiers use `int`** — no `short` or `byte` (project convention from Block P)

---

## Execution Order

1. `HealthStatus.java` (enum)
2. `HealthTier.java` (enum)
3. `LifecycleState.java` (enum)
4. `SubsystemHealth.java` (record — depends on HealthTier, HealthStatus)
5. `TierHealth.java` (record — depends on HealthTier, HealthStatus)
6. `SystemHealth.java` (record — depends on HealthStatus, LifecycleState, HealthTier, TierHealth, SubsystemHealth)
7. `TraceCompleteness.java` (sealed interface + 3 permitted records)
8. `TraceEvent.java` (record)
9. `TraceNode.java` (record — depends on TraceEvent)
10. `TraceChain.java` (record — depends on TraceEvent, TraceNode, TraceCompleteness)
11. `MetricSnapshot.java` (record)
12. `LogLevelOverride.java` (record)
13. `HealthContributor.java` (interface — depends on HealthStatus)
14. `HealthAggregator.java` (interface — depends on SystemHealth, SubsystemHealth, HealthStatus)
15. `TraceQueryService.java` (interface — depends on TraceChain, Ulid, EntityId)
16. `MetricsRegistry.java` (interface)
17. `MetricsStreamBridge.java` (interface — depends on MetricSnapshot)
18. `LogLevelController.java` (interface — depends on LogLevelOverride)
19. `module-info.java`
20. `package-info.java` (update existing)
21. Compile gate: `:observability:observability:compileJava`
22. Full project compile gate: `compileJava`

---

## Summary of New Files

| # | File | Kind | Key Details |
|---|------|------|-------------|
| 1 | HealthStatus | enum (3) | HEALTHY, DEGRADED, UNHEALTHY |
| 2 | HealthTier | enum (3) | CRITICAL_INFRASTRUCTURE, CORE_SERVICES, INTERFACE_SERVICES |
| 3 | LifecycleState | enum (3) | STARTING, RUNNING, SHUTTING_DOWN |
| 4 | SubsystemHealth | record (6) | subsystemId, tier, status, since, reason, inGracePeriod |
| 5 | TierHealth | record (4) | tier, status, degradedSubsystems, unhealthySubsystems |
| 6 | SystemHealth | record (6) | status, lifecycle, since, reason, tiers, subsystems |
| 7 | TraceCompleteness | sealed interface (3 permits) | Complete, InProgress, PossiblyIncomplete |
| 8 | TraceEvent | record (8) | eventId through payload; causationId nullable |
| 9 | TraceNode | record (2) | event, children |
| 10 | TraceChain | record (7) | correlationId through lastTimestamp |
| 11 | MetricSnapshot | record (7) | metricName, min, max, count, sum, windowStart, windowEnd |
| 12 | LogLevelOverride | record (4) | loggerName, originalLevel, overrideLevel, appliedAt |
| 13 | HealthContributor | interface (2 methods) | reportHealth, getSubsystemId |
| 14 | HealthAggregator | interface (4 methods) | getSystemHealth, getSubsystemHealth, onHealthChange, getHealthHistory |
| 15 | TraceQueryService | interface (5 methods) | getChain, findRecentChain, findChains, findChainsByType, findChainsByTimeRange |
| 16 | MetricsRegistry | interface (3 methods) | register, isRegistered, registeredEventClasses |
| 17 | MetricsStreamBridge | interface (5 methods) | start, stop, getLatestSnapshot, subscribe, unsubscribe |
| 18 | LogLevelController | interface (4 methods) | getLevel, setLevel, resetLevel, listOverrides |
| 19 | module-info.java | module descriptor | requires transitive com.homesynapse.event |
| 20 | package-info.java | package Javadoc | (update existing) |

**Total: 18 new files + 1 updated file + module-info.java = 20 files**

---

## What to Watch Out For

1. **TraceEvent.causationId is nullable.** This is the only nullable String field in the trace model. Root events have `causationId == null`. Do NOT add a `Objects.requireNonNull` for this field in the compact constructor. Document explicitly with `{@code null} for the root event of the chain`.

2. **TraceEvent.payload uses `Map<String, Object>`.** `Map.copyOf()` does NOT accept null values. The Phase 2 contract states: all values present or key absent (no null values in the map). If Phase 3 discovers this is too restrictive, the record must be updated then.

3. **HealthAggregator.getHealthHistory() is the only method reading historical data.** All other HealthAggregator methods return current state. getHealthHistory reads from the health transition log. The return type is `List<SubsystemHealth>` — each entry represents a state transition, not a point-in-time health state. Phase 3 determines whether this reads from JFR events or structured logs.

4. **MetricsStreamBridge operates on batches, not individual snapshots.** Both `getLatestSnapshot()` and `subscribe()` return/deliver `List<MetricSnapshot>` — a batch of snapshots (one per metric) per flush window. The consumer receives the whole batch, not individual metric snapshots.

5. **TraceQueryService uses `Ulid` for correlationId, not `String`.** Even though TraceEvent uses String identifiers (query-layer representation), the TraceQueryService accepts typed `Ulid` for the correlationId parameter because the caller has access to the typed form. The service internally converts to the query form.

6. **No integration-runtime dependency in Phase 2.** The observability module's HealthAggregator receives health from all subsystems via `HealthContributor.reportHealth()` — it does NOT call `IntegrationSupervisor.allHealth()` directly. The allHealth() call is a convenience for the REST API, not for the aggregator. The aggregator's data path is: subsystem → HealthContributor → HealthAggregator.onHealthChange().

7. **`HealthTier` is an enum in this module, not a String constant.** The tier assignment (which subsystem is in which tier) is a configuration/Phase 3 concern. The enum defines the three tiers; the mapping of subsystem IDs to tiers is implementation logic.

8. **SystemHealth.tiers key type is `HealthTier` (enum), SystemHealth.subsystems key type is `String`.** These are different key types intentionally — tiers are a fixed enum, subsystem IDs are strings.

---

## Context Delta (post-completion)

**Files created:**
- {Coder fills in after compile gate}

**Decisions made during execution:**
- {Coder fills in any deviations or clarifications}

**What the next block needs to know:**
- {Anything that affects Block R (lifecycle), Block S (app assembly), or Block T (test support)}
