# lifecycle — `com.homesynapse.lifecycle` — Scaffold — 10-phase sequential startup, 30-second shutdown budget, fatal vs non-fatal classification

## Purpose

The lifecycle module defines process-level lifecycle orchestration for HomeSynapse Core. It provides five capabilities: lifecycle state machine (ten sequential phases from BOOTSTRAP through STOPPED), initialization orchestration (sequencing all subsystems through their startup phases with fatal/non-fatal failure classification), shutdown sequencing (reverse initialization order with 30-second grace budget), health loop and watchdog (polling HealthContributors every 30 seconds, computing aggregated health, feeding systemd watchdog), and platform abstraction (HealthReporter for systemd sd_notify, PlatformPaths for FHS directory conventions — both consumed from platform-api, not redefined here).

## Design Doc Reference

**Doc 12 — Startup, Lifecycle & Shutdown** is the governing design document:
- §3–§3.9: Lifecycle phases (ten sequential states)
- §4: Subsystem initialization ordering and fatal/non-fatal classification
- §5: Shutdown sequencing and grace periods
- §6: Health loop and watchdog protocol
- §8: Key interfaces (SystemLifecycleManager)

## JPMS Module

```
module com.homesynapse.lifecycle {
    requires transitive com.homesynapse.observability;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.platform;

    exports com.homesynapse.lifecycle;
}
```

- `requires transitive com.homesynapse.observability` — HealthStatus (from observability) appears in SubsystemState and SystemHealthSnapshot exported API. Must be transitive so consumers see the HealthStatus type.
- `requires transitive com.homesynapse.event` — LD#10 default rule. No event-model types in current exported API, but the default is transitive unless confirmed safe to downgrade.
- `requires transitive com.homesynapse.platform` — LD#10 default rule. PlatformPaths and HealthReporter (from platform-api) are referenced in Javadoc @see tags on SystemLifecycleManager. Platform identity types (Ulid, EntityId) are available transitively through event-model→platform-api chain.

## Gradle Dependencies

```kotlin
dependencies {
    api(project(":observability:observability"))
    api(project(":core:event-model"))
    api(project(":platform:platform-api"))
    implementation(project(":config:configuration"))
}
```

- `api` for observability, event-model, platform-api — matches `requires transitive` in module-info.
- `implementation` for configuration — Phase 3 internal dependency only (SystemLifecycleManager impl reads config for timeouts, grace periods). Not in exported API.

## Package Structure

- **`com.homesynapse.lifecycle`** — All types live in a single flat package. Contains: 2 enums (phase and status FSMs), 1 utility class (event type constants), 2 data records (subsystem state and health snapshot), 1 service interface (lifecycle manager).

## Complete Type Inventory

### Enums

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `LifecyclePhase` | enum (10) | Sequential lifecycle states for the system process | Values: BOOTSTRAP, FOUNDATION, DATA_INFRASTRUCTURE, CORE_DOMAIN, OBSERVABILITY, EXTERNAL_INTERFACES, INTEGRATIONS, RUNNING, SHUTTING_DOWN, STOPPED. No backward transitions. Doc 12 §3–§3.9. |
| `SubsystemStatus` | enum (6) | Individual subsystem initialization state | Values: NOT_STARTED, INITIALIZING, RUNNING, FAILED, STOPPING, STOPPED. Transitions: NOT_STARTED→INITIALIZING→RUNNING or FAILED→STOPPING→STOPPED. |

### Utility Class

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `LifecycleEventType` | final class (7 constants) | Event type string constants for lifecycle domain events | Constants: SYSTEM_STARTING, SYSTEM_SUBSYSTEM_INITIALIZED, SYSTEM_SUBSYSTEM_FAILED, SYSTEM_READY, SYSTEM_HEALTH_CHANGED, SYSTEM_STOPPING, SYSTEM_STOPPED. All in `system.*` namespace. Private constructor prevents instantiation. |

### Data Records

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `SubsystemState` | record (6 fields) | Individual subsystem initialization snapshot | Fields: `subsystemName` (String), `phase` (LifecyclePhase), `status` (SubsystemStatus), `healthState` (HealthStatus, **nullable**), `initializationDuration` (Duration, **nullable**), `error` (String, **nullable with conditional validation**). Imports `com.homesynapse.observability.HealthStatus`. Compact constructor: requireNonNull on first 3; FAILED requires non-null error; RUNNING/STOPPED requires null error. |
| `SystemHealthSnapshot` | record (8 fields) | System-wide health and operational metrics snapshot | Fields: `timestamp` (Instant), `subsystemStates` (Map\<String, SubsystemState\>), `aggregatedHealth` (HealthStatus), `uptime` (Duration, **nullable** — null before RUNNING), `eventStorePosition` (long), `entityCount` (int), `integrationCount` (int), `automationCount` (int). Defensive copy: `Map.copyOf(subsystemStates)`. |

### Service Interfaces

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `SystemLifecycleManager` | interface (5 methods) | Top-level process lifecycle orchestration | Methods: `start()`, `shutdown(String reason)`, `currentPhase()`, `healthSnapshot()`, `subsystemStates()`. Thread-safety: query methods callable from any thread, shutdown synchronized internally. @see HealthReporter, PlatformPaths (both in com.homesynapse.platform). |

## Dependencies

| Module | Relationship | Why |
|---|---|---|
| `com.homesynapse.observability` | `requires transitive` (api) | `HealthStatus` appears in SubsystemState.healthState and SystemHealthSnapshot.aggregatedHealth — both exported API fields |
| `com.homesynapse.event` | `requires transitive` (api) | LD#10 default rule |
| `com.homesynapse.platform` | `requires transitive` (api) | LD#10 default rule. PlatformPaths and HealthReporter referenced in @see tags. |
| `com.homesynapse.config` | `implementation` only | Phase 3: SystemLifecycleManager reads config for timeouts, grace periods |

## Consumers

| Module | What It Consumes | How |
|---|---|---|
| `homesynapse-app` (Doc 14) | `SystemLifecycleManager` | App main() calls start(), registers shutdown hook for shutdown() |
| `rest-api` (Doc 09) | `SystemHealthSnapshot`, `LifecyclePhase` | Health endpoint, lifecycle status endpoint |
| `websocket-api` (Doc 10) | `LifecyclePhase` | Graceful shutdown notification to connected clients |

## Constraints

- **INV-TO-01:** System behavior is observable — lifecycle state transitions produce events and health updates
- **LD#10 (JPMS default rule):** All inter-module `requires` default to `requires transitive`
- **LTD-15:** SLF4J for all logging
- **Doc 12 §4:** Fatal subsystem failures (Configuration, Persistence, Event Bus, Device Model, State Store, Automation, REST API) exit the process. Non-fatal failures (Observability, WebSocket, integrations) degrade to DEGRADED and continue.
- **Doc 12 §5:** Shutdown budget 30 seconds (half of systemd TimeoutStopSec=90). Reverse initialization order.
- **Doc 12 §6:** Health loop polls every 30 seconds during RUNNING state. Watchdog via HealthReporter.reportWatchdog().

## Cross-Module Contracts

- **HealthStatus consumption:** SubsystemState and SystemHealthSnapshot use `com.homesynapse.observability.HealthStatus` directly — they do NOT redefine a lifecycle-local health enum.
- **HealthReporter / PlatformPaths:** Defined in `com.homesynapse.platform` (platform-api module, Block F). The lifecycle module CONSUMES these interfaces — it does NOT redefine them. SystemLifecycleManager's Javadoc @see references point to `com.homesynapse.platform.HealthReporter` and `com.homesynapse.platform.PlatformPaths`.
- **LifecycleEventType → EventPublisher:** Phase 3 implementation will publish events using these type constants through EventPublisher. Phase 2 only defines the constants.

## Gotchas

1. **HealthReporter and PlatformPaths live in platform-api, NOT lifecycle.** The Block R handoff review caught an early draft that tried to create them here. They were already produced in Block F. The lifecycle module consumes them.
2. **SubsystemState.healthState is nullable** — null before Phase 4 (CORE_DOMAIN) when HealthAggregator hasn't started. This is the second nullable non-collection record field in the project (first was TraceEvent.causationId).
3. **SubsystemState has conditional validation** — FAILED requires non-null error; RUNNING/STOPPED requires null error. This is the first record in the project with status-dependent null constraints.
4. **SystemHealthSnapshot.uptime is nullable** — null before RUNNING state. Callers must null-check.
5. **LifecycleEventType is a final utility class with private constructor**, not an enum. This matches the EventTypes pattern from event-model.
6. **The package-info.java @link references use `com.homesynapse.platform.*`**, not `com.homesynapse.lifecycle.*`. The handoff had incorrect FQNs; this was fixed during execution (INFO-level deviation).
7. **No backward phase transitions.** LifecyclePhase is a strict forward FSM. Phase 3 enforcement will likely use ordinal() comparison.

## Phase 3 Notes

- SystemLifecycleManager implementation sequences subsystem init in documented order (Doc 12 §4)
- Fatal vs non-fatal failure classification determines process exit vs DEGRADED state
- Shutdown coordinator implements reverse-order shutdown with per-subsystem grace periods
- Health loop implementation polls HealthContributors every 30 seconds
- Watchdog feeds systemd via HealthReporter.reportWatchdog()
- Configuration dependency provides timeout values, grace periods, watchdog intervals


---

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2). Phase 3 implementation is active — M2.5 `SqliteEventStore` landed 2026-04-11 (commit `5279e7a`), next milestone M2.6 + M2.7 (combined) pending from Nick.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-02** — *Persistence uses platform threads*: `PersistenceLifecycle.start()` wires `SqliteEventStore` + `DatabaseExecutor` — composition happens here or in `app/homesynapse-app`
- **D-03** — *Persistence internals are package-private*: do not `requires com.homesynapse.persistence` for internal types; compose via the exported interfaces only
- **D-04** — *Clock must be injected*: startup-timeout tracking, grace-period countdowns, and `SubsystemState` transition timestamps all take `Clock`

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for recent Phase 3 pattern discoveries (especially the 2026-04-10 entries on `NO_DIRECT_TIME_ACCESS` and JUnit 5 `@BeforeEach` ordering).
