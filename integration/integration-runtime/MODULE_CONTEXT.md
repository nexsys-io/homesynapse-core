# integration-runtime — `com.homesynapse.integration.runtime` — Scaffold — OTP-style supervisor, adapter lifecycle, health monitoring, Kahn's startup ordering

## Purpose

The Integration Runtime module is the supervisory layer that loads, isolates, monitors, and lifecycle-manages every integration adapter within the HomeSynapse process (Doc 05 §4, §5, §6, §8). It is the boundary between protocol-specific code (Zigbee coordinators, MQTT brokers, cloud APIs) and the event-sourced core. Without this subsystem, a misbehaving integration could starve the event bus of CPU, leak memory until the JVM crashes, or block system startup indefinitely — the exact failure modes that Home Assistant users experience when a single integration degrades the entire platform.

Where integration-api (Block I) defines *what an adapter declares and receives*, this module defines *what the supervisor does with those declarations*: lifecycle management, health state machine, restart intensity enforcement, exception classification, thread allocation, and shutdown orchestration. The `IntegrationSupervisor` interface is consumed by the Startup/Lifecycle module for boot/shutdown, the REST API for integration management endpoints, and the Observability module for composite health indicators.

This Phase 2 specification contains 6 Java files: 1 enum (ExceptionClassification — 3 values), 2 records (SlidingWindow — 3 fields, IntegrationHealthRecord — 13 fields), 1 interface (IntegrationSupervisor — 9 methods), package-info.java, and module-info.java.

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

    exports com.homesynapse.integration.runtime;
}
```

`requires transitive` because integration-api types (IntegrationFactory, IntegrationId, HealthState, HealthParameters) appear throughout the exported API surface — in IntegrationSupervisor method signatures and IntegrationHealthRecord record components. The event-model dependency in build.gradle.kts (`implementation`) is for Phase 3 internal use only (producing lifecycle events via EventPublisher) — no event-model types appear in Phase 2's exported API. Phase 3 will add `requires com.homesynapse.event` (non-transitive).

## Package Structure

**`com.homesynapse.integration.runtime`** — Single flat package. 6 Java files total.

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

### Gradle (build.gradle.kts)

```kotlin
api(project(":integration:integration-api"))
implementation(project(":core:event-model"))
```

The `api` scope for integration-api is correct — integration-api types appear in the runtime module's public API. The `implementation` scope for event-model is correct — event-model is only used internally for producing lifecycle events in Phase 3. **Do not change these dependencies.**

## Consumers

### Current consumers: None

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

## Phase 3 Notes

- **Health state machine implementation:** The supervisor maintains mutable per-integration state: current HealthState, three ConcurrentLinkedDeque\<Instant\> sliding windows (error, timeout, slow-call), restart timestamps for intensity tracking, probe state for SUSPENDED recovery cycles. State transitions are guarded by the rules in Doc 05 §3.4.
- **Restart backoff:** Exponential backoff starting from probeInitialDelay, capped at probeMaxDelay. Restart intensity tracked per integration — maxRestarts within restartWindow escalates to FAILED.
- **Thread allocation:** Virtual thread per NETWORK adapter via Executors.newVirtualThreadPerTaskExecutor(). Dedicated platform thread per SERIAL adapter (JNI pinning). Named threads for diagnostics (e.g., "integration-zigbee-0").
- **IntegrationContext construction:** The supervisor constructs per-adapter IntegrationContext with integration-scoped wrappers: EntityRegistry filtered by integrationId, StateQueryService filtered by integrationId, isolated SchedulerService, isolated ManagedHttpClient (if requested), shared EventPublisher (event namespace enforcement is Phase 3).
- **Dependency graph:** Kahn's algorithm with cycle detection (AMD-14). Build adjacency list from IntegrationDescriptor.dependsOn() → resolve integrationType to IntegrationId. Detect cycles before starting any integration. Shutdown in reverse topological order.
- **JFR monitoring:** RecordingStream subscribes to per-integration JFR events (CPU time, memory allocation, thread count). Feeds resourceComplianceScore in health score calculation.
- **Lifecycle event production:** On every health state transition, construct the appropriate IntegrationLifecycleEvent subtype and publish via EventPublisher with EventOrigin.SYSTEM. CRITICAL priority for SUSPENDED and FAILED transitions.
- **Command dispatch subscription:** Subscribe to command_dispatched events on the event bus. Filter by integration ownership (entityId → integrationId lookup via EntityRegistry). Construct CommandEnvelope. Invoke adapter's CommandHandler on the adapter's thread.
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
