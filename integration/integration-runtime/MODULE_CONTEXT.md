# integration-runtime — `com.homesynapse.integration.runtime` — M9.1 SLICE IMPLEMENTED — StandardIntegrationSupervisor (minimal FSM) + CommandRoutingSubscriber (the command_dispatched → CommandHandler spine) + assembly; supervisor breadth (DEGRADED/SUSPENDED/probes/Kahn/JFR) deferred to the post-hero unit

## Purpose

The Integration Runtime module is the supervisory layer that loads, isolates, monitors, and lifecycle-manages every integration adapter within the HomeSynapse process (Doc 05 §4, §5, §6, §8). It is the boundary between protocol-specific code (Zigbee coordinators, MQTT brokers, cloud APIs) and the event-sourced core. Without this subsystem, a misbehaving integration could starve the event bus of CPU, leak memory until the JVM crashes, or block system startup indefinitely — the exact failure modes that Home Assistant users experience when a single integration degrades the entire platform.

Where integration-api (Block I) defines *what an adapter declares and receives*, this module defines *what the supervisor does with those declarations*: lifecycle management, health state machine, restart intensity enforcement, exception classification, thread allocation, and shutdown orchestration. The `IntegrationSupervisor` interface is consumed by the Startup/Lifecycle module for boot/shutdown, the REST API for integration management endpoints, and the Observability module for composite health indicators.

This module now contains **13 Java files** (M9.1; the pre-M9.1 text said "7 Java files" in one place and "6 Java files" in another — 7 was correct, counting package-info.java and module-info.java): 2 enums (ExceptionClassification — **4 values** after AMD-56; HealthDetail — **12 values**, new in AMD-57), 2 records (SlidingWindow — 3 fields, IntegrationHealthRecord — **14 fields** after AMD-57), 1 interface (IntegrationSupervisor — 9 methods), **6 M9.1 production classes** (see the M9.1 section below), package-info.java, and module-info.java.

### M9.1 changes (integration spine, 2026-07-02)

The first `IntegrationSupervisor` implementation and the `command_dispatched` → `CommandHandler.handle(CommandEnvelope)` routing subscriber landed. Six new types:

| Type | Visibility | Purpose |
|---|---|---|
| `IntegrationSupervisorAssembly` | **public** final class | The composition-root seam (mirrors `PendingCommandLedgerAssembly`): `SUBSCRIBER_ID = "integration_supervisor"`, `record Components(IntegrationSupervisor supervisor, Subscriber subscriber)`, `subscriptionFilter()` (command_issued + command_dispatched, DIAGNOSTIC floor, ENTITY subjects), the 6-arg static factory `integrationSupervisor(EventPublisher, EntityRegistry, StateQueryService, Function<String,ConfigurationAccess>, Function<String,Map<String,Object>>, Clock)`, and `abandon(IntegrationSupervisor)` — the W4 fast-stop gateway (the `InProcessEventBus.abandon()` concrete-class precedent; the frozen 9-method interface carries no fast path). |
| `IntegrationIds` | **public** final class | `deriveStable(String integrationType)` — M9.1-interim deterministic identity: first 128 bits of SHA-256 of `"homesynapse:integration:" + type` into the `Ulid` carrier. Stable across restarts; documented LTD-04 deviation (NOT time-ordered). **[Design point] DP-B pending Nick's durable-identity ruling before M9.2 device adoption.** |
| `StandardIntegrationSupervisor` | package-private | The M9.1 FSM slice: registration (factory-list order; Kahn deferred), DP-11 thread allocation (NETWORK→VT, SERIAL→platform, named `integration-<type>-0`), HEALTHY ↔ TRANSIENT-restart-cycle → FAILED, descriptor-driven exponential backoff (clock-driven interrupt-safe waits — W8), grace-bounded close (10 s default; ctor-injectable for tests), lifecycle-event publication (zero mint), and the package-private router seam (`routeTarget(IntegrationId)` / `recordHandlerError`). |
| `CommandRoutingSubscriber` | package-private | The bus `Subscriber`: `command_issued` join-cache (all modes) + LIVE-only `command_dispatched` → `handle(...)` on the per-adapter single-threaded command executor (`integration-cmd-<type>`). |
| `ExceptionClassifier` | package-private | `classify(Throwable)` — the Doc 05 §3.7 M9.1 slice: PermanentIntegrationException→PERMANENT · InterruptedException→SHUTDOWN_SIGNAL · OOM/LinkageError→PERMANENT · everything else→TRANSIENT. Shutdown-aware reclassification lives in the supervisor's run loop (per-adapter `shuttingDown` flag), not the pure classifier. Nothing maps to AUTH_FAILED yet (AMD-56 routing = deferred breadth). |
| `SupervisorHealthReporter` | package-private | Per-integration `HealthReporter` write-through: heartbeat from the injected Clock, keepalive stores the adapter-reported Instant verbatim, errors increment the error-window count. `reportHealthTransition` is logged, not evaluated (no evaluation logic in M9.1). |

JPMS: `+ requires transitive com.homesynapse.event.bus` (the assembly's `Components` exposes the bus `Subscriber` type — paired with `api(project(":core:event-bus"))`), `+ requires org.slf4j` (implementation-only, `implementation(libs.slf4j.api)`).

### M4.C changes (AMD-54..64 freeze, 2026-06-05)

- **`ExceptionClassification` 3 → 4 values** (AMD-56): appended `AUTH_FAILED` last (after `SHUTDOWN_SIGNAL`). Auth failures route to reauth-or-suspend, never transient-backoff retry (AMD-56-INV-01). Append-only; declaration order frozen. M9 exhaustive switches over this enum must add an arm (no silent `default`).
- **`HealthDetail` (new, 12 values)** (AMD-57): the machine-readable cause vocabulary — `NONE, HEARTBEAT_TIMEOUT, KEEPALIVE_TIMEOUT, ERROR_RATE_EXCEEDED, TIMEOUT_RATE_EXCEEDED, SLOW_CALL_RATE_EXCEEDED, PROBE_FAILED, RESTART_LIMIT_EXCEEDED, SUSPENSION_LIMIT_EXCEEDED, RESOURCE_QUOTA_EXCEEDED, AUTH_FAILURE, PERMANENT_FAILURE`. Each value maps 1:1 to a supervisor transition trigger (a metrics-driven FSM can emit it truthfully — the self-report-vs-aggregation rationale, Nick arbitration A1). Append-only (AMD-57-INV-02).
- **`IntegrationHealthRecord` 13 → 14 components** (AMD-57): inserted `HealthDetail detail` immediately after `state` (component 3 of 14), non-null-guarded (`NONE` is the explicit no-cause value, AMD-57-INV-01). The record is supervisor-internal (constructed only by integration-runtime, read-only for rest-api/observability), so the canonical-ctor change is breaking-but-acceptable — **no convenience ctor was added** (zero production construction callers at `e76b925`). Adapters never set `detail` (no write path to the record).

## Design Doc Reference

**Doc 05 — Integration Runtime** is the governing design document:
- §3.4: Health state machine — four-state model (HEALTHY → DEGRADED → SUSPENDED → FAILED) with asymmetric hysteresis, OTP-style one-for-one supervision, sliding window rates, weighted health score formula
- §3.6: Graceful shutdown — reverse startup order, per-adapter grace period (default 10s), Thread.interrupt() for virtual threads, serial port close for platform threads
- §3.7: Exception classification — deterministic table mapping exception types to TRANSIENT/PERMANENT/SHUTDOWN_SIGNAL; shutdown-aware reclassification when per-adapter shuttingDown flag is set
- §3.13: Dependency graph — Kahn's algorithm with cycle detection (AMD-14) for startup ordering from IntegrationDescriptor.dependsOn()
- §4.3: IntegrationHealthRecord — per-integration health state snapshot, weighted health score formula (0.30 × errorRate + 0.20 × timeoutRate + 0.15 × slowCallRate + 0.20 × dataFreshness + 0.15 × resourceCompliance)
- §5: Startup — IntegrationSupervisor.start() returns CompletableFuture<Void>, failing integrations marked FAILED, system proceeds (INV-RF-03)
- §8.1: IntegrationSupervisor — central supervisory contract (9 methods)
- §3.14: Planned restart behavior — plannedRestart flag, availability suppression, command queuing, orphan exclusion, 60s timeout
- §8.2: Runtime types — ExceptionClassification, SlidingWindow, IntegrationHealthRecord

## JPMS Module

```
module com.homesynapse.integration.runtime {
    requires transitive com.homesynapse.integration;
    requires transitive com.homesynapse.event.bus;   // M9.1
    requires org.slf4j;                              // M9.1

    exports com.homesynapse.integration.runtime;
}
```

`requires transitive com.homesynapse.integration` because integration-api types (IntegrationFactory, IntegrationId, HealthState, HealthParameters) appear throughout the exported API surface. `requires transitive com.homesynapse.event.bus` (M9.1) because `IntegrationSupervisorAssembly.Components` exposes the bus `Subscriber` type — paired with `api(project(":core:event-bus"))` (the exports lockstep). Event-model, device-model, state-store, config, and platform types all resolve TRANSITIVELY through `com.homesynapse.integration` — no direct requires exists or is needed (the pre-M9.1 prose claiming an event-model `implementation` Gradle edge was stale: the actual build.gradle.kts had only `api(":integration:integration-api")` before M9.1). `org.slf4j` is plain/`implementation` (LTD-15).

## Package Structure

**`com.homesynapse.integration.runtime`** — Single flat package. 13 Java files total (M9.1; the pre-M9.1 "6 Java files" here disagreed with the header's "7" — both are superseded).

## Complete Type Inventory

### Enums (1)

| Type | Values | Purpose |
|---|---|---|
| `ExceptionClassification` (3 values) | TRANSIENT (restart with backoff), PERMANENT (transition to FAILED), SHUTDOWN_SIGNAL (do not restart — supervisor's own shutdown) | Deterministic classification of exceptions escaping the adapter→supervisor boundary. |

**Exception classification table (Doc 05 §3.7):**

| Classification | Exceptions | Supervisor Action |
|---|---|---|
| TRANSIENT | IOException, SocketException, SocketTimeoutException, unknown RuntimeException | Restart with exponential backoff, subject to restart intensity limit |
| PERMANENT | PermanentIntegrationException, ConfigurationException, AuthenticationException, UnsupportedOperationException, OutOfMemoryError, other Error | Transition to FAILED immediately, no restart |
| SHUTDOWN_SIGNAL | InterruptedException, ClosedByInterruptException | Do not restart, do not record as failure |

**Shutdown-aware reclassification:** When the per-adapter `shuttingDown` flag is set, SocketException and IOException are reclassified from TRANSIENT to SHUTDOWN_SIGNAL. This prevents restart attempts for exceptions caused by the supervisor's own shutdown sequence.

### Records (2)

| Type | Fields | Purpose |
|---|---|---|
| `SlidingWindow` (3 fields) | size (int — window capacity, default 20 from HealthParameters.healthWindowSize()), count (int — events in window, 0 to size), rate (double — count/size, 0.0 to 1.0) | Point-in-time snapshot of a sliding window for error/timeout/slow-call rate tracking. Phase 3 uses ConcurrentLinkedDeque\<Instant\> internally; this record captures observable state. |
| `IntegrationHealthRecord` (13 fields) | integrationId (IntegrationId), state (HealthState), healthScore (double 0.0–1.0), lastHeartbeat (Instant), lastKeepalive (Instant, nullable), stateChangedAt (Instant), consecutiveFailures (int), suspensionCycleCount (int), totalSuspendedTime (Duration), errorWindow (SlidingWindow), timeoutWindow (SlidingWindow), slowCallWindow (SlidingWindow), plannedRestart (boolean) | Per-integration health state snapshot. Not persisted — reconstructed on startup. Exposed via REST API. plannedRestart indicates the integration is in a supervisor-initiated restart cycle (Doc 05 §3.14). |

**Health score formula:**
```
healthScore = 0.30 × (1 - errorRate)
            + 0.20 × (1 - timeoutRate)
            + 0.15 × (1 - slowCallRate)
            + 0.20 × dataFreshnessScore
            + 0.15 × resourceComplianceScore
```
`dataFreshnessScore` and `resourceComplianceScore` are computed on demand from lastHeartbeat and JFR metrics — not stored as fields because they are time-dependent and would be immediately stale. Phase 3 computes them when updating the health score.

### Service Interfaces (1)

| Type | Methods | Purpose |
|---|---|---|
| `IntegrationSupervisor` (9 methods) | start, stop, startIntegration, stopIntegration, restartIntegration, health, allHealth, isRunning, registeredIntegrations | Central supervisory contract. OTP-style one-for-one supervision. Thread-safe. |

**IntegrationSupervisor method detail:**

| Method | Signature | Async/Sync | Notes |
|---|---|---|---|
| `start` | `CompletableFuture<Void> start(List<IntegrationFactory>)` | Async | Discovers from factory list (DECIDE-04, no ServiceLoader), topological sort (Kahn's AMD-14), starts all enabled integrations. Failing integration → FAILED, system proceeds (INV-RF-03). |
| `stop` | `void stop()` | **Sync** | Blocks until all adapters stopped or timed out. Reverse startup order. Per-adapter grace period (default 10s). |
| `startIntegration` | `CompletableFuture<Void> startIntegration(IntegrationId)` | Async | Manual restart of FAILED integration. Throws IllegalStateException if not FAILED. |
| `stopIntegration` | `CompletableFuture<Void> stopIntegration(IntegrationId)` | Async | Stop single running integration. |
| `restartIntegration` | `CompletableFuture<Void> restartIntegration(IntegrationId)` | Async | Stop then start. Convenience for REST API. |
| `health` | `Optional<IntegrationHealthRecord> health(IntegrationId)` | Sync | Point-in-time snapshot. Empty if not registered. |
| `allHealth` | `Map<IntegrationId, IntegrationHealthRecord> allHealth()` | Sync | Unmodifiable map. Snapshot — not live. |
| `isRunning` | `boolean isRunning(IntegrationId)` | Sync | True if HEALTHY or DEGRADED (actively running). |
| `registeredIntegrations` | `Set<IntegrationId> registeredIntegrations()` | Sync | Unmodifiable set. |

## Dependencies

### Phase 2: integration-api only

| Module | Why | Gradle Scope |
|---|---|---|
| integration-api (`com.homesynapse.integration`) | IntegrationFactory (start param), IntegrationId (method params, record field), HealthState (record field), HealthParameters (Javadoc references) | `api` |

Integration-api's `requires transitive` chain provides transitive access to platform-api (IntegrationId), event-model, device-model, state-store, persistence, configuration, and java.net.http. The runtime module's Phase 2 types only directly import from integration-api and platform-api (for IntegrationId).

### Phase 3 will add:

| Module | Why | Gradle Scope |
|---|---|---|
| event-model (`com.homesynapse.event`) | EventPublisher for producing lifecycle events (IntegrationStarted, etc.) | `implementation` (already in build.gradle.kts) |
| event-bus (`com.homesynapse.event.bus`) | Subscribe to command_dispatched events for CommandHandler dispatch | `implementation` (to be added) |
| state-store (`com.homesynapse.state`) | Integration-scoped StateQueryService wrappers | transitively via integration-api |
| device-model (`com.homesynapse.device`) | Integration-scoped EntityRegistry wrappers | transitively via integration-api |
| persistence (`com.homesynapse.persistence`) | TelemetryWriter for IntegrationContext construction | transitively via integration-api |
| configuration (`com.homesynapse.config`) | ConfigurationAccess for IntegrationContext construction | transitively via integration-api |
| jdk.jfr | RecordingStream for health monitoring mechanism 3 (resource compliance) | JDK module |

### Gradle (build.gradle.kts, M9.1)

```kotlin
api(project(":integration:integration-api"))
api(project(":core:event-bus"))            // M9.1 lockstep with requires transitive
implementation(libs.slf4j.api)             // M9.1 lockstep with plain requires

testImplementation(project(":testing:test-support"))
testImplementation(testFixtures(project(":integration:integration-api")))
testImplementation(testFixtures(project(":core:event-model")))
```

The `api` scopes match the two `requires transitive` directives (the exports lockstep). There is NO direct event-model edge — event-model resolves transitively through integration-api (the pre-M9.1 claim of an `implementation(":core:event-model")` line here was stale; it never existed in the actual file).

## Consumers

### Current consumers:
- **lifecycle** (`com.homesynapse.lifecycle`, M9.1) — Phase 6 of `HomeSynapseCore` constructs `IntegrationSupervisorAssembly.integrationSupervisor(...)`, registers the router via `subscribeRuntime` (`SUBSCRIBER_ID`, `subscriptionFilter()`, `coalesceExempt=true`, AFTER projection LIVE), calls `supervisor.start(factories).get(30, SECONDS)` (boot continues on failure — INV-RF-01), `supervisor.stop()` in `doTeardown`, and `IntegrationSupervisorAssembly.abandon(supervisor)` in `abandon()`. Plain `requires` / `implementation(...)` — runtime types stay off lifecycle's exported API.

### Planned consumers:
- **lifecycle** (`com.homesynapse.lifecycle`) — Calls `IntegrationSupervisor.start(factories)` during boot Phase 4, calls `IntegrationSupervisor.stop()` during shutdown step 5 (after WebSocket, before REST API). The lifecycle module assembles the `List<IntegrationFactory>` per DECIDE-04.
- **rest-api** (`com.homesynapse.api.rest`, Phase 3) — Calls `health(IntegrationId)`, `allHealth()` for integration health endpoints (Doc 09 §3.2, §7). Calls `startIntegration()`, `stopIntegration()`, `restartIntegration()` for integration management endpoints.
- **observability** (`com.homesynapse.observability`) — Calls `allHealth()` for the composite health indicator (Doc 11 §11.3) that aggregates health across all subsystems.

## Cross-Module Contracts

- **IntegrationSupervisor.start() accepts `List<IntegrationFactory>`, not ServiceLoader.** Per DECIDE-04, the application module assembles the factory list explicitly. The supervisor does not scan the classpath or module path for factories. This is the single entry point for integration discovery.
- **IntegrationHealthRecord contains integration-api types (IntegrationId, HealthState) but is defined in integration-runtime.** Adapters never see this type — they interact with health via HealthReporter (in integration-api). The health record is a supervisor-internal view exposed to REST API and observability consumers.
- **stop() is synchronous; start() and individual operations are async.** The stop() method blocks the caller until shutdown is complete or timed out. This is required by the lifecycle module's ordered shutdown sequence — step 5 must complete before step 6 begins. start() returns CompletableFuture to allow the lifecycle module to continue with other boot phases while integrations initialize.
- **Health record snapshots, not live objects.** All returned IntegrationHealthRecord instances and collections are immutable point-in-time snapshots. The REST API receives a consistent view even if the supervisor updates health concurrently.
- **ExceptionClassification is consumed only by the Phase 3 supervisor implementation.** It is in the exported API so that the REST API can reference it for diagnostic endpoints, but the primary consumer is the supervisor's internal exception handling logic.
- **`IntegrationHealthRecord.plannedRestart` is supervisor-internal state.** Core modules (automation, state-store) cannot read this field due to JPMS module boundaries — they learn about planned restarts via `integration_stopped(reason: planned_restart)` and `integration_restarted` events published through the event bus. Only REST API and observability consumers (which depend on integration-runtime) read the field directly.
- **Three sliding windows per integration.** Error, timeout, and slow-call rates are tracked independently. Window capacity defaults to 20 (from HealthParameters.healthWindowSize()). The rates feed into the weighted health score formula.
- **Lifecycle events produced by the supervisor flow through integration-api types.** The supervisor constructs IntegrationStarted, IntegrationStopped, IntegrationHealthChanged, IntegrationRestarted, and IntegrationResourceExceeded payloads (defined in integration-api) and publishes them via EventPublisher. The event types live in integration-api so consumers (REST API, automation engine, WebSocket) can pattern-match without depending on integration-runtime.

## Constraints

| Constraint | Description |
|---|---|
| LTD-01 | Virtual threads for NETWORK adapters; platform threads for SERIAL (JNI). Thread allocation is Phase 3 — Phase 2 interfaces must not preclude it. |
| LTD-04 | IntegrationId (ULID) used throughout — transitively available via integration-api → platform-api. |
| LTD-11 | No synchronized — ReentrantLock only. Phase 3 supervisor implementation must use ReentrantLock for concurrent health state access. |
| LTD-15 | JFR metrics for health monitoring mechanism 3 (resource compliance). Phase 2 interfaces don't reference JFR types. |
| LTD-16 | In-process compiled modules. DECIDE-04 overrides ServiceLoader for MVP. |
| LTD-17 | Build-enforced API boundaries. integration-runtime exports only `com.homesynapse.integration.runtime`. |
| INV-RF-01 | Integration isolation — supervisor catches all exceptions escaping the adapter boundary. ExceptionClassification defines the response. |
| INV-RF-02 | Resource quotas — IntegrationHealthRecord tracks resource compliance via JFR metrics. Phase 3 implementation. |
| INV-RF-03 | Startup independence — IntegrationSupervisor.start() completes regardless of external device connectivity. |
| INV-RF-06 | Graceful degradation — four-state health model (HEALTHY → DEGRADED → SUSPENDED → FAILED) with asymmetric hysteresis. |
| INV-TO-01 | Observable behavior — every health transition produces an event (Phase 3 via EventPublisher). |
| INV-HO-04 | Self-explaining errors — IntegrationHealthRecord exposed via REST API; ExceptionClassification documents failure reasons. |
| INV-CE-02 | Zero-config — HealthParameters.defaults() provides sensible defaults for all supervisor behavior. |

## Key Design Decisions

1. **IntegrationSupervisor is an interface, not a class.** Phase 2 defines the contract; Phase 3 implements it. The implementation will hold substantial mutable state (per-integration health tracking, sliding window deques, restart intensity counters, thread references). Keeping it as an interface enables testing with mock supervisors in consumer modules.

2. **IntegrationHealthRecord is a record in integration-runtime, not integration-api.** Adapters do not see this type — they interact with health through HealthReporter (in integration-api). The health record contains supervisor-internal fields (consecutiveFailures, suspensionCycleCount, totalSuspendedTime, sliding windows) that adapters should not depend on. REST API and observability consumers read these snapshots for dashboard display.

3. **SlidingWindow uses `double` for rate, not `BigDecimal`.** The health score calculation uses floating-point arithmetic with no currency or precision requirements. The rate is count/size — a simple ratio that doesn't accumulate rounding errors across calculations. Double avoids unnecessary complexity.

4. **ExceptionClassification has three values, not two.** SHUTDOWN_SIGNAL distinguishes exceptions caused by the supervisor's own shutdown from genuine adapter failures. Without this, a graceful shutdown would trigger restart attempts for every adapter whose socket was interrupted — creating a storm of restart → interrupt → restart cycles.

5. **`stop()` is synchronous; lifecycle operations are async.** stop() blocks because the lifecycle module's shutdown sequence requires ordered completion (integration shutdown must finish before event bus shutdown). start() and individual operations return CompletableFuture for non-blocking orchestration.

6. **`isRunning()` maps to HEALTHY or DEGRADED, not a "RUNNING" enum value.** The HealthState enum has no RUNNING value — "running" in the supervisor sense means the adapter's thread is active and the health state is either HEALTHY or DEGRADED. SUSPENDED and FAILED mean the adapter is not actively processing.

## Gotchas

**GOTCHA: `IntegrationHealthRecord.lastKeepalive` is nullable.** Null means no keepalive has been reported yet (the adapter hasn't successfully communicated with its external device). This is expected during initialization and for adapters that don't implement protocol-level keepalives. Do not treat null as an error condition.

**GOTCHA: `IntegrationHealthRecord.healthScore` includes components not stored as fields.** The `dataFreshnessScore` and `resourceComplianceScore` are computed on demand from lastHeartbeat and JFR metrics. The stored healthScore represents the value at the time the snapshot was created. It may diverge from a freshly computed score if time has elapsed.

**GOTCHA: `SlidingWindow.rate` is not necessarily equal to `count / size` at read time.** The record captures a point-in-time snapshot. Phase 3 computes the rate from the internal ConcurrentLinkedDeque and stores it in the record. The stored rate is authoritative, not a derived value that should be recomputed from count and size.

**GOTCHA: `IntegrationSupervisor.startIntegration()` throws IllegalStateException if not FAILED.** This is for manual restart only — the supervisor handles automatic restarts internally for TRANSIENT failures. The REST API must check the integration's current state before calling startIntegration().

**GOTCHA: `stop()` blocks but has a timeout.** Each adapter gets a configurable grace period (default 10s from Doc 05 §3.6). If an adapter does not stop within its grace period, the supervisor logs the abandonment and proceeds. The total stop() duration is bounded by (number of adapters × grace period), but in practice runs faster because shutdown proceeds in parallel within each dependency tier.

**GOTCHA: No event-model imports in Phase 2.** The module-info.java has no `requires com.homesynapse.event`. Event-model is in build.gradle.kts as `implementation` for Phase 3, but the Phase 2 exported API uses only integration-api types and JDK types. Phase 3 will add `requires com.homesynapse.event` (non-transitive) when the implementation imports EventPublisher.

**GOTCHA: Unknown RuntimeException defaults to TRANSIENT, not PERMANENT.** This is deliberate (Doc 05 §3.7) to prevent the Home Assistant anti-pattern where an unexpected exception type permanently kills an integration. The safe default is restart-with-backoff. Only known-unrecoverable exceptions (PermanentIntegrationException, OutOfMemoryError, etc.) trigger permanent failure.

**GOTCHA (M9.1): the router's join cache — bounded, all-modes populated, evict-on-join.** `command_dispatched` carries no command name/parameters, so `CommandRoutingSubscriber` joins it to its `command_issued` via `causalContext().causationId()` against a 1024-entry insertion-ordered cache (drop-oldest with a WARN `integration.command_cache_evicted`). The cache is populated in ALL subscriber modes (a `command_issued` delivered during TRANSITION legitimately joins a LIVE `command_dispatched` across the flip) but **dispatch fires only for LIVE `command_dispatched`** (INV-ES-09). A successful join EVICTS the entry — a bus at-least-once redelivery is a join miss, so `handle(...)` runs exactly once per dispatched command (AMD-90-INV-01 composes). A join miss is WARN `integration.route_join_miss` + skip: no dispatch, no result event — the pending-command ledger's timeout owns that outcome.

**GOTCHA (M9.1): the router publishes FAILURE results only.** On a normal `handle(...)` return the router publishes NOTHING — the adapter owns the eventual `command_result` (Doc 08 §3.10 step 7). The router's own `command_result`s (`integration_unavailable` / `unsupported` / `handler_error`) are CRITICAL, origin SYSTEM, causation-chained from the `command_dispatched` envelope. `InterruptedException` from `handle(...)` restores the interrupt and publishes nothing (shutdown).

**GOTCHA (M9.1 / DP-6): integration identity is an INTERIM hash derivation.** `IntegrationIds.deriveStable(type)` — SHA-256-based, stable across restarts, deliberately NOT a time-ordered ULID (documented LTD-04 deviation). **[Design point] DP-B**: Nick rules the durable identity story before M9.2's real device adoption makes it one-way. Everything resolves ids through this single seam.

**GOTCHA (M9.1): "running" means HOSTED, not merely a health state.** `isRunning(id)` (and the router's `routeTarget`) require the adapter to be hosted (created + not torn down) AND HEALTHY/DEGRADED. A cleanly-stopped adapter keeps its last health state (`integration_stopped` publishes the AMD-58-style same-state pair) but is no longer hosted. An administratively stopped HEALTHY integration is therefore restartable only via `restartIntegration` (manual `startIntegration` stays FAILED-only per the frozen contract).

**GOTCHA (M9.1): the M9.1 deferred-breadth list.** DEGRADED/SUSPENDED, the probe ladder, suspension cycles, heartbeat-timeout sweeps, window-rate evaluation, JFR/resource quotas, planned-restart suppression behaviors (Doc 05 §3.14 — only the `plannedRestart` flag is carried), dependency-graph (Kahn) ordering, integration-scoped registry/query wrappers, AUTH_FAILED routing, and the health-score formula (the slice reports binary 1.0/0.0) are ALL deferred to the post-hero supervisor-breadth unit (NQ-6 validates restart defaults first). A normal (non-shutdown) `run()` return classifies TRANSIENT and restarts.

## Phase 3 Notes

*(M9.1 status tags added 2026-07-02: the spine slice is BUILT; everything tagged "deferred to post-hero unit" is supervisor breadth the M9.1 instruction explicitly excluded.)*

- **Health state machine implementation [M9.1 SLICE BUILT; full FSM deferred to post-hero unit]:** M9.1 tracks per-integration state under one ReentrantLock (an error-window count, restart timestamps in an ArrayDeque, HEALTHY/FAILED transitions). The three-deque sliding windows, probe state, and DEGRADED/SUSPENDED transitions per Doc 05 §3.4 are the breadth unit's.
- **Restart backoff [BUILT, M9.1]:** Exponential from the DESCRIPTOR's `BackoffParameters` (initialDelay × multiplier^n, capped at maxDelay — NOT probeInitialDelay/probeMaxDelay as this note previously sketched; the probe parameters belong to the SUSPENDED recovery ladder, which is deferred). Intensity: maxRestarts within restartWindow escalates to FAILED (`HealthDetail.RESTART_LIMIT_EXCEEDED`). Clock-driven interrupt-safe waits (W8) — testable under a stepped TestClock.
- **Thread allocation [BUILT, M9.1]:** One supervise-loop thread per adapter — virtual for NETWORK, dedicated platform for SERIAL (JNI pinning), named `integration-<type>-0` — plus a single-threaded per-adapter VIRTUAL command executor named `integration-cmd-<type>` (FIFO command delivery, Doc 08 §3.10 step 1).
- **IntegrationContext construction [M9.1 SLICE BUILT; scoped wrappers deferred to post-hero unit]:** M9.1 passes the REAL (unscoped) EntityRegistry/StateQueryService, a per-integration `SupervisorHealthReporter`, and per-integration-scoped `ConfigurationAccess` from the injected `Function<String, ConfigurationAccess>` (the composition root binds `ConfigurationAccess.scoped(type, configurationService.getCurrentModel())` — the B7 corrected path, so config IS scoped now). The 5 service-gated tails (scheduler/telemetry/http/security/discovery) stay null until an adapter declares them (Zigbee at M9.4). Integration-SCOPED registry/query wrappers are the breadth unit's.
- **Dependency graph:** Kahn's algorithm with cycle detection (AMD-14). Build adjacency list from IntegrationDescriptor.dependsOn() → resolve integrationType to IntegrationId. Detect cycles before starting any integration. Shutdown in reverse topological order.
- **JFR monitoring:** RecordingStream subscribes to per-integration JFR events (CPU time, memory allocation, thread count). Feeds resourceComplianceScore in health score calculation.
- **Lifecycle event production:** On every health state transition, construct the appropriate IntegrationLifecycleEvent subtype and publish via EventPublisher with EventOrigin.SYSTEM. CRITICAL priority for SUSPENDED and FAILED transitions.
- **Command dispatch subscription: BUILT (M9.1).** `CommandRoutingSubscriber` consumes `command_dispatched` and invokes the owning adapter's `CommandHandler` on the per-adapter command executor. NOTE the built shape differs from this note's original sketch: the router does NOT re-resolve entity→integration via EntityRegistry — `dispatched.integrationId()` is authoritative (that resolution already happened in `StandardCommandDispatchService`), and the command name/parameters come from the DP-2 causation join against `command_issued`.
- **ManagedHttpClient implementation:** Wrap java.net.http.HttpClient with Semaphore for concurrency limiting, token bucket for rate limiting. Connection pool isolation per adapter. Lifecycle tied to adapter — close() cancels pending requests and releases the connection pool.
- **Shutdown orchestration:** Set per-adapter shuttingDown flag, interrupt virtual threads / close serial ports, wait for grace period, log abandoned adapters, produce integration_stopped events for clean shutdowns.
- **Planned restart lifecycle (Doc 05 §3.14):** When `restartIntegration()` is called, set `plannedRestart = true` on the IntegrationHealthRecord. While true: suppress `availability_changed` events for owned entities, queue inbound commands (do not drop), exclude owned devices from orphan detection (AMD-17). Clear the flag when the adapter reaches HEALTHY or when 60s timeout expires (whichever comes first). On timeout, treat as normal restart failure. The automation engine accesses planned restart state via event subscription (`integration_stopped` with reason `planned_restart`), NOT by reading `IntegrationHealthRecord.plannedRestart()` directly — JPMS prevents core modules from importing integration-runtime types.
- **Health evaluation interval:** Default 15s, configurable range 5–60s (Doc 05 §3.4). The health score is recomputed on this interval, not on every health signal. This bounds CPU cost on the Pi but means state transitions can lag by up to one interval.
- **Testing strategy:** Unit tests for ExceptionClassification logic (mock exception → expected classification), health state machine transitions (mock health signals → expected state), restart intensity (rapid restarts → FAILED). Integration tests for full supervisor lifecycle (start → health reporting → degradation → suspension → recovery). Performance test for startup time with multiple adapters.


---

## Phase 3 Cross-Module Context

*Added 2026-04-11 (Alignment Pass #2). Phase 3 implementation is active — M2.5 `SqliteEventStore` landed 2026-04-11 (commit `5279e7a`), next milestone M2.6 + M2.7 (combined) pending from Nick.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: use `@EventType` + `EventTypeRegistry` for command/lifecycle event dispatch; integration events must be annotated
- **D-02** — *Persistence uses platform threads*: when this module persists state or health records, submit through `DatabaseExecutor` — do not hold `Connection` across virtual-thread boundaries
- **D-04** — *Clock must be injected*: supervisor health-window timestamps, restart-intensity timers, and backoff schedules all take `Clock` via constructor injection — no `Instant.now()` or `System.nanoTime()`
- **D-05** — *`@EventType` on every event record*: IntegrationLifecycleEvent subtypes are annotated (M2.i); any new lifecycle event type added here needs the annotation + an `EventCategoryMapping` entry

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for recent Phase 3 pattern discoveries (especially the 2026-04-10 entries on `NO_DIRECT_TIME_ACCESS` and JUnit 5 `@BeforeEach` ordering).
