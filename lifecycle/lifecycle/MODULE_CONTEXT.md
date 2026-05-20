# lifecycle — `com.homesynapse.lifecycle` — 7 public + 2 package-private types (M3.6d-a) — Process-level startup/shutdown orchestration AND M3.6 composition-root primitives (HomeSynapseConfig, SharedScheduler, ThrowingStateQueryService)

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

    // M3.6d-a — composition-root prerequisites
    requires transitive com.homesynapse.persistence;
    requires transitive com.homesynapse.event.bus;
    requires transitive com.homesynapse.state;

    // M3.6d-a build-fix — SLF4J for internal logging (SharedScheduler)
    requires org.slf4j;

    exports com.homesynapse.lifecycle;
}
```

- `requires transitive com.homesynapse.observability` — HealthStatus (from observability) appears in SubsystemState and SystemHealthSnapshot exported API. Must be transitive so consumers see the HealthStatus type.
- `requires transitive com.homesynapse.event` — LD#10 default rule. No event-model types in current exported API, but the default is transitive unless confirmed safe to downgrade.
- `requires transitive com.homesynapse.platform` — LD#10 default rule. PlatformPaths and HealthReporter (from platform-api) are referenced in Javadoc @see tags on SystemLifecycleManager. Platform identity types (Ulid, EntityId) are available transitively through event-model→platform-api chain.
- `requires transitive com.homesynapse.persistence` (M3.6d-a) — `HomeSynapseConfig.persistence()` returns `PersistenceConfig`, an exported field accessor. LD#10 default.
- `requires transitive com.homesynapse.event.bus` (M3.6d-a) — `HomeSynapseConfig.eventBus()` returns `EventBusConfig`; `SharedScheduler` consumes `DerivedWriteRateLimit` and `QueueSaturationHealthCheck`. LD#10 default.
- `requires transitive com.homesynapse.state` (M3.6d-a) — `ThrowingStateQueryService` implements `StateQueryService`. LD#10 default.
- `requires org.slf4j` (M3.6d-a build-fix) — **non-transitive** (LTD-15 / DECIDE-01). `SharedScheduler` uses SLF4J internally for ERROR logging from `safelyInvoke()`; the matching Gradle declaration is `implementation(libs.slf4j.api)`. No SLF4J types appear in the lifecycle module's exported API, so consumers do not need SLF4J on their compile classpath through this module. Mirrors the canonical pattern in `core/persistence/module-info.java` (M2.2) and `core/state-store/module-info.java` (M3.5a). Failing to add this directive produces the build error "package org.slf4j does not exist" at compile time — the persistence module's transitive deps did NOT pull SLF4J into the lifecycle module's classpath because persistence declares SLF4J at `implementation` scope, which the java-library plugin does not propagate to consumers.

## Gradle Dependencies

```kotlin
dependencies {
    api(project(":observability:observability"))
    api(project(":core:event-model"))
    api(project(":platform:platform-api"))
    implementation(project(":config:configuration"))

    // M3.6d-a — composition-root prerequisites
    api(project(":core:persistence"))
    api(project(":core:event-bus"))
    api(project(":core:state-store"))

    // M3.6d-a build-fix — SLF4J for SharedScheduler.safelyInvoke()
    implementation(libs.slf4j.api)

    testImplementation(project(":testing:test-support"))
}
```

- `api` for observability, event-model, platform-api — matches `requires transitive` in module-info.
- `implementation` for configuration — Phase 3 internal dependency only (SystemLifecycleManager impl reads config for timeouts, grace periods). Not in exported API.
- `api` for persistence, event-bus, state-store (M3.6d-a) — matches `requires transitive` in module-info. The new lifecycle types depend on `PersistenceConfig`, `EventBusConfig`, `DerivedWriteRateLimit`, `QueueSaturationHealthCheck`, and `StateQueryService` from these modules, surfaced through either exported records (`HomeSynapseConfig`) or package-private collaborators (`SharedScheduler`, `ThrowingStateQueryService`).
- `implementation(libs.slf4j.api)` (M3.6d-a build-fix) — matches the non-transitive `requires org.slf4j` in module-info. `implementation` scope keeps SLF4J off consumers' compile classpath, which is correct because no SLF4J types appear in the lifecycle module's exported API. Canonical pattern from `core/persistence/build.gradle.kts:14` and `core/state-store/build.gradle.kts:12`.
- `testImplementation` for test-support — `SharedSchedulerTest` does not currently use it but the dependency is declared for future composition-root tests that need TestClock or NoRealIoExtension.

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

### M3.6 Composition-Root Primitives (M3.6d-a)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `HomeSynapseConfig` | public record (2 fields) | Consolidated configuration carrier for `HomeSynapseCore` (M3.6d-b). | Fields: `persistence` (`PersistenceConfig`, non-null), `eventBus` (`EventBusConfig`, non-null). Compact constructor enforces non-null. Constant: `HOME_DEFAULT = new HomeSynapseConfig(PersistenceConfig.HOME_DEFAULT, EventBusConfig.HOME_DEFAULT)`. YAML loading is deferred to a later WU per Doc 06 §14 — M3.6d uses programmatic construction. Extensible: future subsystem configs are added as new record components alongside the existing `*_DEFAULT` constants. |
| `SharedScheduler` | package-private final class | Single-threaded scheduler driving the two periodic maintenance tasks shared by `HomeSynapseCore`: token-bucket refill (50 ms cadence) and queue-saturation tick (1 s cadence). | Production constructor: `SharedScheduler(DerivedWriteRateLimit, QueueSaturationHealthCheck)` — wraps `rateLimit::refill` and `healthCheck::tick` as the two scheduled tasks and delegates to the test-friendly form. Test-friendly constructor: `SharedScheduler(Runnable refillTask, Runnable tickTask)` — used by `SharedSchedulerTest` because the two collaborators are `final` and cannot be mocked. Method `shutdown()` calls `executor.shutdownNow()` and `awaitTermination(2000ms)`; idempotent. Uses `Executors.newSingleThreadScheduledExecutor` with a daemon thread named `"hs-sched-0"` (daemon status is defence against composition-root bugs that forget to call `shutdown()`). Tasks are wrapped in `safelyInvoke` so a thrown `RuntimeException` is logged but does NOT cancel the executor's task — `ScheduledExecutorService` cancels future executions of a throwing task by default; the wrapper preserves cadence across transient faults. Constants: `REFILL_PERIOD_MILLIS = 50`, `TICK_PERIOD_MILLIS = 1000`. |
| `ThrowingStateQueryService` | package-private final class implementing `StateQueryService` | Placeholder for `HomeSynapseCore.stateQueryService()` (M3.6d-b) — throws `IllegalStateException` on every method call. Replaced by `MaterializedStateQueryService` in M3.6e. | All 5 `StateQueryService` methods throw with message `NOT_WIRED_MESSAGE = "StateQueryService not yet wired — available after M3.6e"`. The constant is package-visible so `HomeSynapseCoreTest` (landing M3.6d-b) can assert the error path. Avoids `null` returns at the composition-root accessor so accidental pre-M3.6e callsites surface a clear failure instead of a downstream NPE. |

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

### M3.6d-a deliverables (2026-05-20)

- **`HomeSynapseConfig`** — public record bundling `PersistenceConfig` and `EventBusConfig`. `HOME_DEFAULT` reproduces the prior implicit defaults exactly. Consumed by `HomeSynapseCore` (M3.6d-b).
- **`SharedScheduler`** — package-private single-threaded scheduler. Drives `DerivedWriteRateLimit.refill()` every 50 ms and `QueueSaturationHealthCheck.tick()` every 1 s on a daemon platform thread named `"hs-sched-0"`. 5 unit tests in `SharedSchedulerTest` (cadence × 2, shutdown × 2, throwing-task survival × 1).
- **`ThrowingStateQueryService`** — package-private placeholder. `HomeSynapseCore.stateQueryService()` (M3.6d-b) will return one; M3.6e replaces it with `MaterializedStateQueryService`.
- **Module dependencies added:** `requires transitive com.homesynapse.persistence`, `requires transitive com.homesynapse.event.bus`, `requires transitive com.homesynapse.state`. The transitive directives match LD#10 (default for inter-module requires) and surface the public types these three modules export (`PersistenceConfig`, `EventBusConfig`, `DerivedWriteRateLimit`, `QueueSaturationHealthCheck`, `StateQueryService`).
- **M3.6d-b deferred to the next WU.** `PersistenceFactory` (in `core/persistence`) and `HomeSynapseCore` (in this module) require additional infrastructure not yet present: `SqlitePersistenceLifecycle` must construct `SqliteStateStore` + `SqliteDeadLetterStore` + an `Include.ALWAYS`-configured `ObjectMapper` + `CheckpointSerializer`; `WriteCoordinator` needs a `queueSize()` method for the writer-queue-depth `IntSupplier`; and the composition root needs a production `SubscriberReadConnectionFactory` (the test harness's `RecordingReadConnectionFactory` is a fixture, not production). See coder-handoff.md M3.6d-a entry for the full deferral list.


---


## Phase 3 Cross-Module Context

*Updated 2026-05-17 (Post-M3.1 refresh). Phase 3 active — M3.1 `InProcessEventBus` landed 2026-05-17. Next milestone: M3.5a (StateProjection vertical slice). M3 governance: AMD-41/42/43 APPLIED. See `homesynapse-core-docs/design/HomeSynapse_Core_M3_Implementation_Plan_PLAN-M3-CONSOLIDATED-02.md` for the full M3 implementation plan.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: lifecycle events dispatched via `@EventType` registry lookup
- **AMD-42** — *Subscriber Lifecycle and Isolation*: lifecycle module will wire InProcessEventBus with subscribers during startup sequencing

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for Phase 3 pattern discoveries (including M3.1 entries on default interface methods, contract test capability hooks, and JPMS-enforced JDBC-free constraints).
