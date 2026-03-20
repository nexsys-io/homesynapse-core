# Block O — Integration Runtime

**Module:** `integration/integration-runtime`
**Package:** `com.homesynapse.integration.runtime`
**Design Doc:** Doc 05 — Integration Runtime (§4, §5, §6, §8) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :integration:integration-runtime:compileJava`

---

## Strategic Context

The Integration Runtime is the supervisory layer that loads, isolates, monitors, and lifecycle-manages every integration adapter within the HomeSynapse process. It is the boundary between protocol-specific code (Zigbee coordinators, MQTT brokers, cloud APIs) and the event-sourced core. Without this subsystem, a misbehaving integration could starve the event bus of CPU, leak memory until the JVM crashes, or block system startup indefinitely — the exact failure modes that Home Assistant users experience when a single integration degrades the entire platform.

Block I (integration-api) defined the adapter-facing contracts: `IntegrationFactory`, `IntegrationAdapter`, `IntegrationContext`, `IntegrationDescriptor`, `HealthReporter`, `CommandHandler`, `HealthParameters`, `HealthState`, `PermanentIntegrationException`, lifecycle event types, and the supporting enums. Those types define *what an adapter declares and receives*. Block O defines *what the supervisor does with those declarations* — the runtime machinery that manages adapter lifecycles, tracks health, enforces restart intensity, classifies exceptions, allocates threads, and orchestrates shutdown.

This block produces the integration-runtime module's Phase 2 interface specification: the `IntegrationSupervisor` interface, the `IntegrationHealthRecord` data type, the `SlidingWindow` utility type, exception classification types, and the module descriptor. Phase 3 implements these interfaces — the health state machine logic, restart backoff, JFR monitoring, and thread allocation are all implementation concerns.

**Strategic importance:** The Integration Runtime directly serves the reliability battlefield (INV-RF-01 through INV-RF-06) and the trust battlefield (INV-TO-01 — observable behavior). Every Home Assistant user who has experienced a single broken integration degrading their entire system is a potential HomeSynapse customer. The supervisor is the mechanism that prevents that failure mode.

## Scope

**IN:** `IntegrationSupervisor` interface (the central supervisory contract), `IntegrationHealthRecord` record (per-integration health state snapshot), `SlidingWindow` record (sliding window for error/timeout/slow-call rate tracking), `ExceptionClassification` enum (transient vs permanent), module-info.java with correct requires and exports, build.gradle.kts verification. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references. All types from Doc 05 §8.1 and §8.2 that belong to the runtime module (not already in integration-api).

**OUT:** Implementation code. Tests. Health state machine transition logic. Restart backoff calculation. JFR `RecordingStream` monitoring setup. Thread allocation logic (platform vs virtual thread selection). ServiceLoader or direct-construction wiring. `ManagedHttpClient` implementation (resource control wrapper around `java.net.http.HttpClient`). Dependency graph topological sort. Shutdown orchestration sequencing. Event production enforcement interceptor. Telemetry routing logic. Integration-scoped `EntityRegistry`/`StateQueryService` filter wrappers. Configuration override resolution (YAML → descriptor → system defaults precedence). ArchUnit rules. Gradle module graph enforcement. Structured logging implementation. JFR metric emission.

---

## Locked Decisions

1. **The `IntegrationSupervisor` is an interface, not a class.** Doc 05 §8.1 lists it as the central supervisory contract. Phase 2 defines the interface signatures; Phase 3 implements it. The supervisor manages adapter lifecycle, health state machine, restart intensity, and thread allocation. It is consumed by the Startup/Lifecycle module (Doc 12) and the REST API (Doc 09) for integration management endpoints.

2. **`IntegrationHealthRecord` is a record in the runtime module, not integration-api.** Doc 05 §4.3 specifies this as the per-integration health state maintained by the supervisor in memory. It contains `HealthState` (from integration-api) but also supervisor-internal fields like `consecutiveFailures`, `suspensionCycleCount`, `totalSuspendedTime`, and sliding window references. Adapters do not see this type — they interact with health via `HealthReporter` (in integration-api). The record captures a point-in-time snapshot; the implementation maintains mutable tracking state internally.

3. **`SlidingWindow` is a record in the runtime module.** Doc 05 §3.4 specifies a sliding window of 20 calls for error/timeout/slow-call rate calculations. The `SlidingWindow` record captures the window state as a snapshot: `size` (configured window capacity), `count` (events currently in window), `rate` (count / size as a double 0.0–1.0). Phase 3 uses a `ConcurrentLinkedDeque<Instant>` internally; the record captures the observable state. This is analogous to how `WsClientState` captures mutable connection state as an immutable snapshot record.

4. **`ExceptionClassification` is an enum in the runtime module.** Doc 05 §3.7 defines a deterministic exception classification table. The enum values are `TRANSIENT` (restart with backoff) and `PERMANENT` (transition to FAILED). The classification logic is Phase 3 implementation; the enum defines the possible outcomes. A third value `SHUTDOWN_SIGNAL` distinguishes `InterruptedException` and `ClosedByInterruptException` from true failures — the supervisor does not restart when the exception was caused by its own shutdown signal.

5. **`IntegrationSupervisor` methods return `CompletableFuture<Void>` for async lifecycle operations.** Doc 05 §5 specifies that `start()` returns a `CompletableFuture<Void>` that completes when all enabled integrations have been started. The `stop()` method is synchronous — it blocks until all adapters have stopped or timed out (Doc 05 §3.6). Individual adapter operations (`startIntegration`, `stopIntegration`, `restartIntegration`) also return `CompletableFuture<Void>` for use by the REST API's integration management endpoints (Doc 09 §7).

6. **No `ServiceLoader` discovery — direct construction per DECIDE-04.** Block I's `IntegrationFactory` Javadoc documents DECIDE-04 (2026-03-20): direct factory construction was chosen over ServiceLoader discovery. With a single MVP integration (Zigbee), ServiceLoader adds runtime scanning overhead for zero benefit. The `IntegrationSupervisor` accepts a `List<IntegrationFactory>` at construction — the application module assembles this list explicitly. Doc 05 §3.10 specifies ServiceLoader, but DECIDE-04 overrides this for MVP. If post-MVP community integrations require dynamic discovery, LTD-17 can be amended.

7. **JPMS Default Rule (LD#10 from Block N):** All inter-module `requires` directives default to `requires transitive`. Use non-transitive `requires` ONLY when you can confirm that NO types from the required module appear in any record component, method parameter, return type, exception superclass, or throws clause in this module's exported API.

    **Analysis for this module:** `IntegrationSupervisor` methods reference `IntegrationFactory` (from integration-api) in the `register` parameter list. `IntegrationHealthRecord` holds `IntegrationId` (from platform-api, transitively via integration-api), `HealthState` (from integration-api), and `SlidingWindow` (local to runtime). The module's primary dependency is integration-api, which already `requires transitive` all the core modules (event-model, device-model, state-store, persistence, configuration, platform-api, java.net.http).

    Therefore: `requires transitive com.homesynapse.integration` because integration-api types (`IntegrationFactory`, `IntegrationAdapter`, `IntegrationDescriptor`, `HealthState`, `IntegrationHealthRecord`'s `HealthState` field, etc.) appear throughout the exported API surface. The `event-model` dependency in `build.gradle.kts` (`implementation`) is for Phase 3 internal usage (producing lifecycle events via `EventPublisher`) — it does NOT need `requires transitive` in Phase 2 because no event-model types appear in the runtime module's Phase 2 exported API signatures.

    Module-info: `module com.homesynapse.integration.runtime { requires transitive com.homesynapse.integration; exports com.homesynapse.integration.runtime; }`

8. **No cross-module updates.** Block O does not modify any files in other modules. The integration-runtime module is a consumer of integration-api types. The existing `build.gradle.kts` already has `api(project(":integration:integration-api"))` and `implementation(project(":core:event-model"))` — these are correct and should not be changed.

9. **`IntegrationSupervisor` health query methods return unmodifiable snapshots.** `health(IntegrationId)` returns an `Optional<IntegrationHealthRecord>`, and `allHealth()` returns a `Map<IntegrationId, IntegrationHealthRecord>` (unmodifiable). These are consumed by the REST API for the integration health endpoints (Doc 09 §3.2, §7). The map is keyed by `IntegrationId` (from platform-api) — this type is transitively available through integration-api.

10. **`SlidingWindow` uses `double` for rate, not `BigDecimal`.** The health score calculation (Doc 05 §3.4) uses floating-point arithmetic with no currency or precision requirements. The sliding window rate is `count / size` — a simple ratio that doesn't accumulate rounding errors. `double` is appropriate and avoids unnecessary complexity.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Enums (no dependencies beyond integration-api)

| File | Type | Notes |
|------|------|-------|
| `ExceptionClassification.java` | enum (3 values) | Doc 05 §3.7: `TRANSIENT` (restart with backoff — IOException, SocketException, SocketTimeoutException, unknown RuntimeException), `PERMANENT` (transition to FAILED — ConfigurationException, AuthenticationException, UnsupportedOperationException, OutOfMemoryError, other Error, PermanentIntegrationException), `SHUTDOWN_SIGNAL` (do not restart — InterruptedException, ClosedByInterruptException during active shutdown). Javadoc: deterministic classification of exceptions escaping the adapter→supervisor boundary. The supervisor classifies every exception before deciding whether to restart or transition to FAILED. Unknown RuntimeException defaults to TRANSIENT to prevent the Home Assistant anti-pattern of permanent failure on unexpected exception types. The `SHUTDOWN_SIGNAL` value distinguishes exceptions caused by the supervisor's own shutdown from genuine adapter failures — when the supervisor sets the per-adapter `shuttingDown` flag, SocketException and IOException are reclassified from TRANSIENT to SHUTDOWN_SIGNAL (Doc 05 §3.7). Thread-safe (enum). |

### Group 2: SlidingWindow Record (no dependencies beyond Group 1)

| File | Type | Notes |
|------|------|-------|
| `SlidingWindow.java` | record (3 fields) | Doc 05 §3.4: `size` (int, the configured window capacity — default 20 per HealthParameters.healthWindowSize()), `count` (int, events currently in the window — 0 to size inclusive), `rate` (double, `count / size` as 0.0 to 1.0; 0.0 when size is 0). Javadoc: point-in-time snapshot of a sliding window used for health score calculation. The supervisor maintains three sliding windows per integration: error events, timeout events, and slow-call events. The rate is the proportion of events in the window that match the tracked condition: `errorRate` = errors / windowSize, `timeoutRate` = timeouts / windowSize, `slowCallRate` = slow calls / windowSize. Phase 3 uses a `ConcurrentLinkedDeque<Instant>` internally; this record captures the observable state for health record snapshots and REST API exposure. Compact constructor: validate `size >= 0`, `count >= 0`, `count <= size`, `rate >= 0.0 && rate <= 1.0`. Thread-safe (immutable record). |

### Group 3: IntegrationHealthRecord (depends on Group 2 and integration-api)

| File | Type | Notes |
|------|------|-------|
| `IntegrationHealthRecord.java` | record (12 fields) | Doc 05 §4.3: `integrationId` (IntegrationId, non-null — from com.homesynapse.platform.identity via integration-api), `state` (HealthState, non-null — HEALTHY, DEGRADED, SUSPENDED, FAILED), `healthScore` (double — 0.0 to 1.0, weighted composite per Doc 05 §3.4), `lastHeartbeat` (Instant, non-null — timestamp of last `HealthReporter.reportHeartbeat()` call), `lastKeepalive` (Instant, **@Nullable** — timestamp of last successful protocol-level keepalive; null if no keepalive reported yet), `stateChangedAt` (Instant, non-null — timestamp of last health state transition), `consecutiveFailures` (int — count of consecutive failures since last stable period; resets to 0 after stable uptime threshold), `suspensionCycleCount` (int — number of SUSPENDED→probe→SUSPENDED cycles; resets on recovery to HEALTHY), `totalSuspendedTime` (Duration, non-null — cumulative time spent in SUSPENDED state), `errorWindow` (SlidingWindow, non-null — error rate sliding window snapshot), `timeoutWindow` (SlidingWindow, non-null — timeout rate sliding window snapshot), `slowCallWindow` (SlidingWindow, non-null — slow-call rate sliding window snapshot). Javadoc: per-integration health state snapshot maintained by the supervisor in memory. Not persisted — reconstructed on startup from the integration's current state. Exposed via `IntegrationSupervisor.health(IntegrationId)` and consumed by the REST API integration health endpoints (Doc 09 §3.2, §7). The `healthScore` is calculated per the weighted formula: `0.30 × (1 - errorRate) + 0.20 × (1 - timeoutRate) + 0.15 × (1 - slowCallRate) + 0.20 × dataFreshnessScore + 0.15 × resourceComplianceScore`. Note: `dataFreshnessScore` and `resourceComplianceScore` are computed from `lastHeartbeat` and JFR metrics respectively — they are not stored as fields because they are time-dependent and would be stale immediately. Phase 3 computes them on demand when `healthScore` is updated. Compact constructor: `integrationId`, `state`, `lastHeartbeat`, `stateChangedAt`, `totalSuspendedTime`, `errorWindow`, `timeoutWindow`, `slowCallWindow` non-null. Validate `healthScore >= 0.0 && healthScore <= 1.0`, `consecutiveFailures >= 0`, `suspensionCycleCount >= 0`. Thread-safe (immutable record). |

### Group 4: IntegrationSupervisor Interface (depends on Groups 1–3 and integration-api)

| File | Type | Notes |
|------|------|-------|
| `IntegrationSupervisor.java` | interface | Doc 05 §8.1: The central supervisory contract managing adapter lifecycle, health state machine, restart intensity, thread allocation, and shutdown. Consumed by the Startup/Lifecycle module (Doc 12 — `start()`, `stop()`), the REST API (Doc 09 — integration management endpoints), and the Observability module (Doc 11 — composite health indicator). **Methods:** |

**IntegrationSupervisor methods:**

| Method | Signature | Notes |
|--------|-----------|-------|
| `start` | `CompletableFuture<Void> start(List<IntegrationFactory> factories)` | Doc 05 §5, INV-RF-03: Discovers integrations from the provided factory list, validates descriptors, builds dependency graph, performs topological sort (Kahn's algorithm with cycle detection per AMD-14, Doc 05 §3.13), allocates threads per `IoType`, constructs `IntegrationContext` per adapter, and starts all enabled integrations concurrently. Returns a future that completes when all integrations have been started — where "started" means initialize() returned or timed out, NOT that the adapter connected to its external device. Failed integrations are marked FAILED; the system proceeds. A failing integration NEVER blocks startup (INV-RF-03). The `factories` parameter is never null and not empty. |
| `stop` | `void stop()` | Doc 05 §3.6: Initiates graceful shutdown of all running integrations. Stops in reverse startup order (dependents before dependencies). For virtual thread adapters: `Thread.interrupt()`. For platform thread serial adapters: `serialPort.closePort()` equivalent. Each adapter gets `shutdownGracePeriod` (default 10s) to complete. Abandoned adapters are logged. Produces `integration_stopped` events for clean shutdowns. Blocks until all adapters stopped or timed out. |
| `startIntegration` | `CompletableFuture<Void> startIntegration(IntegrationId id)` | Doc 05 §3.4 FAILED→LOADING: Manual restart of a FAILED integration via REST API or config reload. Resets health counters, transitions through LOADING→INITIALIZING→RUNNING. Produces `integration_restarted` event. Throws `IllegalStateException` if the integration is not in FAILED state. |
| `stopIntegration` | `CompletableFuture<Void> stopIntegration(IntegrationId id)` | Stops a single running integration. Used by REST API integration management. |
| `restartIntegration` | `CompletableFuture<Void> restartIntegration(IntegrationId id)` | Stop then start. Convenience method for REST API. |
| `health` | `Optional<IntegrationHealthRecord> health(IntegrationId id)` | Returns the current health record snapshot for the specified integration, or empty if the integration is not registered. The returned record is immutable and represents a point-in-time snapshot. |
| `allHealth` | `Map<IntegrationId, IntegrationHealthRecord> allHealth()` | Returns an unmodifiable map of all registered integrations' health records. Consumed by REST API integration list/health endpoints and the Observability module's composite health indicator (Doc 11 §11.3). |
| `isRunning` | `boolean isRunning(IntegrationId id)` | Returns true if the integration is in RUNNING state. |
| `registeredIntegrations` | `Set<IntegrationId> registeredIntegrations()` | Returns the set of all registered integration IDs (unmodifiable). |

Javadoc: The `IntegrationSupervisor` is the single point of control for all integration adapter lifecycles. It implements the OTP-style one-for-one supervision strategy (Doc 05 §3.4, L4): only the failed adapter is restarted; all other integrations continue operating. Restart intensity is tracked per integration — 3 restarts within 60 seconds (configurable via `HealthParameters`) escalates to FAILED. The supervisor does NOT implement the health state machine logic in Phase 2 — it defines the query and lifecycle interfaces. Phase 3 implements the transition guards, health score calculation, JFR monitoring, and thread allocation.

Thread-safety: The supervisor is thread-safe. Multiple threads (REST API handlers, lifecycle module, health monitor) may call methods concurrently. All returned collections and records are immutable snapshots.

`@see IntegrationFactory`, `@see IntegrationAdapter`, `@see IntegrationContext`, `@see IntegrationHealthRecord`, `@see HealthState`, `@see HealthParameters`

### Group 5: Module Descriptor and Build Configuration

| File | Notes |
|------|-------|
| `module-info.java` | `module com.homesynapse.integration.runtime { requires transitive com.homesynapse.integration; exports com.homesynapse.integration.runtime; }`. Single `requires transitive` directive for integration-api. The `event-model` dependency (for producing lifecycle events via `EventPublisher`) is a Phase 3 implementation import — no event-model types appear in the runtime module's Phase 2 exported API signatures. Phase 3 will add `requires com.homesynapse.event` (non-transitive, for EventPublisher.publish()). |

**Build configuration verification:**

The existing `build.gradle.kts` already has the correct dependencies:

```kotlin
plugins {
    id("homesynapse.java-conventions")
    `java-library`
}

description = "Integration runtime: supervisor, health state machine, thread allocation"

dependencies {
    api(project(":integration:integration-api"))
    implementation(project(":core:event-model"))
}
```

The `api(project(":integration:integration-api"))` is correct — integration-api types appear in the runtime's public API. The `implementation(project(":core:event-model"))` is correct — event-model is only used internally for producing lifecycle events in Phase 3. **Do NOT change any existing dependencies.**

---

## File Placement

All integration-runtime types go in: `integration/integration-runtime/src/main/java/com/homesynapse/integration/runtime/`
Module info: `integration/integration-runtime/src/main/java/module-info.java` (create new)

The existing `package-info.java` at `integration/integration-runtime/src/main/java/com/homesynapse/integration/runtime/package-info.java` is in the correct package (`com.homesynapse.integration.runtime`). It should be kept and its Javadoc updated to describe the runtime module's purpose. Do NOT delete it — unlike the websocket-api scaffold, this package-info is in the right package.

---

## Cross-Module Type Dependencies

**Phase 2 imports from `com.homesynapse.integration` (integration-api):**
- `IntegrationFactory` — parameter type in `IntegrationSupervisor.start(List<IntegrationFactory>)`
- `IntegrationId` — transitively available via integration-api → platform-api. Used in `IntegrationSupervisor` method parameters and `IntegrationHealthRecord` field.
- `HealthState` — from integration-api. Used in `IntegrationHealthRecord.state` field.
- `HealthParameters` — referenced in Javadoc (health thresholds). Not directly in Phase 2 type signatures, but conceptually coupled.

**Phase 2 imports from `java.time`:** `Instant` and `Duration` in `IntegrationHealthRecord` fields.

**Phase 2 imports from `java.util`:** `Optional`, `Map`, `Set`, `List` in `IntegrationSupervisor` return types.

**Phase 2 imports from `java.util.concurrent`:** `CompletableFuture` in `IntegrationSupervisor` return types.

**Phase 2: NO imports from event-model, event-bus, persistence, configuration, state-store, or device-model.** All event production, health monitoring, and context construction are Phase 3 implementation concerns. The runtime's Phase 2 API surface references only integration-api types, JDK standard types, and its own types (SlidingWindow, IntegrationHealthRecord, ExceptionClassification).

**Phase 3 will add imports for:**
- `com.homesynapse.event` — `EventPublisher` (for producing lifecycle events), `EventEnvelope`, `DomainEvent`
- `com.homesynapse.event.bus` — `EventBus` (for command dispatch subscription on adapter behalf)
- `com.homesynapse.state` — `StateQueryService` (for integration-scoped wrappers)
- `com.homesynapse.device` — `EntityRegistry` (for integration-scoped wrappers)
- `com.homesynapse.persistence` — `TelemetryWriter` (for context construction)
- `com.homesynapse.config` — `ConfigurationAccess` (for context construction)
- `jdk.jfr` — `RecordingStream`, JFR event types (for health monitoring mechanism 3)

**Exported to (downstream consumers):**
- `com.homesynapse.lifecycle` (lifecycle module) — `IntegrationSupervisor.start()`, `IntegrationSupervisor.stop()`
- `com.homesynapse.api.rest` (REST API) — `IntegrationSupervisor.health()`, `allHealth()`, `startIntegration()`, `stopIntegration()`, `restartIntegration()`
- `com.homesynapse.observability` (observability module) — `IntegrationSupervisor.allHealth()` for composite health indicator (Doc 11 §11.3)

---

## Javadoc Standards

Per Sprint 1–4 lessons (Blocks A–N), plus integration-runtime-specific requirements:

1. Every `@param` documents nullability (`never {@code null}` or `{@code null} if...`)
2. Every type has `@see` cross-references to related types (within module and to integration-api types)
3. Thread-safety explicitly stated on all interfaces and records ("Thread-safe" or "Not thread-safe")
4. Class-level Javadoc explains the "why" — what role this type plays in the Integration Runtime architecture
5. Reference Doc 05 sections in class-level Javadoc (e.g., `@see <a href="...">Doc 05 §3.4 Health State Machine</a>`)
6. `IntegrationSupervisor` Javadoc: document OTP-style one-for-one supervision, restart intensity semantics, async vs sync methods, startup/shutdown ordering
7. `IntegrationHealthRecord` Javadoc: document the weighted health score formula, the three sliding windows, which fields are non-null, and that `dataFreshnessScore`/`resourceComplianceScore` are computed on demand (not stored)
8. `SlidingWindow` Javadoc: document the relationship to `HealthParameters.healthWindowSize()`, the rate calculation, and that Phase 3 uses `ConcurrentLinkedDeque<Instant>` internally
9. `ExceptionClassification` Javadoc: document each classification's supervisor action, cite the full exception mapping table from Doc 05 §3.7, document the shutdown-aware reclassification behavior
10. `package-info.java` Javadoc: update to describe the module's purpose (supervisory layer), its relationship to integration-api, and the three main responsibilities (lifecycle, health, thread allocation)

---

## Execution Order

1. **Update `package-info.java`** — Replace the existing one-line Javadoc with a comprehensive package description covering the module's purpose, relationship to integration-api (Block I), and the three main Phase 3 responsibilities.
2. **Create `ExceptionClassification.java`** — Enum with 3 values. No dependencies.
3. **Create `SlidingWindow.java`** — Record with 3 fields and validation. No dependencies beyond JDK.
4. **Create `IntegrationHealthRecord.java`** — Record with 12 fields. Imports from integration-api (`IntegrationId`, `HealthState`) and Group 2 (`SlidingWindow`).
5. **Create `IntegrationSupervisor.java`** — Interface with 9 methods. Imports from integration-api (`IntegrationFactory`, `IntegrationId`) and Group 3 (`IntegrationHealthRecord`).
6. **Create `module-info.java`** — Module descriptor with `requires transitive com.homesynapse.integration`.
7. **Run compile gate:** `./gradlew :integration:integration-runtime:compileJava` then `./gradlew compileJava` (full project).

---

## File Summary

| # | File | Type | Fields/Methods | Lines (est.) |
|---|------|------|----------------|-------------|
| 1 | `package-info.java` | package Javadoc | — | ~25 |
| 2 | `ExceptionClassification.java` | enum (3 values) | TRANSIENT, PERMANENT, SHUTDOWN_SIGNAL | ~60 |
| 3 | `SlidingWindow.java` | record (3 fields) | size, count, rate + validation | ~55 |
| 4 | `IntegrationHealthRecord.java` | record (12 fields) | integrationId through slowCallWindow + validation | ~120 |
| 5 | `IntegrationSupervisor.java` | interface (9 methods) | start, stop, startIntegration, stopIntegration, restartIntegration, health, allHealth, isRunning, registeredIntegrations | ~180 |
| 6 | `module-info.java` | module descriptor | requires transitive + exports | ~15 |

**Estimated total:** ~455 lines across 6 files (including package-info update). This is a smaller block than recent ones (Block N: 26 files, ~1400 lines). The integration-runtime's Phase 2 surface is narrow — the heavy lifting (health state machine, restart backoff, thread allocation, JFR monitoring) is all Phase 3 implementation.

---

## Lessons Incorporated

**From coder-lessons.md (2026-03-20 — Block N):**
- **JPMS `requires transitive` default rule:** Applied in LD#7 analysis. `requires transitive com.homesynapse.integration` because integration-api types pervade the exported API. The non-transitive `event-model` is Phase 3 only.
- **Nullable collection defensive copy:** Not applicable to this block (no nullable collection fields in Phase 2 types).

**From coder-lessons.md (2026-03-15 — Sprint 1):**
- **Empty package exports:** The existing scaffold has `package-info.java` but the `module-info.java` doesn't exist yet (no `exports` clause to worry about). Creating both in this block avoids the empty-package issue.
- **Javadoc quality pass:** Build the quality pass into execution — don't defer it. Check `@param` nullability, `@see` cross-refs, thread-safety statements.

**From cross-agent-notes (2026-03-20):**
- **DECIDE-04 (direct construction over ServiceLoader):** The `IntegrationSupervisor.start()` method accepts `List<IntegrationFactory>` directly — no ServiceLoader discovery. This is consistent with the integration-api's `IntegrationFactory` Javadoc.
- **Path changes:** Design docs are at `homesynapse-core-docs/design/`, not `nexsys-hivemind/context/design/`.
- **18 LTDs (not 17).** LTD-18 exists.

**From pm-lessons.md (2026-03-20):**
- **Compile gate deferred if blocked by infrastructure:** If the VM runs out of disk space again, document the deferral in the handoff and flag it for manual execution. Do NOT silently skip it.

---

## Constraints Active in This Block

| Constraint | Application |
|---|---|
| LTD-01 | Virtual threads for network adapters; platform threads for serial (JNI). Thread allocation is Phase 3 but the interface contracts must not preclude it. |
| LTD-04 | `IntegrationId` (ULID) used throughout — transitively available via integration-api. |
| LTD-15 | JFR metrics (Phase 3). The Phase 2 interfaces don't reference JFR types. |
| LTD-16 | In-process compiled modules. DECIDE-04 overrides ServiceLoader for MVP. |
| LTD-17 | Build-enforced API boundaries. integration-runtime exports only `com.homesynapse.integration.runtime`. |
| INV-RF-01 | Integration isolation — the supervisor catches all exceptions escaping the adapter boundary. `ExceptionClassification` defines the response. |
| INV-RF-02 | Resource quotas — `IntegrationHealthRecord` tracks resource compliance. Implementation in Phase 3. |
| INV-RF-03 | Startup independence — `IntegrationSupervisor.start()` returns `CompletableFuture<Void>` that completes regardless of external device connectivity. |
| INV-RF-06 | Graceful degradation — four-state health model (HEALTHY→DEGRADED→SUSPENDED→FAILED) with asymmetric hysteresis. |
| INV-TO-01 | Observable behavior — every health transition produces an event (Phase 3 implementation via EventPublisher). |
| INV-HO-04 | Self-explaining errors — `IntegrationHealthRecord` exposed via REST API; `ExceptionClassification` documents user-facing failure reasons. |
| INV-CE-02 | Zero-config — `HealthParameters.defaults()` provides sensible defaults for all supervisor behavior. |
