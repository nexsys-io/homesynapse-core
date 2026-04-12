# observability — `com.homesynapse.observability` — Scaffold — Health model, hierarchical aggregation, trace query, metrics, JFR integration

## Purpose

The observability module defines the diagnostic backbone for HomeSynapse Core. It provides four capabilities: health aggregation (composing per-subsystem health indicators into a three-tier system health model), trace queries (making the correlation_id/causation_id causal chain metadata on every EventEnvelope navigable), JFR metrics infrastructure (custom event registration, continuous recording bridge, streaming to consumers), and dynamic log level control (runtime SLF4J/Logback level adjustment without restart). Every other subsystem's §11 (observability contracts) reports into the interfaces defined here. This module is a pure consumer of event-model and platform-api types — it defines no domain events and publishes no events.

## Design Doc Reference

**Doc 11 — Observability & Debugging** is the governing design document:
- §3: Architecture (health aggregation §3.3, JFR metrics §3.2, trace queries §3.4, log control §3.6)
- §4: Data model (health model §4.1, trace model §4.2, metric model §4.3)
- §5: Behavioral contracts
- §7: Interaction patterns with other subsystems
- §8: Key interfaces (HealthAggregator, HealthContributor, TraceQueryService, MetricsRegistry, MetricsStreamBridge, LogLevelController)

## JPMS Module

```
module com.homesynapse.observability {
    requires transitive com.homesynapse.event;

    exports com.homesynapse.observability;
}
```

The `requires transitive` on event-model provides transitive access to platform-api identity types (`Ulid`, `EntityId`) which appear in `TraceQueryService` method signatures. No dependency on state-store or integration-runtime in Phase 2 — both will be added as non-transitive `requires` in Phase 3.

## Package Structure

- **`com.homesynapse.observability`** — All types live in a single flat package. Contains: health model enums and records, trace model sealed interface and records, metric and log level records, and six service interfaces.

## Complete Type Inventory

### Enums

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `HealthStatus` | enum (3) | Three-state health model for subsystems and system-wide | Values: `HEALTHY`, `DEGRADED`, `UNHEALTHY`. Doc 01's "CRITICAL" maps to UNHEALTHY (Doc 11 §7.1). Thread-safe (enum). |
| `HealthTier` | enum (3) | Tiered classification for health aggregation composition | Values: `CRITICAL_INFRASTRUCTURE` (Tier 1: Event Bus, State Store, Persistence), `CORE_SERVICES` (Tier 2: Automation, Integration Runtime, Configuration, Device Model, Observability), `INTERFACE_SERVICES` (Tier 3: REST API, WebSocket API). Doc 11 §3.3, decisions D-02/D-03. |
| `LifecycleState` | enum (3) | System-wide lifecycle state governing health behavior | Values: `STARTING`, `RUNNING`, `SHUTTING_DOWN`. One-directional FSM. During STARTING, per-subsystem grace periods apply. Doc 11 §3.3, decision D-04. |

### Health Model Records

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `SubsystemHealth` | record (6 fields) | Individual subsystem health snapshot | Fields: `subsystemId` (String), `tier` (HealthTier), `status` (HealthStatus), `since` (Instant), `reason` (String), `inGracePeriod` (boolean). All non-null. |
| `TierHealth` | record (4 fields) | Per-tier health summary | Fields: `tier` (HealthTier), `status` (HealthStatus), `degradedSubsystems` (List\<String\>), `unhealthySubsystems` (List\<String\>). Defensive copy via `List.copyOf()`. |
| `SystemHealth` | record (6 fields) | Complete system health with tiered breakdown | Fields: `status` (HealthStatus), `lifecycle` (LifecycleState), `since` (Instant), `reason` (String), `tiers` (Map\<HealthTier, TierHealth\>), `subsystems` (Map\<String, SubsystemHealth\>). Defensive copy via `Map.copyOf()`. Top-level type returned by `HealthAggregator.getSystemHealth()`. |

### Trace Model

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `TraceCompleteness` | sealed interface (3 permits) | Completeness status of causal chain query results | Permits: `Complete(String terminalEventType)`, `InProgress(Instant lastEventTime, Duration elapsed)`, `PossiblyIncomplete(String reason)`. All permits are records. Doc 11 §3.4, §4.2, decision D-14. |
| `TraceEvent` | record (8 fields) | Single event in a causal chain | Fields: `eventId` (String), `eventType` (String), `entityId` (String), `correlationId` (String), `causationId` (String, **nullable** — null for root event), `eventTime` (Instant), `ingestTime` (Instant), `payload` (Map\<String, Object\>). Uses String identifiers (query-layer representation), not typed ULID wrappers. `Map.copyOf()` on payload. |
| `TraceNode` | record (2 fields) | Tree node wrapping TraceEvent with children | Fields: `event` (TraceEvent), `children` (List\<TraceNode\>). Children ordered by eventTime ascending. `List.copyOf()` on children. |
| `TraceChain` | record (7 fields) | Complete causal chain for a correlation_id | Fields: `correlationId` (String), `rootEvent` (TraceEvent), `orderedEvents` (List\<TraceEvent\>), `tree` (TraceNode), `completeness` (TraceCompleteness), `firstTimestamp` (Instant), `lastTimestamp` (Instant). Primary data structure from TraceQueryService. Validates orderedEvents non-empty. |

### Utility Records

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `MetricSnapshot` | record (7 fields) | Aggregated metric values for one JFR flush window | Fields: `metricName` (String), `min` (double), `max` (double), `count` (long, validated >= 0), `sum` (double), `windowStart` (Instant), `windowEnd` (Instant). Produced by MetricsStreamBridge pre-aggregation. |
| `LogLevelOverride` | record (4 fields) | Active dynamic log level override | Fields: `loggerName` (String), `originalLevel` (String), `overrideLevel` (String), `appliedAt` (Instant). Level strings use SLF4J names: "TRACE", "DEBUG", "INFO", "WARN", "ERROR". |

### Service Interfaces

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `HealthContributor` | interface (2 methods) | Per-subsystem health reporting callback | Methods: `reportHealth(HealthStatus, String)`, `getSubsystemId()`. Each subsystem receives its own instance from HealthAggregator during initialization. Thread-safe. |
| `HealthAggregator` | interface (4 methods) | Tiered health composition engine | Methods: `getSystemHealth()`, `getSubsystemHealth(String)`, `onHealthChange(String, HealthStatus, String)`, `getHealthHistory(Instant, Instant)`. Reactive evaluation (not polling). Deterministic (INV-TO-02). O(10) with no I/O. |
| `TraceQueryService` | interface (5 methods) | Causal chain query engine | Methods: `getChain(Ulid)`, `findRecentChain(EntityId)`, `findChains(EntityId, Instant, Instant)`, `findChainsByType(String, Instant, Instant)`, `findChainsByTimeRange(Instant, Instant, int)`. Read-only (INV-PR-02). Uses typed `Ulid`/`EntityId` parameters. |
| `MetricsRegistry` | interface (3 methods) | Custom JFR event type registration | Methods: `register(String, String)`, `isRegistered(String)`, `registeredEventClasses()`. Budget: 15–25 custom events, ceiling 50–100. Thread-safe. |
| `MetricsStreamBridge` | interface (5 methods) | JFR RecordingStream to consumer bridge | Methods: `start()`, `stop()`, `getLatestSnapshot()`, `subscribe(Consumer)`, `unsubscribe(Consumer)`. Operates on batches (List\<MetricSnapshot\>), not individual snapshots. Bounded queue: 60 snapshots. |
| `LogLevelController` | interface (4 methods) | Runtime SLF4J log level adjustment | Methods: `getLevel(String)`, `setLevel(String, String)`, `resetLevel(String)`, `listOverrides()`. Changes via Logback LoggerContext API. Runtime-only (not persisted). Restricted to allowed prefixes. |

## Dependencies

| Module | Relationship | Why |
|---|---|---|
| `com.homesynapse.event` | `requires transitive` (api) | `Ulid` and `EntityId` (via platform-api, transitive) appear in TraceQueryService method signatures |
| `com.homesynapse.platform` | transitive via event-model | Identity types (Ulid, EntityId) used in trace queries |
| `com.homesynapse.state` | Phase 3 only | StateQueryService used for trace data enrichment |
| `com.homesynapse.integration` | Phase 3 only | IntegrationSupervisor.allHealth() for integration health |

## Consumers

| Module | What It Consumes | How |
|---|---|---|
| `lifecycle` (Doc 12) | `HealthAggregator`, `SystemHealth`, `LifecycleState` | Boot sequencing uses health status to determine readiness |
| `rest-api` (Doc 09) | `HealthAggregator`, `SystemHealth`, `TraceQueryService`, `TraceChain`, `MetricsStreamBridge`, `LogLevelController` | REST endpoints for health, traces, metrics, log levels |
| `websocket-api` (Doc 10) | `MetricsStreamBridge` subscription | Real-time metric push to WebSocket clients |
| `dashboard` (Doc 13) | Via REST/WebSocket APIs | Three-tier health dashboard, trace visualization |
| All subsystems | `HealthContributor` | Each subsystem reports health via its own contributor instance |

## Constraints

- **INV-TO-01:** System behavior is observable — this module is the primary mechanism
- **INV-TO-02:** Health evaluation is deterministic and reproducible
- **INV-TO-04:** Health transitions produce JFR events and structured log entries
- **INV-ES-06:** Every state change is explainable through the causal chain — TraceQueryService satisfies this
- **INV-PR-02:** TraceQueryService is read-only
- **INV-PR-03:** Bounded overhead for always-on diagnostics
- **LTD-04:** Typed ULIDs for identity (Ulid, EntityId in TraceQueryService)
- **LTD-15:** SLF4J for all logging (LogLevelController uses SLF4J level names)
- **LD#10 (JPMS default rule):** `requires transitive` for all inter-module dependencies where types appear in exported API

## Cross-Module Contracts

- **HealthContributor → HealthAggregator:** Subsystems call `reportHealth()` on their contributor; contributor forwards to `HealthAggregator.onHealthChange()`. This is the sole data path for health updates.
- **TraceQueryService → EventStore:** Phase 3 reads from EventStore using correlation_id queries. Phase 2 API uses `Ulid` for correlationId but String identifiers in TraceEvent (query-layer representation).
- **MetricsStreamBridge → JFR:** Phase 3 bridges RecordingStream onEvent/onFlush callbacks to MetricSnapshot batches.

## Gotchas

1. **TraceEvent.causationId is the ONLY nullable String field** in the trace model. It is null for root events. Do not add `Objects.requireNonNull` for this field.
2. **TraceEvent uses String identifiers, not typed ULID wrappers.** This is deliberate — it's a query/presentation-layer model, not a domain model.
3. **MetricsStreamBridge operates on batches**, not individual snapshots. Both `getLatestSnapshot()` and `subscribe()` deal with `List<MetricSnapshot>`.
4. **SystemHealth.tiers uses enum keys (HealthTier), but SystemHealth.subsystems uses String keys.** Different key types are intentional.
5. **HealthTier defines the three tiers; the mapping of subsystem IDs to tiers is Phase 3 configuration.** The enum is the type system; the assignment is implementation.
6. **No integration-runtime dependency in Phase 2.** Health data flows through HealthContributor.reportHealth(), not through direct IntegrationSupervisor calls.
7. **`Map.copyOf()` on TraceEvent.payload does NOT accept null values.** Phase 2 contract: all values present or key absent. Phase 3 may need adjustment if nullable payload values are required.
8. **The `requires transitive com.homesynapse.event` directive was incorrectly downgraded to `requires` during a codebase audit (pre-Block R) and had to be reverted.** The audit assumed that because no event-model types appeared directly in observability's exported API, `transitive` was unnecessary. However, platform-api types (`Ulid`, `EntityId`) reach this module's consumers ONLY through the transitive chain: observability → event-model → platform-api. `TraceQueryService` uses `Ulid` and `EntityId` in its method signatures, which are exported API. The `-Xlint:all -Werror` `[exports]` warning correctly caught this. **Lesson: transitive dependency analysis must trace the FULL type graph, not just direct imports from the immediately-required module.**

## Phase 3 Notes

- Add `requires com.homesynapse.state` (non-transitive) for StateQueryService
- Add `requires com.homesynapse.integration` (non-transitive) for IntegrationSupervisor.allHealth()
- JFR custom event class definitions are Phase 3 internal types, not API-surface types
- Health state machine transition logic, flapping prevention, startup grace period tracking are all Phase 3
- Trace chain assembly SQL queries and TraceCompleteness classification are Phase 3
- MetricSnapshot pre-aggregation from JFR events and bounded queue management are Phase 3
- LogLevelController allowed prefix validation against configuration is Phase 3


---

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2). Phase 3 implementation is active — M2.5 `SqliteEventStore` landed 2026-04-11 (commit `5279e7a`), next milestone M2.6 + M2.7 (combined) pending from Nick.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: trace event emission dispatches on `@EventType`
- **D-02** — *Persistence uses platform threads*: trace storage writes go through `DatabaseExecutor`; queries via `ReadExecutor`
- **D-04** — *Clock must be injected*: `TraceEvent.timestamp` and health-probe intervals all take `Clock` — this is the module with the largest time-access surface

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for recent Phase 3 pattern discoveries (especially the 2026-04-10 entries on `NO_DIRECT_TIME_ACCESS` and JUnit 5 `@BeforeEach` ordering).
