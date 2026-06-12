# lifecycle — `com.homesynapse.lifecycle` — 8 public + 3 package-private types (M3.6d-b/M3.6e.1/M3.6e.2/M3.7/M4.0b-1) — Process-level startup/shutdown orchestration AND M3.6/M3.7 composition-root primitives (HomeSynapseConfig with M3.7 httpPort field, SharedScheduler, ThrowingStateQueryService, NotifyingEventPublisher, HomeSynapseCore — now owns the embedded Javalin HTTP server, the production StateQueryService, the entity-query endpoints, the admin endpoints, the M4.0b-1 production-rule + dispatching-advancer wiring (closes OR-M3-17/18; `MinimalProjectionAdvancer` removed), AND the M3.7 `abandon()` ungraceful-shutdown method)

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

    // M3.6d-b — HomeSynapseCore aggregates IntegrationEvents.LIFECYCLE_EVENT_CLASSES
    // (10 since M4.C/AMD-58) and CAPABILITY_EVENT_CLASSES (2, M4.C/AMD-59) for the
    // event-type registry. Non-transitive; IntegrationEvents is internal to the
    // composition root, not exposed on the public API.
    requires com.homesynapse.integration;

    // M3.6e.1 — HomeSynapseCore registers ReadinessFilter (from rest-api) as
    // the Javalin before("/api/*") gate and constructs the embedded Javalin
    // server (Jetty pool sized from DeploymentProfile). All three requires
    // are non-transitive — the types are referenced only inside the
    // composition root, not exposed on the lifecycle module's public API.
    requires com.homesynapse.api.rest;
    requires io.javalin;
    requires org.eclipse.jetty.util;

    // M3.6d-a build-fix — SLF4J for internal logging (SharedScheduler, HomeSynapseCore)
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
| `HomeSynapseConfig` | public record (4 fields, M3.7) | Consolidated configuration carrier for `HomeSynapseCore`. | Fields: `persistence` (`PersistenceConfig`, non-null), `eventBus` (`EventBusConfig`, non-null), `httpPort` (`int`, `>= 0`, M3.7 — `0` requests an ephemeral port), **`checkpointPolicy` (`CheckpointPolicy`, non-null, M3.7 — replaces the previously-hardcoded `FixedCheckpointPolicy.HOME_DEFAULT` in `HomeSynapseCore`)**. Compact constructor validates non-null + non-negative port. Constants: `HOME_DEFAULT = new HomeSynapseConfig(PersistenceConfig.HOME_DEFAULT, EventBusConfig.HOME_DEFAULT, 7070, FixedCheckpointPolicy.HOME_DEFAULT)`. Static factory: `testing()` (M3.7) pairs `DeploymentProfile.TESTING` + `RetentionPolicy.SOURCE_DEFAULT` + `EventBusConfig.HOME_DEFAULT` + port `0` + `FixedCheckpointPolicy.TESTING` (N=1) so the projection checkpoint fires after every event under `Clock.fixed()` — required for crash-recovery integration tests. YAML loading is deferred per Doc 06 §14 — M3.6d/M3.7 use programmatic construction. Extensible: future subsystem configs are added as new record components alongside the existing `*_DEFAULT` constants. |
| `SharedScheduler` | package-private final class | Single-threaded scheduler driving the two periodic maintenance tasks shared by `HomeSynapseCore`: token-bucket refill (50 ms cadence) and queue-saturation tick (1 s cadence). | Production constructor: `SharedScheduler(DerivedWriteRateLimit, QueueSaturationHealthCheck)` — wraps `rateLimit::refill` and `healthCheck::tick` as the two scheduled tasks and delegates to the test-friendly form. Test-friendly constructor: `SharedScheduler(Runnable refillTask, Runnable tickTask)` — used by `SharedSchedulerTest` because the two collaborators are `final` and cannot be mocked. Method `shutdown()` calls `executor.shutdownNow()` and `awaitTermination(2000ms)`; idempotent. Uses `Executors.newSingleThreadScheduledExecutor` with a daemon thread named `"hs-sched-0"` (daemon status is defence against composition-root bugs that forget to call `shutdown()`). Tasks are wrapped in `safelyInvoke` so a thrown `RuntimeException` is logged but does NOT cancel the executor's task — `ScheduledExecutorService` cancels future executions of a throwing task by default; the wrapper preserves cadence across transient faults. Constants: `REFILL_PERIOD_MILLIS = 50`, `TICK_PERIOD_MILLIS = 1000`. |
| `ThrowingStateQueryService` | package-private final class implementing `StateQueryService` | M3.6d-b placeholder retained per the M3.6e.1 brief ("ThrowingStateQueryService stays. Do not delete it."). **No longer returned by `HomeSynapseCore.stateQueryService()`** as of M3.6e.1 — replaced by the production `MaterializedStateQueryService` via `StateQueryService.materialized(...)`. | All 5 `StateQueryService` methods throw `IllegalStateException` with message `NOT_WIRED_MESSAGE = "StateQueryService not yet wired — available after M3.6e"`. Still useful as a stub for tests that construct `HomeSynapseCore` collaborators in isolation without needing the real query service. |

### M3.7 Composition-Root Closure (2026-05-22)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `MinimalProjectionAdvancer` | **REMOVED (M4.0b-1)** — was a package-private final class implementing `ProjectionAdvancer` (M3.7) | Closed OR-M3-18 as a forward-every-envelope placeholder. **Deleted in M4.0b-1** — superseded at the composition root by the state-store `DispatchingProjectionAdvancer` (REC-28), reached via the public factory `ProjectionAdvancer.dispatching(EventStore)`. The lifecycle module no longer owns a `ProjectionAdvancer` implementation; the production advancer now lives package-private in `com.homesynapse.state`. No other references existed (only `HomeSynapseCore` constructed it). | — |
| `NotifyingEventPublisher` | package-private final class implementing `EventPublisher` (M3.7 Finding 2) | Decorator that bridges the publish/notify gap: delegates both `publish` and `publishRoot` to the raw persistence-layer `EventPublisher`, then calls `EventBus.notifyEvent(globalPosition)` after each successful persist. Preserves INV-ES-04 — notification only fires after the delegate returns successfully; on `SequenceConflictException`, no notification is sent. | Constructor: `NotifyingEventPublisher(EventPublisher delegate, EventBus bus)` — package-private, constructed inside `HomeSynapseCore.start()` between step 5 (rate limit + advancer) and step 6 (StateProjection). The composition root passes this decorator (not the raw publisher) to `StateProjection.create()` and returns it from `HomeSynapseCore.eventPublisher()`. No module-info or build.gradle.kts changes — `com.homesynapse.lifecycle` already `requires transitive` both `com.homesynapse.event` and `com.homesynapse.event.bus`. Runs on whatever thread calls publish — no blocking I/O in the decorator itself (LTD-19 safe). |

### M3.6d-b Composition Root (2026-05-20)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `HomeSynapseCore` | public final class implementing `ReadinessSource` | Composition root for the HomeSynapse Core runtime. Owns the full lifecycle of every long-lived subsystem and wires them together in a fixed order. M3.6e.1 added the production `StateQueryService` wiring and the embedded Javalin HTTP server. M3.6e.2 added the entity query and admin endpoints. M3.7 closed OR-M3-17/18 by replacing the no-op placeholders with `MINIMAL_DERIVATION_RULE` + `MinimalProjectionAdvancer`, replaced the hardcoded `HTTP_PORT = 7070` constant with `config.httpPort()` (allowing ephemeral ports for E2E tests), and added the public accessor `boundHttpPort()`. **M3.7 fix round 1 (2026-05-23)** changed `mode()` to read the bus's authoritative per-subscriber FSM via `EventBus.subscribers()` instead of delegating to `StateProjection.currentMode()`. **M3.7 fix round 4 (2026-05-23)** wired `Subscriber.setMode(SubscriberMode)` callbacks at all CAS sites in `ReplayDriver`, `TransitionCoordinator`, and `SubscriberSupervisor`, restoring symmetry between the bus FSM and subscriber-visible mode. **M3.7 Recovery Step 2 (2026-05-26)** added `NotifyingEventPublisher` decorator (Finding 2) — bridges the publish/notify gap so `eventPublisher()` returns a decorated publisher that calls `EventBus.notifyEvent()` after every successful persist; `StateProjection.create()` now receives the decorated publisher, and `eventPublisher()` accessor returns the field instead of `persistenceFactory.eventPublisher()`. **M3.7 closeout (2026-05-27)** adds `public void abandon()` — ungraceful 4-step teardown (HTTP server → scheduler → `eventBus.abandon()` → `persistenceFactory.abandon()`) with a `volatile boolean abandoned` flag that makes `stop()` and `abandon()` mutually exclusive. Use for crash simulation in `HomeSynapseE2eHarness.abandon()` / `CrashRecoveryHttpIT`. The bus and persistence calls reach the M3.7 concrete-class `InProcessEventBus.abandon()` and `PersistenceFactory.abandon()` respectively; both skip durability operations (no WAL checkpoint, no projection checkpoint flush). | Constructors (M6.2): `HomeSynapseCore(Path dbPath, HomeSynapseConfig config, Clock clock, HomeId homeId)` — the pre-M6.2 form, now delegating with a `null` cipher (existing harnesses/tests compile unchanged) — and `HomeSynapseCore(Path, HomeSynapseConfig, Clock, HomeId, PayloadCipher payloadCipher)` — the M6.2 E2-bridge form (Doc 15 §3.8 / CARRY 1): the config-supplied `com.homesynapse.persistence.PayloadCipher` adapter (built by `Main.payloadCipher(Path, Clock)` over config's `ScopeKeyManager`) is HELD in a final nullable field Javadoc-marked for the M6.3 at-rest write path's consumption; nothing reads it in M6.2 and it is NOT yet forwarded into `PersistenceFactory` (that forwarding is the M6.3 wiring step). Nullable is the DP-6 smallest-honest-seam pin — M6.3 makes it required. No module-info change: lifecycle already `requires transitive com.homesynapse.persistence`, so the ctor-param exposure is `[exports]`-clean. Public methods: `start() → CompletableFuture<Void>` (**16-step bootstrap + step 5b added in M3.7 Recovery Step 2**; MUST be invoked from a platform thread per LTD-19 / DECIDE-M2-05 for JacksonWarmup), `stop()` (reverse-order teardown; idempotent), `eventPublisher() → EventPublisher` (returns `NotifyingEventPublisher` decorator after M3.7 Recovery Step 2), `eventStore() → EventStore`, `eventBus() → EventBus`, `stateQueryService() → StateQueryService` (returns the production `MaterializedStateQueryService` constructed via `StateQueryService.materialized(...)`; same instance on every call), `mode() → SubscriberMode` (returns `COLD` before `start()`, reads the projection subscriber's mode from `EventBus.subscribers()` afterwards — fix round 1), `boundHttpPort() → int` (M3.7 — returns Javalin's actual bound port; throws `IllegalStateException` before `start()`). Bootstrap order: persistence → bus metrics → event bus → state store + checkpoint source → rate limit + **M3.7 `MinimalProjectionAdvancer`** → **step 5b: `NotifyingEventPublisher` decorator (M3.7 Finding 2)** → state projection (now wired with `MINIMAL_DERIVATION_RULE` + `MinimalProjectionAdvancer` + decorated publisher, closing OR-M3-17/18 + Finding 2) → subscribe → health signal handler → queue saturation health check → shared scheduler → materialized state query service (M3.6e.1) → embedded Javalin HTTP server with `ReadinessFilter` registered as `before("/api/*")` bound on `config.httpPort()` (M3.7 — `0` means ephemeral) → entity query endpoints (M3.6e.2) → admin endpoints (M3.6e.2) → `app.start(config.httpPort())` → mark started. Shutdown order: httpServer.stop() (first, to refuse new queries before tearing down state) → scheduler.shutdown() → eventBus.unsubscribe("state_projection") → rateLimit.close() → persistenceFactory.close(). No teardown needed for the decorator — it holds no resources. Fields (constructed during start): `persistenceFactory`, `eventBus`, `stateProjection`, `scheduler`, `rateLimit`, `healthCheck`, `stateQueryService`, `httpServer`, `projectionAdvancer`, `eventPublisher` (M3.7 Finding 2), `started`. Constants: `PROJECTION_SUBSCRIBER_ID = "state_projection"`, `MINIMAL_DERIVATION_RULE = context -> List.of()` (M3.7 — closes OR-M3-17 as the empty-derivation path; full M4.0 successor is `DispatchingProjectionAdvancer` per Research 8 REC-28). The `HTTP_PORT = 7070` constant was removed in M3.7. **M3.7 checkpoint-fix (2026-05-27):** the checkpoint policy passed to `StateProjection.create()` is now `config.checkpointPolicy()` instead of the hardcoded `FixedCheckpointPolicy.HOME_DEFAULT` — production paths keep the HOME default through `HomeSynapseConfig.HOME_DEFAULT`, and `HomeSynapseConfig.testing()` selects the N=1 `TESTING` policy so crash-recovery integration tests flush a view checkpoint per event. |

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
9. **`HomeSynapseCore` M4.0a wiring (AMD-45).** Step 6 now passes `persistenceFactory.atomicCheckpointSink()` into `StateProjection.create(...)` (the new `AtomicCheckpointSink` param, positioned after `stateCheckpointSource()`), and step 7 registers the projection `SubscriberInfo` with the **4-arg** constructor `(PROJECTION_SUBSCRIBER_ID, SubscriptionFilter.all(), true /*coalesceExempt*/, true /*atomicCheckpoint*/)`. The `atomicCheckpoint=true` flag is what makes the bus skip its per-delivery subscriber checkpoint so the projection's coupled `AtomicCheckpointSink` write is the sole writer (AMD-45 §2.2). No new module-info/Gradle edges — `AtomicCheckpointSink` is a `com.homesynapse.state` type already reachable through `requires transitive com.homesynapse.state`. Also M4.0a (deliverable 5): the `MINIMAL_DERIVATION_RULE` class Javadoc was corrected — "state_reported → state map update" is now stated precisely (on `state_reported` the `EntityState` record is replaced and version/timestamps advance but `attributes` are untouched; only `state_changed` updates `attributes`), and the automation engine's milestone was corrected from "M5 scope" to "M7/M8 scope (M5 is the Platform API)".

8. **`HomeSynapseCore.abandon()` skips `rateLimit.close()` deliberately (M3.7).** `stop()` calls (in order) `httpServer.stop()` → `scheduler.shutdown()` → `eventBus.unsubscribe(...)` → `rateLimit.close()` → `persistenceFactory.close()`. `abandon()` calls only `httpServer.stop()` → `scheduler.shutdown()` → `eventBus.abandon()` → `persistenceFactory.abandon()`. `DerivedWriteRateLimit` is a `Semaphore`-backed token bucket with no OS-level resources (no executor, no file handles); skipping `close()` leaks nothing because the JVM will reclaim the Semaphore when the rate limit's enclosing object becomes unreachable. The `abandon()` contract is about OS-handle release for crash simulation, not about graceful in-memory cleanup. `SharedScheduler` has only `shutdown()` (no `shutdownNow()` accessor) but `shutdown()` internally invokes `executor.shutdownNow()` on the underlying `ScheduledExecutorService`, so the interrupt-running-tasks semantics the brief asked for are already satisfied.

## Phase 3 Notes

- SystemLifecycleManager implementation sequences subsystem init in documented order (Doc 12 §4)
- Fatal vs non-fatal failure classification determines process exit vs DEGRADED state
- Shutdown coordinator implements reverse-order shutdown with per-subsystem grace periods
- Health loop implementation polls HealthContributors every 30 seconds
- Watchdog feeds systemd via HealthReporter.reportWatchdog()
- **M3.6d-b complete** — `HomeSynapseCore` composition-root facade, 12-step bootstrap, reverse shutdown. `SystemLifecycleManager` merger with `HomeSynapseCore` is post-M3.6 cleanup. Two open-rocks (OR-M3-15 `DerivationRule` placeholder, OR-M3-16 `ProjectionAdvancer` placeholder) will be resolved before M3.7.
- Configuration dependency provides timeout values, grace periods, watchdog intervals

### M6.2 deliverables (2026-06-11) — PayloadCipher seam threading (Doc 15 §3.8 / CARRY 1)

- **`HomeSynapseCore` gains the nullable `PayloadCipher` seam.** New final field `payloadCipher` + a 5-arg primary constructor; the existing 4-arg constructor delegates with `null` so every existing caller (`HomeSynapseCoreTest`, `HomeSynapseE2eHarness`) compiles and stays GREEN. The field is HELD only — no `start()` step touches it; M6.3 forwards it into the persistence write path and makes it required.
- **No module-info / Gradle change** — `PayloadCipher` is a `com.homesynapse.persistence` type already reachable through `requires transitive com.homesynapse.persistence` (and the transitive directive covers the public-ctor-param exposure under `-Xlint:exports`).
- **Test:** `HomeSynapseCoreTest.constructorAcceptsPayloadCipherSeam` boots and stops the runtime with a stub cipher. The real adapter round-trip lives in app's `PayloadCipherBridgeTest` (only `app` reads both `config` and `persistence` — the zero-new-edge property).

### M4.0b-1 deliverables (2026-05-29) — production derivation rule + dispatching advancer wiring

- **`HomeSynapseCore` composition-root rewiring (Contract 3).** Step 6's `StateProjection.create(...)` derivation-rule argument changed from the removed `MINIMAL_DERIVATION_RULE` no-op constant to `DerivationRule.production()` (state-store gateway factory). Step 5's advancer construction changed from `new MinimalProjectionAdvancer(persistenceFactory.eventStore())` to `ProjectionAdvancer.dispatching(persistenceFactory.eventStore())` (state-store gateway factory). The `projectionAdvancer` field type widened from `MinimalProjectionAdvancer` to the public `ProjectionAdvancer` interface.
- **`projectionVersion` stays literal `1`** — confirmed at step 6 (no 1→2 bump; amendment-free per the Release Gate). No reconciliation/replay-from-zero fires; historical attributes are NOT backfilled (M4.0b-2, P2-blocked).
- **Removed:** the `MINIMAL_DERIVATION_RULE` constant (and its Javadoc) and the `MinimalProjectionAdvancer.java` class file (grep-confirmed: only `HomeSynapseCore` referenced them). OR-M3-17 and OR-M3-18 are now closed by real production collaborators, not placeholders.
- **Added import:** `com.homesynapse.state.ProjectionAdvancer`. **No module-info / build.gradle.kts changes** — `DerivationRule`, `ProjectionAdvancer`, and `EventStore` are all reachable through the existing `requires transitive com.homesynapse.state` / `com.homesynapse.event` edges.
- **Downstream regression check (no scope change):** `HomeSynapseCoreTest` and the `testing/integration-tests` E2E suite (`EndpointE2eIT`, `CrashRecoveryHttpIT`) remain green by construction — the production rule updates attributes of *existing* entities (it does not create new ones), so entity counts are unchanged, and the `getViewPosition() >= N` waits use `>=` which tolerate the extra derived events. Confirmed by source inspection; not run in-session (deferred build gate). `testing/integration-tests:check` is a recommended downstream gate alongside the two named targets.

### M3.6e.2 deliverables (2026-05-22)

- **`HomeSynapseCore.start()` — 14-step → 16-step bootstrap.** Steps 13 and 14 (new) register the entity query and admin endpoint groups against the same Javalin instance constructed in step 12. The new steps land *before* `app.start(HTTP_PORT)` so all routes are bound before the listener accepts traffic. The Javadoc bootstrap-sequence `<ol>` was extended with two new `<li>` items.
- **Step 13 — Entity query endpoints.** Single call: `RestFilters.installEntityQueryEndpoints(app, stateQueryService, stateProjection::cursorPosition, clock)`. The three routes (`GET /api/v1/entities`, `GET /api/v1/entities/{entityId}`, `GET /api/v1/entities/{entityId}/state`) all live under `/api/*` and therefore inherit the `ReadinessFilter` gate installed at step 12.
- **Step 14 — Admin endpoints.** Single call: `RestFilters.installAdminEndpoints(app, eventBus, this, stateQueryService, stateProjection::cursorPosition)`. The two routes (`GET /internal/dlq`, `GET /internal/projection`) live under `/internal/*` and are intentionally outside the readiness gate (settled decision SD-5 from the M3.6e.2 brief — operators need them during REPLAY).
- **No new module-info edges.** Both `com.homesynapse.api.rest` and `com.homesynapse.event.bus` were already declared (M3.6e.1 and M3.6d-a respectively); M3.6e.2 reuses them. No Gradle changes either.
- **Open-rocks unchanged.** OR-M3-15 (`NO_OP_DERIVATION`) and OR-M3-16 (`NO_OP_ADVANCER`) remain as M3.6d-b carry-overs — out of scope for M3.6e.2.

### M3.6e.1 deliverables (2026-05-22)

- **`HomeSynapseCore.start()` — 12-step → 14-step bootstrap.** Step 11 wires the production `MaterializedStateQueryService` via `StateQueryService.materialized(persistenceFactory.stateStore(), this, stateProjection::cursorPosition, clock)` and caches it in a field. Step 12 constructs an embedded `Javalin` server, sizes its Jetty thread pool from `config.persistence().profile().javalinMinThreads()/javalinMaxThreads()`, registers `new ReadinessFilter(this)` as `before("/api/*")`, suppresses the Javalin banner, and binds port 7070. The field-stored Javalin instance is what `stop()` tears down first (refuse new queries before tearing down state).
- **`HomeSynapseCore.stateQueryService()` — wiring change.** Returns the cached `MaterializedStateQueryService` instead of a fresh `ThrowingStateQueryService`. The placeholder class stays (per the brief) for tests but is no longer instantiated by the composition root.
- **Module-info additions:** `requires com.homesynapse.api.rest`, `requires io.javalin`, `requires org.eclipse.jetty.util` (for `QueuedThreadPool`). All non-transitive — these types appear only inside the composition root's `start()` body, not on any lifecycle-module public API surface.
- **Gradle additions:** `implementation(project(":api:rest-api"))` and `implementation(libs.javalin)`. `implementation` scope matches the non-transitive module-info edges.
- **Test impact:** `HomeSynapseCoreTest.stateQueryServiceThrowsUntilM3_6e` rewritten as `stateQueryServiceReturnsMaterializedAfterM3_6e_1` — verifies the real query service returns `Optional.empty()` for unknown entities and is stable across calls.

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
