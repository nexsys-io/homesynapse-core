# testing:integration-tests — On-Device Integration Tests

Module 20 of 20. Created in M3.4a (2026-05-19, `5ae7912`). Test-only module — no main source set, no production types, no `module-info.java`. Exercises the full production stack (file-based SQLite + platform-thread executors + InProcessEventBus + StateProjection) under Pi-4-equivalent JVM constraints.

**Design doc reference:** PLAN-M3-CONSOLIDATED-02 §9 (M3.4 integration tests), §13.8 (soak test policy).

**Gating:** Tests are excluded from the default `./gradlew check` flow. Enable via `-PpiProfile=throttled`. Optional `-PsustainedMinutes=N` overrides the sustained-load test duration (default 60 minutes, CI: 10).

---

## Dependencies

### Test classpath (`testImplementation`)
| Dependency | Why |
|---|---|
| `platform:platform-api` | Identity types (EntityId, HomeId, Ulid) |
| `core:event-model` | DomainEvent subtypes, EventTypes, SubjectRef |
| `core:event-bus` | InProcessEventBus (via testFixtures factory), BusMetrics, SubscriberSnapshot |
| `core:state-store` | StateProjection, EntityState, StateQueryService |
| `core:persistence` | SqlitePersistenceLifecycle (via testFixtures harness), DeploymentProfile |
| `integration:integration-api` | IntegrationLifecycleEvent subtypes |
| `testing:test-support` | Cross-cutting test infrastructure |
| testFixtures(`core:event-model`) | Event construction helpers, test event types |
| testFixtures(`core:event-bus`) | `InProcessEventBusFactory` — constructs package-private `InProcessEventBus` |
| testFixtures(`core:state-store`) | `InMemoryProjectionAdvancer` — wraps any EventStore for batch path |
| testFixtures(`core:persistence`) | `PersistenceTestHarness` — wraps package-private `SqlitePersistenceLifecycle`; `ThrottledWriteCoordinator` — disk test double |
| `libs.slf4j.api` | Progress logging for long-running tests (added M3.4b) |

### Runtime only (`testRuntimeOnly`)
| Dependency | Why |
|---|---|
| `libs.sqlite.jdbc` | Real WAL mode requires the native JDBC driver |

---

## Package Structure

All types in `com.homesynapse.it` (test scope only, no module-info.java).

---

## Type Inventory

All types are test-scope. None are public API — this module has no consumers.

### Test Infrastructure

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `IntegrationTestHarness` | class | Wires the full production stack for integration testing | Factory methods: `start(Path, Clock)` (standard), `startThrottled(Path, Clock, BusMetrics)` (M3.4b — injects ThrottledWriteCoordinator), `startForCrashSimulation(Path, Clock, BusMetrics)` (M3.4b — semantic alias for crash tests). `abandon()` simulates ungraceful shutdown (skips WAL checkpoint + executor cleanup). Accessors: `eventPublisher()`, `eventStore()`, `eventBus()`, `stateProjection()`, `stop()`. |

### M3.4a Tests (commit `5ae7912`)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `BurstLoadIT` | test class | 500-event burst under Pi-4 JVM constraints | 6 assertions: event count, entity state materialization, global position contiguity, causal chain integrity, lag bound, completion within time budget. Uses `IntegrationTestHarness.start()`. |
| `HeapBudgetIT` | test class | 3,000-entity heap bound verification | 4 assertions: entity count materialized, heap after forced GC under ceiling, no OOM, state query correctness. Uses `-Xmx256m`. |

### M3.4b Tests (commit `adf04d2`)

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Pi4SustainedLoadIT` | test class | Sustained 100 ev/s for configurable duration | Duration from `sustained.minutes` system property (default 60). Lag-bound assertion (≤50 events) is the load-bearing check. Event-count assertion is informational (lower-bound 25% of theoretical). Uses `IntegrationTestHarness.startThrottled()`. |
| `Pi4D1SpikeIT` | test class | 50 ev/s for 30 minutes with D1 spike simulation | Validates stability under realistic SD-card latency spikes (200ms at 0.5% probability). Paced against absolute schedule to prevent drift. Uses `startThrottled()`. |
| `CrashRecoveryIT` | test class | 5,000 events, simulated crash at ≥3,000, restart, verify recovery | Creates harness → publishes 5,000 events → calls `abandon()` at ≥3,000 → creates fresh harness on same dbPath → verifies all persisted events survive and projection rebuilds correctly. Uses `@TempDir(cleanup = CleanupMode.NEVER)` because abandoned harness holds file handles. |

### M3.7 — E2E HTTP Coverage

New types (parallel to M3.4a/M3.4b infrastructure — the M3.4 `IntegrationTestHarness` STAYS for performance/load/heap-budget tests that do not exercise HTTP; M3.7's `HomeSynapseE2eHarness` is the HTTP-aware sibling).

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `HomeSynapseE2eHarness` | test class (REC-16) | Wires the full production composition root (`HomeSynapseCore`) against a `@TempDir` SQLite file and an ephemeral HTTP port for HTTP-aware E2E. Implements `AutoCloseable`. **M3.7 closeout adds `abandon()`** for ungraceful crash simulation. | Static factory: `start(Path, Clock, HomeId)` constructs `HomeSynapseConfig.testing()` (port 0) and calls `core.start().join()` on the calling thread (MUST be a platform thread — JacksonWarmup constraint, LTD-19 / DECIDE-M2-05). Accessors delegate to the underlying core: `boundHttpPort()`, `baseUri()`, `eventPublisher()`, `eventStore()`, `eventBus()`, `stateQueryService()`, `mode()`. `stop()` / `close()` are idempotent. `abandon()` delegates to `HomeSynapseCore.abandon()` — releases OS handles (HTTP socket, JDBC connections, subscriber VTs, scheduler threads) WITHOUT WAL checkpoint or projection checkpoint flush. Mutual exclusion with `stop()` via the underlying `HomeSynapseCore.abandoned` flag — calling either after the other is a no-op. Used by `CrashRecoveryHttpIT` in the post-publish `finally` block (replaced the empty-finally placeholder that previously leaked the entire stack). Each harness binds to a single dbPath and a single ephemeral port — no shared state across tests. |
| `LiveModeAwaiter` | test utility (REC-13, reinterpreted) | Awaitility-based helper for waiting on `SubscriberMode.LIVE`. | Two static methods: `awaitLive(harness, Duration)` and the default-5s overload `awaitLive(harness)`. REC-13 said "use `bus.isLive()`" — there is no such method on `EventBus` (per-subscriber concept). This helper polls `harness.mode()` instead, which delegates to `HomeSynapseCore.mode()` → `StateProjection.currentMode()`. No `isLive()` method was added to `EventBus`. |
| `TestEvents` | test utility (REC-18) | Static-factory helpers for the most common `EventDraft` shapes. | Methods: `stateReported(EntityId, String attributeKey, String value)`, `availabilityChanged(EntityId, String previousStatus, String newStatus)`. Defaults: priority NORMAL or DIAGNOSTIC, origin DEVICE_AUTONOMOUS, `eventTime = null`, `actorRef = null`, `idempotencyKey = null`. |
| `EndpointE2eIT` | test class | E2E tests against the M3.6e.2 endpoints with real Jetty + real `HttpClient` + `json-unit-assertj` (REC-17). | Five `@Test` methods covering: empty listing, populated listing, single-entity 200, unknown-entity 404 with RFC 9457 problem detail, `/internal/dlq` response shape including the new `oldestParkedAt` field (asserts empty-DLQ → null). Uses `Clock.fixed(...)` per DEC-M3-09. |
| `CrashRecoveryHttpIT` | test class (REC-19) | Crash-recovery scenario at the HTTP layer — exercises the full rehydration path end-to-end (M3.7 fix 2026-05-27). | Mirrors M3.4b's `CrashRecoveryIT` but verifies via `GET /api/v1/entities` after restart. Uses `@TempDir(cleanup = CleanupMode.NEVER)` per the M3.4b Windows file-handle lesson. **Recovery mechanism after M3.7 fix:** `HomeSynapseConfig.testing()` selects `FixedCheckpointPolicy.TESTING` (N=1) so every `onEvent()` in Phase 1 flushes a view checkpoint under `"state_projection"`; on restart the fresh `SqliteStateStore` rehydrates its `ConcurrentHashMap` from that blob, even though the bus subscriber checkpoint (position 2) causes the bus to skip replay. The test asserts the HTTP entities listing has size 2 — the correct integration-level signal that the state survived abandon/restart. |
| `InFlightRequestShutdownIT` | test class (REC-21) | In-flight request behaviour during shutdown. | Fires a slow request on a separate platform thread, then calls `harness.stop()`. Asserts the request resolves deterministically (response or IO failure) within a loose 30-second bound (the exact `RestApiLifecycle.stop(int drainSeconds)` contract is Phase 3 future scope). |

### M3.7 Dependencies Added

- `testImplementation(project(":lifecycle:lifecycle"))` — for `HomeSynapseCore`, `HomeSynapseConfig`
- `testImplementation(project(":api:rest-api"))` — for the M3.6e.2 endpoint contract types referenced in assertions
- `testImplementation(libs.awaitility)` — Awaitility 4.3.0 (REC-14)
- `testImplementation(libs.json.unit.assertj)` — json-unit-assertj 3.5.0 (REC-17)

---

## Resources

| File | Purpose |
|---|---|
| `src/test/resources/pi4-throttled.properties` | Pi 4 equivalent test profile: `disk.baseline.delay.ms=10`, `disk.spike.delay.ms=200`, `disk.spike.probability=0.005`, `sustained.duration.minutes=60`, `heap.budget.mb=256`. Loaded by IntegrationTestHarness when `pi.profile=throttled`. |

---

## Build Configuration

```kotlin
// Key entries from build.gradle.kts
tasks.test {
    enabled = project.hasProperty("piProfile")
    // When enabled: -Xmx256m, -Xms256m, -XX:ActiveProcessorCount=4, G1GC
    // System properties: pi.profile, sustained.minutes (optional)
}
```

No `module-info.java` — this is a pure test module. Tests run on the classpath, not the module path.

---

## Constraints

- **No default `./gradlew check` participation.** Tests only run with `-PpiProfile=throttled`.
- **`NO_DIRECT_TIME_ACCESS` arch rule does NOT apply.** This module is not on `homesynapse-app`'s test classpath where the ArchUnit rule runs. Tests may use `Clock.systemUTC()`, `System.nanoTime()`, and `Instant.now()` directly (matches M3.4a `BurstLoadIT` precedent).
- **All production types accessed via testFixture factories.** `PersistenceTestHarness` wraps `SqlitePersistenceLifecycle`; `InProcessEventBusFactory` wraps `InProcessEventBus`. Never import package-private production types directly.
- **Decorator injection for test doubles.** `ThrottledWriteCoordinator` is injected via `PersistenceTestHarness.startThrottled()`, which internally uses the `Function<WriteCoordinator, WriteCoordinator>` decorator on `DatabaseExecutor`.

---

## Gotchas

**GOTCHA: @TempDir cleanup on Windows.** Crash-simulation tests (`CrashRecoveryIT`) use `@TempDir(cleanup = CleanupMode.NEVER)` because the abandoned harness holds SQLite file handles. Using `ON_SUCCESS` causes JUnit to throw IOException during cleanup. Temp directories accumulate across runs — OS handles eventual cleanup.

**GOTCHA: SLF4J not inherited from persistence.** The persistence module declares SLF4J API as `implementation`-scoped. The `java-test-fixtures` plugin does NOT propagate `implementation` deps to fixture consumers. This module requires its own `testImplementation(libs.slf4j.api)`.

**GOTCHA: `startThrottled` vs `startForCrashSimulation`.** Both call the same `startInternal` helper. The behavioral difference is in calling `abandon()` at the crash point, not in construction. The named factories provide semantic clarity for test authors.

**GOTCHA: Sustained test duration.** Default is 60 minutes. CI runs should use `-PsustainedMinutes=10`. The 10-minute run validates mechanism; the 60-minute run validates endurance. Pi4D1SpikeIT always runs 30 minutes regardless of the property (it has its own internal duration).

**GOTCHA: Event-count vs lag-bound assertions.** `Pi4SustainedLoadIT`'s event-count assertion is informational (lower-bound 25% of theoretical 100 ev/s × duration). The lag-bound assertion (≤50 events behind) is the load-bearing check that catches publisher stalls, write coordinator deadlocks, and projection backlog growth.

**GOTCHA: Two abandon paths coexist with different semantics (M3.7).** The codebase has TWO `abandon` mechanisms after M3.7, and they do different things:

| Path | Types | Behavior | Used By |
|---|---|---|---|
| **Production-grade (M3.7)** | `PersistenceFactory.abandon()`, `InProcessEventBus.abandon()`, `HomeSynapseCore.abandon()` → `HomeSynapseE2eHarness.abandon()` | Actively releases resources: closes JDBC connections, shuts down `DatabaseExecutor`, interrupts subscriber VTs (`SubscriberRuntime.close()`), clears bus registries, stops Javalin HTTP server, calls `SharedScheduler.shutdown()` (which `shutdownNow()`s its `ScheduledExecutorService`). | `HomeSynapseE2eHarness.abandon()` → `CrashRecoveryHttpIT` |
| **Flag-based (M3.4b, retained as-is)** | `PersistenceTestHarness.abandonForCrashSimulation()` → `IntegrationTestHarness.abandon()` | Sets a `volatile boolean abandoned = true` flag so `close()` becomes a no-op. Does NOT actively release JDBC connections, does NOT shut down executors, does NOT stop threads. | `IntegrationTestHarness.abandon()` → `CrashRecoveryIT` |

The flag-based path works for the M3.4 tests because `IntegrationTestHarness` builds a passive bus (no live subscriber VTs, no HTTP server, no `SharedScheduler`) — leaking the connections is recoverable because the test JVM exits at end-of-test and SQLite's automatic WAL recovery handles the uncheckpointed pages on the next `start`. The production-grade path is required for `HomeSynapseE2eHarness` because `HomeSynapseCore` owns live VTs and a bound HTTP socket; leaking them either fails the subsequent harness's HTTP port binding or hangs JUnit at temp-dir cleanup time on Windows. Both paths coexist until Doc 15 Layer 2 (unified harness) consolidates `IntegrationTestHarness` and `HomeSynapseE2eHarness`. **Do not "fix" one path to mirror the other** — the difference reflects the different harnesses they back. When adding a new HTTP-aware crash test, route through `HomeSynapseE2eHarness.abandon()`; when adding a new persistence-only crash test, route through `IntegrationTestHarness.abandon()`.

---

## Phase 3 Notes

*Module created M3.4a (2026-05-19). Extended M3.4b (2026-05-19). MODULE_CONTEXT populated during WUCP Phase 2 reconciliation.*

**M3.4a (5ae7912):** Created module. IntegrationTestHarness, BurstLoadIT (6 assertions), HeapBudgetIT (4 assertions). PersistenceTestHarness and InProcessEventBusFactory testFixture factories in their respective modules. Pi-profile gating in build.gradle.kts.

**M3.4b (adf04d2):** ThrottledWriteCoordinator + 9 unit tests. Three new IT tests (Pi4SustainedLoadIT, Pi4D1SpikeIT, CrashRecoveryIT). IntegrationTestHarness extended with startThrottled, startForCrashSimulation, abandon. PersistenceTestHarness extended with startWithWriteCoordinator, startThrottled, abandonForCrashSimulation. InProcessEventBusFactory extended with createWithMetrics. scripts/pi4-validation.sh. SLF4J dep.

**On the horizon:** M3.7 end-to-end integration tests (PLAN-M3 §11) will add tests to this module. M3.6d composition-root facade (`HomeSynapseCore`) may simplify IntegrationTestHarness by replacing manual wiring with the facade's `start()` method.
