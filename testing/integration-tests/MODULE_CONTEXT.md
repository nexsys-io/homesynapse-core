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

---

## Phase 3 Notes

*Module created M3.4a (2026-05-19). Extended M3.4b (2026-05-19). MODULE_CONTEXT populated during WUCP Phase 2 reconciliation.*

**M3.4a (5ae7912):** Created module. IntegrationTestHarness, BurstLoadIT (6 assertions), HeapBudgetIT (4 assertions). PersistenceTestHarness and InProcessEventBusFactory testFixture factories in their respective modules. Pi-profile gating in build.gradle.kts.

**M3.4b (adf04d2):** ThrottledWriteCoordinator + 9 unit tests. Three new IT tests (Pi4SustainedLoadIT, Pi4D1SpikeIT, CrashRecoveryIT). IntegrationTestHarness extended with startThrottled, startForCrashSimulation, abandon. PersistenceTestHarness extended with startWithWriteCoordinator, startThrottled, abandonForCrashSimulation. InProcessEventBusFactory extended with createWithMetrics. scripts/pi4-validation.sh. SLF4J dep.

**On the horizon:** M3.7 end-to-end integration tests (PLAN-M3 §11) will add tests to this module. M3.6d composition-root facade (`HomeSynapseCore`) may simplify IntegrationTestHarness by replacing manual wiring with the facade's `start()` method.
