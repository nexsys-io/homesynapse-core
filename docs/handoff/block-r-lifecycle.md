# Block R — Startup, Lifecycle & Shutdown

**Module:** `lifecycle/lifecycle`
**Package:** `com.homesynapse.lifecycle`
**Design Doc:** Doc 12 — Startup, Lifecycle & Shutdown (§3, §4, §5, §7, §8) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :lifecycle:lifecycle:compileJava`

---

## Strategic Context

The Startup, Lifecycle & Shutdown subsystem is the process-level orchestrator for HomeSynapse Core. It owns the ordered initialization of all subsystems from cold start (sequencing six phases with explicit ordering contracts), the runtime health and watchdog protocol that feeds systemd's liveness detection, and the graceful shutdown sequence that preserves data integrity and respects systemd's timeout model.

This subsystem answers three questions that determine whether the system can run reliably for years without human intervention:

1. **In what order do subsystems initialize, and what happens when one fails?** Without defined ordering, subsystems can initialize in wrong order — the Automation Engine before the State Store has recovered produces incorrect evaluations against empty state. Startup fails intermittently, unreproducibly.

2. **How does the platform supervisor know the process is alive?** The health loop calls the HealthReporter's watchdog method every 30 seconds. If the loop stops, systemd's watchdog kills and restarts the process — the liveness guarantee.

3. **How does the system stop without losing data?** The shutdown sequence reverses initialization order and respects grace periods — the Persistence Layer's WAL checkpoint completes, databases close cleanly, the unclean shutdown marker is removed.

Block R produces the lifecycle module's Phase 2 interface specification: the `SystemLifecycleManager` (top-level orchestrator), the `HealthReporter` interface (platform abstraction for systemd), the `PlatformPaths` interface (directory resolution), and the data types that track lifecycle state (`LifecyclePhase`, `SubsystemState`, `SubsystemStatus`, `SystemHealthSnapshot`).

**Strategic importance:** This module's contracts (C12-01 through C12-10) are dependencies for every other subsystem's initialization logic. The lifecycle module publishes the initialization order that other subsystems depend on (§3). The Observability module (Doc 11 §3.3) depends on HealthAggregator having a HealthReporter to feed watchdog and health status (§3.10). The startup grace period logic (Doc 12 §3.6) gates the system's readiness classification — a system in STARTING state with subsystems in grace period reports DEGRADED health until grace periods expire.

## Scope

**IN:** Two enums (`LifecyclePhase`, `SubsystemStatus`), one utility class (`LifecycleEventType` — event type constants), two data records (`SubsystemState`, `SystemHealthSnapshot`), one service interface (`SystemLifecycleManager`), module-info.java, updated package-info.java, build.gradle.kts verification. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references for all types. **Note:** `HealthReporter` and `PlatformPaths` already exist in `platform-api` (Block F) — the lifecycle module consumes them, it does not redefine them.

**OUT:** Implementation code. Tests. Actual phase execution logic. Subsystem initialization and shutdown mechanics (those belong to individual subsystems). Integration supervisor management (Doc 05). Health aggregation evaluation (Doc 11). Lifecycle event production into the event bus (owned by EventPublisher).

---

## Files to Read Before Starting

Read these files in this order before writing any code:

1. **NexSys Coder skill** — Coding standards, Java patterns, Javadoc conventions
2. **Doc 12 — Startup, Lifecycle & Shutdown** (§3 Architecture, §4 Data Model, §5 Contracts, §7 Interactions, §8 Key Interfaces) — the governing design document. Read thoroughly.
3. **`observability/observability/MODULE_CONTEXT.md`** — `HealthAggregator`, `HealthContributor`, `HealthTier`, `LifecycleState`, `SystemHealth` types that this module's initialization logic interacts with
4. **`core/event-model/MODULE_CONTEXT.md`** — `EventPublisher`, lifecycle events (`system.starting`, `system.ready`, `system.stopping`, `system.stopped`, etc.)
5. **`platform/platform-api/MODULE_CONTEXT.md`** — `Ulid` type used in various identity fields
6. **`config/configuration/MODULE_CONTEXT.md`** — `ConfigurationService` invoked during Phase 1

---

## Locked Decisions

1. **Six initialization phases in fixed sequence.** Doc 12 §3 defines phases BOOTSTRAP (Phase 0), FOUNDATION (Phase 1), DATA_INFRASTRUCTURE (Phase 2), CORE_DOMAIN (Phase 3), OBSERVABILITY (Phase 4), EXTERNAL_INTERFACES (Phase 5), INTEGRATIONS (Phase 6), then RUNNING, SHUTTING_DOWN, STOPPED. No subsystem may depend on a subsystem that initializes in a later phase (C12-01). The ordering is documented and testable.

2. **Shutdown order is reverse initialization order.** Doc 12 §3.9, contract C12-02: subsystems shut down in reverse phase order (Phase 6 first, Phase 0 last), and within a phase, in reverse initialization order. This guarantees dependencies are still available during shutdown.

3. **Watchdog notification within WatchdogSec/2.** Doc 12 §3.10, contract C12-03: the health loop calls `reportWatchdog()` at least once every `WatchdogSec / 2` seconds (30 seconds for default `WatchdogSec=60`). If the process fails to maintain this cadence, systemd kills and restarts it. This is the liveness guarantee.

4. **Fatal failures exit immediately; non-fatal failures degrade gracefully.** Doc 12 §6: Configuration System, Persistence Layer, Event Bus, Device Model, State Store, Automation Engine, REST API failure is fatal (process exits with diagnostic). Observability, WebSocket API, integration adapter failures are non-fatal (system reports DEGRADED health and continues).

5. **READY=1 notification happens at end of Phase 5, not Phase 6.** Doc 12 §3.7, contract C12-08: the REST and WebSocket APIs are operational, `reportReady()` is called. Integrations start after READY (Phase 6), allowing users to access the dashboard before device connectivity is established. This satisfies INV-RF-03 (no external blocking during startup).

6. **Total shutdown grace period is 30 seconds.** Doc 12 §3.9 grace period budget (C12-06): Integration drain (10s), API drain (5s), core subsystem flush (10s), Persistence close (3s), Observability + logging (2s). Subsystems exceeding their budget are forcibly closed.

7. **PlatformPaths and HealthReporter are resolved once during Phase 0 and immutable thereafter.** Doc 12 §3.2 and §3.9, contract C12-10: Phase 0 determines which `PlatformPaths` implementation to use (`LinuxSystemPaths` or development-mode paths) and which `HealthReporter` implementation to use (`SystemdHealthReporter` or `NoOpHealthReporter`). These selections do not change for the lifetime of the process.

8. **Unclean shutdown marker: written after Phase 2, removed after Phase 12.** Doc 12 §3.4 step 2.3 and §3.9 step 11, contract C12-07: the marker file `.unclean_shutdown` is written in the data directory after Persistence Layer and Event Bus initialize. On startup, its presence indicates the previous shutdown was not graceful. The marker is removed at the end of the shutdown sequence (step 11) after all subsystems are shut down successfully.

9. **Lifecycle events use the `system.*` event type namespace.** Doc 12 §4.4: `system.starting`, `system.subsystem_initialized`, `system.subsystem_failed`, `system.ready`, `system.health_changed`, `system.stopping`, `system.stopped`. Each carries specific payload fields listed in §4.4.

10. **JPMS Default Rule (LD#10 from Block N):** All inter-module `requires` directives default to `requires transitive`. Use non-transitive `requires` ONLY when you can confirm that NO types from the required module appear in any record component, method parameter, return type, exception superclass, or throws clause in this module's exported API.

    **Analysis for this module:**
    - `observability` types appear in exported API: `HealthStatus` is a field type in `SubsystemState.healthState` and `SystemHealthSnapshot.aggregatedHealth`. Therefore: `requires transitive com.homesynapse.observability` — consumers need HealthStatus at minimum.
    - `event-model` types: No event-model types appear in lifecycle's Phase 2 exported API (EventPublisher is Phase 3 implementation usage only). Per LD#10 default, kept as `requires transitive com.homesynapse.event` to avoid module-info revision in Phase 3.
    - `platform-api` types: `HealthReporter` and `PlatformPaths` live in `com.homesynapse.platform` (Block F). They do not appear in lifecycle's type signatures, but consumers of lifecycle will need them transitively. Per LD#10 default: `requires transitive com.homesynapse.platform`.
    - `config` types: `ConfigurationService` is called but its types do not appear in lifecycle module's exported API signatures (it's Phase 3 usage). Non-transitive `requires` initially; Phase 3 may need to adjust.

    **build.gradle.kts update required:**
    ```kotlin
    dependencies {
        api(project(":observability:observability"))
        api(project(":core:event-model"))
        api(project(":platform:platform-api"))
        implementation(project(":config:configuration"))
    }
    ```

    **module-info.java:**
    ```java
    module com.homesynapse.lifecycle {
        requires transitive com.homesynapse.observability;
        requires transitive com.homesynapse.event;
        requires transitive com.homesynapse.platform;

        exports com.homesynapse.lifecycle;
    }
    ```

    Rationale: `observability` exports HealthStatus (used in SubsystemState, SystemHealthSnapshot); `event` and `platform` per LD#10 default (Phase 3 will need them, no compelling reason for non-transitive).

11. **HealthReporter implementations:** `SystemdHealthReporter` is selected when `$NOTIFY_SOCKET` environment variable is set (systemd process notification). `NoOpHealthReporter` is selected otherwise (development, non-systemd environments). The selection logic lives in Phase 0 (§3.2 step 0.5). Both implementations satisfy the same `HealthReporter` interface.

12. **PlatformPaths implementations:** `LinuxSystemPaths` is selected when `/opt/homesynapse/` exists and the process is running as the `homesynapse` user (FHS-compliant deployment). Otherwise, a development-mode implementation that creates directories under the current working directory is selected.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Enums and Constants (3 files — no inter-type dependencies)

#### Step 1: `LifecyclePhase.java`

Location: `lifecycle/lifecycle/src/main/java/com/homesynapse/lifecycle/LifecyclePhase.java`

```java
public enum LifecyclePhase {
    BOOTSTRAP,                // Phase 0: platform, logging, JFR, HealthReporter
    FOUNDATION,               // Phase 1: configuration loading and validation
    DATA_INFRASTRUCTURE,      // Phase 2: persistence, event bus
    CORE_DOMAIN,              // Phase 3: device model, state store, automation
    OBSERVABILITY,            // Phase 4: health aggregation, metrics
    EXTERNAL_INTERFACES,      // Phase 5: REST API, WebSocket API
    INTEGRATIONS,             // Phase 6: adapter discovery and startup
    RUNNING,                  // Steady state: health loop active
    SHUTTING_DOWN,            // Shutdown sequence in progress
    STOPPED                   // All resources released, process exiting
}
```

Javadoc: The ten sequential lifecycle states of a HomeSynapse process. Doc 12 §3–§3.9. BOOTSTRAP through INTEGRATIONS are initialization phases executing sequentially in order (C12-01). RUNNING is the steady-state phase where the health loop (§3.10) executes. SHUTTING_DOWN is the graceful shutdown sequence. STOPPED is the terminal state. The process moves through states in order — no backward transitions. A subsystem that initializes in phase N depends only on subsystems from phases 0 through N-1. Thread-safe (enum).

#### Step 2: `SubsystemStatus.java`

Location: `lifecycle/lifecycle/src/main/java/com/homesynapse/lifecycle/SubsystemStatus.java`

```java
public enum SubsystemStatus {
    NOT_STARTED,     // Subsystem has not begun initialization
    INITIALIZING,    // initialize() in progress
    RUNNING,         // Successfully initialized and operating
    FAILED,          // Initialization failed or runtime failure
    STOPPING,        // Shutdown in progress
    STOPPED          // Shutdown complete
}
```

Javadoc: The six possible states of a single subsystem within the overall lifecycle. Doc 12 §4.2. A subsystem transitions: NOT_STARTED → INITIALIZING (on start) → RUNNING (on success) or FAILED (on error). From RUNNING or FAILED, the subsystem may transition to STOPPING → STOPPED when the shutdown sequence executes. NOT_STARTED subsystems are skipped during shutdown — they never had resources to release. Thread-safe (enum).

#### Step 3: `LifecycleEventType.java` (enum for event type constants)

Location: `lifecycle/lifecycle/src/main/java/com/homesynapse/lifecycle/LifecycleEventType.java`

```java
public final class LifecycleEventType {
    private LifecycleEventType() {}

    public static final String SYSTEM_STARTING = "system.starting";
    public static final String SYSTEM_SUBSYSTEM_INITIALIZED = "system.subsystem_initialized";
    public static final String SYSTEM_SUBSYSTEM_FAILED = "system.subsystem_failed";
    public static final String SYSTEM_READY = "system.ready";
    public static final String SYSTEM_HEALTH_CHANGED = "system.health_changed";
    public static final String SYSTEM_STOPPING = "system.stopping";
    public static final String SYSTEM_STOPPED = "system.stopped";
}
```

Javadoc: Canonical registry of lifecycle event type string constants. Doc 12 §4.4. All are in the `system.*` namespace. These constants are used by the SystemLifecycleManager when publishing lifecycle events to the event bus. Thread-safe (class with no instance state).

### Group 2: Data Records (3 files — depend on Group 1 enums)

#### Step 4: `SubsystemState.java`

Location: `lifecycle/lifecycle/src/main/java/com/homesynapse/lifecycle/SubsystemState.java`

```java
public record SubsystemState(
    String subsystemName,                    // e.g., "persistence_layer", "event_bus"
    LifecyclePhase phase,                    // The phase this subsystem belongs to
    SubsystemStatus status,                  // INITIALIZING, RUNNING, FAILED, STOPPED
    HealthStatus healthState,                // HEALTHY, DEGRADED, UNHEALTHY (nullable before init)
    Duration initializationDuration,         // How long initialize() took
    String error                             // null if no error; diagnostic message if failed
) {}
```

Javadoc: Individual subsystem's state snapshot within the overall system lifecycle. Doc 12 §4.2. Tracks the subsystem's current phase assignment, initialization progress, health status (from the observability module), and any initialization error that occurred. `subsystemName` — human-readable identifier (e.g., "event-bus", "state-store"). `phase` — the LifecyclePhase this subsystem is assigned to. `status` — current status (NOT_STARTED, INITIALIZING, RUNNING, FAILED, STOPPING, STOPPED). `healthState` — the subsystem's health status as reported by its HealthContributor (HEALTHY, DEGRADED, UNHEALTHY, or null during early initialization before health contributor is available). `initializationDuration` — elapsed time from when initialization started to when it completed (success or failure). Null if the subsystem has not yet started initialization. `error` — diagnostic message if status is FAILED; null otherwise. Examples: "SQLite database integrity check failed", "Persistence Layer initialization timed out after 60 seconds", "Configuration schema validation found 3 fatal errors".

Compact constructor: `Objects.requireNonNull` on subsystemName, phase, status. `healthState` is explicitly nullable. `initializationDuration` is nullable (Duration is a class type). Validate: if status is FAILED, error must be non-null; if status is RUNNING or STOPPED, error must be null.

#### Step 5: `SystemHealthSnapshot.java`

Location: `lifecycle/lifecycle/src/main/java/com/homesynapse/lifecycle/SystemHealthSnapshot.java`

```java
public record SystemHealthSnapshot(
    Instant timestamp,
    Map<String, SubsystemState> subsystemStates,    // keyed by subsystemName
    HealthStatus aggregatedHealth,                   // HEALTHY, DEGRADED, UNHEALTHY
    Duration uptime,                                 // Time since system_ready event
    long eventStorePosition,                         // Current global_position in event log
    int entityCount,                                 // Number of active entities
    int integrationCount,                            // Number of running integrations
    int automationCount                              // Number of loaded automations
) {}
```

Javadoc: Point-in-time snapshot of the entire system's lifecycle and health state. Doc 12 §4.3. Captured at each health check iteration (§3.10) and made available via `SystemLifecycleManager.healthSnapshot()`. This data structure is consumed by REST API health endpoints and WebSocket health streaming (Doc 09, Doc 10). `timestamp` — when this snapshot was captured. `subsystemStates` — per-subsystem state detail keyed by subsystem identifier. `aggregatedHealth` — system-wide health status (worst-of across subsystems, per the three-tier model in Doc 11 §3.3). `uptime` — time since the system reached RUNNING state (or null if not yet RUNNING). `eventStorePosition` — the highest global_position in the event store at snapshot time. `entityCount` — number of Entity objects in the Device Registry (snapshot from Device Model). `integrationCount` — number of running integration adapters. `automationCount` — number of loaded automation definitions.

Compact constructor: `Objects.requireNonNull` on all fields except uptime, which is nullable (system not yet RUNNING). Defensive copy: `subsystemStates = Map.copyOf(subsystemStates)`.

### Group 3: Service Interface (1 file)

> **NOTE:** `HealthReporter` and `PlatformPaths` already exist in `com.homesynapse.platform` (created in Block F, `platform-api` module). The lifecycle module **consumes** these interfaces — it does NOT redefine them. They are available via `requires transitive com.homesynapse.platform`.

#### Step 6: `SystemLifecycleManager.java`

Location: `lifecycle/lifecycle/src/main/java/com/homesynapse/lifecycle/SystemLifecycleManager.java`

```java
public interface SystemLifecycleManager {
    void start() throws Exception;
    void shutdown(String reason) throws Exception;
    LifecyclePhase currentPhase();
    SystemHealthSnapshot healthSnapshot();
    Map<String, SubsystemState> subsystemStates();
}
```

Javadoc: Top-level orchestrator for the process lifecycle. Doc 12 §8.4. Sequences the six initialization phases (BOOTSTRAP through INTEGRATIONS), runs the runtime health loop (§3.10 after INTEGRATIONS completes), and executes the shutdown sequence (§3.9 when SIGTERM arrives). This is the primary interface — the main() method calls `systemLifecycleManager.start()` and registers a JVM shutdown hook that calls `shutdown()`.

`start()` — Execute the full startup sequence (Phases 0–6) synchronously. Blocks until initialization completes or a fatal error occurs. If a fatal failure occurs, calls `shutdown()` for any already-initialized subsystems and throws an exception (or calls `System.exit(1)` with diagnostic logging before throwing). Called from main() during application startup. Throws generic `Exception` — Phase 3 implementation will define specific exception types or wrap all exceptions in a `StartupFailedException`.

`shutdown(String reason)` — Execute the shutdown sequence. Safe to call from the JVM shutdown hook, from `start()` on fatal failure, or from an explicit admin API call. Concurrent calls are serialized — the first call executes shutdown; subsequent calls wait for completion. `reason` — human-readable reason for shutdown (e.g., "SIGTERM", "fatal error: persistence layer failed"). Non-null. Throws generic `Exception` during Phase 2; Phase 3 may refine exception types.

`currentPhase()` — Return the current lifecycle phase. Non-null. Returns STOPPED after shutdown completes. Called by REST API `/api/v1/system/health` endpoint and WebSocket health streaming.

`healthSnapshot()` — Return a point-in-time snapshot of the system's health state. Non-null. Captures subsystem states, aggregated health, uptime, entity count, etc. Called by REST health endpoint and WebSocket consumers. See `SystemHealthSnapshot` for details.

`subsystemStates()` — Return the per-subsystem state map (keyed by subsystem name). Non-null, unmodifiable (via `Map.copyOf()` or Collections.unmodifiableMap). This is the detailed breakdown; `healthSnapshot()` includes this plus additional metrics.

Thread-safe: `currentPhase()`, `healthSnapshot()`, and `subsystemStates()` are callable from any thread at any time. `start()` is called once from main(). `shutdown()` is safe to call from the shutdown hook and is synchronized internally.

`@see HealthReporter`
`@see PlatformPaths`
`@see SubsystemState`
`@see SystemHealthSnapshot`

### Group 4: Module Infrastructure (2 files)

#### Step 7: `module-info.java`

Location: `lifecycle/lifecycle/src/main/java/module-info.java`

```java
/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Process-level lifecycle orchestration for HomeSynapse Core.
 *
 * <p>This module owns the ordered initialization of all subsystems from cold start,
 * the runtime health and watchdog protocol that feeds systemd's liveness detection,
 * and the graceful shutdown sequence that preserves data integrity. The primary entry
 * point is {@link com.homesynapse.lifecycle.SystemLifecycleManager#start()}, called
 * from main(). The lifecycle module publishes the initialization order that all other
 * subsystems depend on, provides platform abstraction interfaces for systemd and
 * directory conventions, and tracks system-wide health state via {@link
 * com.homesynapse.lifecycle.SystemHealthSnapshot}.</p>
 *
 * @see com.homesynapse.lifecycle
 */
module com.homesynapse.lifecycle {
    requires transitive com.homesynapse.observability;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.platform;

    exports com.homesynapse.lifecycle;
}
```

JPMS analysis per LD#10 (default = `requires transitive`):
- `observability`: HealthStatus appears in SubsystemState.healthState and SystemHealthSnapshot.aggregatedHealth — types leak into exported API, `requires transitive` confirmed correct
- `event`: No event-model types appear in lifecycle's Phase 2 exported API (EventPublisher is Phase 3 usage only). Kept as `requires transitive` per LD#10 default — Phase 3 will need it and it avoids a module-info edit later
- `platform`: HealthReporter and PlatformPaths (from platform-api) are consumed by the lifecycle implementation but do not appear in lifecycle's own type signatures. Kept as `requires transitive` per LD#10 default — consumers of lifecycle will need platform types transitively

#### Step 8: Update `package-info.java`

Location: `lifecycle/lifecycle/src/main/java/com/homesynapse/lifecycle/package-info.java`

Replace the stub with:

```java
/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Process-level lifecycle orchestration for HomeSynapse Core.
 *
 * <p>This package provides five capabilities:</p>
 *
 * <ul>
 *   <li><strong>Lifecycle State Machine</strong> — The system moves through ten
 *       sequential phases: {@link LifecyclePhase#BOOTSTRAP} (platform initialization),
 *       {@link LifecyclePhase#FOUNDATION} (configuration), {@link
 *       LifecyclePhase#DATA_INFRASTRUCTURE} (persistence and event bus), {@link
 *       LifecyclePhase#CORE_DOMAIN} (device model, state store, automation), {@link
 *       LifecyclePhase#OBSERVABILITY} (health aggregation), {@link
 *       LifecyclePhase#EXTERNAL_INTERFACES} (REST and WebSocket APIs), {@link
 *       LifecyclePhase#INTEGRATIONS} (integration adapter startup), {@link
 *       LifecyclePhase#RUNNING} (steady state), {@link
 *       LifecyclePhase#SHUTTING_DOWN} (graceful shutdown), and {@link
 *       LifecyclePhase#STOPPED} (terminated).</li>
 *   <li><strong>Initialization Orchestration</strong> — {@link
 *       SystemLifecycleManager} sequences all subsystems through their initialization
 *       phases in documented order. No subsystem may depend on a subsystem from a
 *       later phase. Fatal failures (Configuration System, Persistence Layer, Event
 *       Bus, Device Model, State Store, Automation Engine, REST API) exit the process
 *       with diagnostic messages. Non-fatal failures (Observability, WebSocket API,
 *       integration adapters) degrade the system health to DEGRADED and continue.</li>
 *   <li><strong>Shutdown Sequencing</strong> — Reverse initialization order with
 *       grace periods. The {@link com.homesynapse.lifecycle.HealthReporter} interface
 *       communicates shutdown progress to systemd. Total shutdown budget: 30 seconds
 *       (half of systemd's `TimeoutStopSec=90`).</li>
 *   <li><strong>Health Loop and Watchdog</strong> — After RUNNING state, the health
 *       loop polls all {@link
 *       com.homesynapse.observability.HealthContributor} implementations every 30
 *       seconds, computes aggregated health via the three-tier model, and feeds
 *       systemd's watchdog via {@link
 *       com.homesynapse.lifecycle.HealthReporter#reportWatchdog()}. Missing watchdog
 *       calls trigger process restart.</li>
 *   <li><strong>Platform Abstraction</strong> — {@link
 *       com.homesynapse.lifecycle.HealthReporter} abstracts lifecycle notifications
 *       (systemd `sd_notify` messages). {@link com.homesynapse.lifecycle.PlatformPaths}
 *       abstracts directory conventions (FHS on Linux). Both are resolved once during
 *       BOOTSTRAP and cached for the lifetime of the process.</li>
 * </ul>
 *
 * <p>Design authority: Doc 12 — Startup, Lifecycle & Shutdown (Locked).</p>
 *
 * @see SystemLifecycleManager
 * @see HealthReporter
 * @see PlatformPaths
 */
package com.homesynapse.lifecycle;
```

### Group 5: Compile Gate

Run `./gradlew :lifecycle:lifecycle:compileJava` from the repository root. Must pass with zero errors and zero warnings (`-Xlint:all -Werror`).

**Common pitfalls:**
- Missing imports: `java.nio.file.Path`, `java.time.Instant`, `java.time.Duration`, `java.util.Map`, `java.util.Objects`
- HealthStatus, HealthTier, HealthAggregator imports from `com.homesynapse.observability`
- LifecycleState import from `com.homesynapse.observability` (defined in Doc 11, not here)
- EventPublisher, CausalContext imports from `com.homesynapse.event`
- Ulid import from `com.homesynapse.platform.identity`
- Map.copyOf() on subsystemStates — the map keys are Strings, which is fine
- Package-info Javadoc uses `{@link}` references — ensure all referenced types exist before compiling
- Validate fields in compact constructors with Objects.requireNonNull on non-null fields

Then run the full project compile gate: `./gradlew compileJava` to verify no regressions.

---

## Constraints

1. **Java 21** — use records, sealed interfaces (if needed), pattern matching as appropriate
2. **`-Xlint:all -Werror`** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies** — only types from existing modules (observability, event-model, platform-api)
5. **Javadoc on every public type, method, and constructor** — including record components via `@param`
6. **All types go in `com.homesynapse.lifecycle` package** — single flat package
7. **Do NOT create implementations** — interfaces and contract types only
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files in other modules** — all work is new file creation in the lifecycle module plus updating existing package-info.java and build.gradle.kts
10. **No `@Nullable` annotations** — use Javadoc `{@code null} if...` patterns per project convention
11. **LifecyclePhase and SubsystemStatus are enums, not sealed types** — no need for sealed here

---

## Execution Order

1. `LifecyclePhase.java` (enum)
2. `SubsystemStatus.java` (enum)
3. `LifecycleEventType.java` (utility class with constants)
4. `SubsystemState.java` (record — depends on LifecyclePhase, SubsystemStatus, HealthStatus)
5. `SystemHealthSnapshot.java` (record — depends on HealthStatus)
6. `SystemLifecycleManager.java` (interface — depends on LifecyclePhase, SystemHealthSnapshot, SubsystemState)
7. `module-info.java`
8. `package-info.java` (update existing)
9. Compile gate: `:lifecycle:lifecycle:compileJava`
10. Full project compile gate: `compileJava`

> **Reminder:** `HealthReporter` and `PlatformPaths` already exist in `platform-api` (Block F). Do NOT create them here.

---

## Summary of New Files

| # | File | Kind | Key Details |
|---|------|------|-------------|
| 1 | LifecyclePhase | enum (10 values) | BOOTSTRAP through STOPPED |
| 2 | SubsystemStatus | enum (6 values) | NOT_STARTED, INITIALIZING, RUNNING, FAILED, STOPPING, STOPPED |
| 3 | LifecycleEventType | utility class (7 constants) | system.starting, system.ready, system.stopping, system.stopped, etc. |
| 4 | SubsystemState | record (6 fields) | subsystemName, phase, status, healthState, initializationDuration, error |
| 5 | SystemHealthSnapshot | record (8 fields) | timestamp, subsystemStates, aggregatedHealth, uptime, eventStorePosition, entityCount, integrationCount, automationCount |
| 6 | SystemLifecycleManager | interface (5 methods) | start, shutdown, currentPhase, healthSnapshot, subsystemStates |
| 7 | module-info.java | module descriptor | requires transitive observability, event, platform |
| 8 | package-info.java | package Javadoc | (update existing) |

**Total: 6 new files + 2 updated files (module-info, package-info) = 8 files**

> **Note:** `HealthReporter` (4 methods) and `PlatformPaths` (6 methods) already exist in `platform-api` module (Block F). The lifecycle module consumes them via `requires transitive com.homesynapse.platform`.

---

## What to Watch Out For

1. **HealthReporter and PlatformPaths are platform abstractions selected once during Phase 0.** They are not created or managed by the lifecycle module — they are provided by the bootstrapping code in Phase 0. The lifecycle module's Phase 2 interface spec defines the contracts; Phase 3 implementation selects implementations based on environment (systemd vs. non-systemd, FHS vs. development paths).

2. **LifecyclePhase ordering is a contract.** Doc 12 §3.2–§3.8 defines which subsystems initialize in each phase. No subsystem may depend on a subsystem from a later phase. This is a critical constraint that every subsystem design must verify against.

3. **SubsystemState.healthState is nullable during early initialization.** HealthContributors may not be available until Phase 4 (Observability). Subsystems initialized in earlier phases report health via HealthContributor only after that interface is set up. Early in the initialization, healthState is null.

4. **SystemHealthSnapshot.uptime is nullable until RUNNING state is reached.** Before the system enters RUNNING (after Phase 6), there is no "ready time" from which to measure uptime. The uptime field is null. Once RUNNING is reached, uptime is always populated.

5. **LifecycleEventType is a utility class, not an enum.** It's a container for `public static final String` constants matching Doc 12 §4.4 event type strings. It's used by Phase 3 implementation to construct EventDraft instances for publishing lifecycle events.

6. **Shutdown is idempotent and can be called at any point.** Even if startup is in the middle of Phase 3, calling `shutdown()` will release only the subsystems that have completed initialization. Subsystems that have not yet started are skipped. This is why SubsystemState tracks the status of each subsystem — shutdown logic uses it to decide what to clean up.

7. **No circular dependencies between HealthReporter (platform-api) and SystemLifecycleManager (lifecycle).** HealthReporter is used by SystemLifecycleManager's Phase 3 implementation (to report lifecycle milestones), but SystemLifecycleManager does not appear in HealthReporter's interface. This one-way dependency is important for modularity. Note: HealthReporter lives in `com.homesynapse.platform`, not in `com.homesynapse.lifecycle`.

8. **PlatformPaths.tempDir() is cleaned on every startup.** Every other directory persists across restarts. tempDir is explicitly wiped at the start of Phase 0. Subsystems may use it for scratch space without worrying about cleanup.

9. **The relationship between Doc 11 LifecycleState and Doc 12 LifecyclePhase:** Doc 11 defines `LifecycleState` (STARTING, RUNNING, SHUTTING_DOWN) which tracks the system-wide lifecycle state for health evaluation purposes. Doc 12 defines `LifecyclePhase` (BOOTSTRAP, FOUNDATION, ..., STOPPED) which tracks the detailed process lifecycle. The lifecycle module exports both — LifecycleState is from the observability module (used by HealthAggregator to apply grace periods and tier composition rules), and LifecyclePhase is defined here.

10. **SystemHealthSnapshot captures a point-in-time view, not a streaming state machine.** Snapshots are produced on demand (REST health endpoint) or periodically (health loop every 30 seconds). Intermediate state transitions between snapshots are not captured. For a complete audit trail, consume lifecycle events from the event bus.

---

## Context Delta (post-completion)

**Files created (8 total):**
1. `LifecyclePhase.java` — enum (10 phases)
2. `SubsystemStatus.java` — enum (6 statuses)
3. `LifecycleEventType.java` — utility class (7 event type constants)
4. `SubsystemState.java` — record (6 fields)
5. `SystemHealthSnapshot.java` — record (8 fields)
6. `SystemLifecycleManager.java` — interface (5 methods)
7. `module-info.java` — requires transitive observability, event, platform; exports lifecycle
8. `package-info.java` — updated from stub with comprehensive package Javadoc

> `HealthReporter` and `PlatformPaths` are NOT created here — they already exist in `platform-api` (Block F).

**Updated files:**
- `build.gradle.kts` — api dependencies on observability, event, platform; implementation on config

**JPMS module-info analysis:**
- `requires transitive com.homesynapse.observability` — HealthStatus appears in SubsystemState.healthState and SystemHealthSnapshot.aggregatedHealth (types in exported API)
- `requires transitive com.homesynapse.event` — per LD#10 default; Phase 3 will use EventPublisher for lifecycle events
- `requires transitive com.homesynapse.platform` — per LD#10 default; consumers need HealthReporter/PlatformPaths transitively

**Decisions made during execution:**
- All 12 locked decisions followed exactly
- JPMS analysis confirmed transitive declarations correct
- No sealed types needed — enums suffice for LifecyclePhase and SubsystemStatus
- All Javadoc includes nullability contracts (especially HealthState, uptime) and thread-safety statements
- Utility class (LifecycleEventType) provides compile-time event type constants per Doc 12 §4.4

**Compile gate:** Pending manual execution. Code follows all established project patterns. Run: `./gradlew :lifecycle:lifecycle:compileJava` then `./gradlew compileJava`.

**What the next block needs to know:**
- **Phase 3 Implementation:** Each subsystem initializes by implementing the initialization/shutdown callbacks that SystemLifecycleManager will call. The lifecycle module's Phase 3 will wire up subsystem discovery (likely via ServiceLoader or manual registry), phase sequencing, and state tracking. Lifecycle events (system.starting, system.ready, system.stopping, system.stopped) are published via EventPublisher during Phase 2 boundaries.
- **Configuration System (Doc 06):** Phase 1 invokes `ConfigurationService.load()` to validate configuration before any other subsystem starts. Lifecycle configuration (shutdown_grace_period_seconds, etc.) is read from the `lifecycle:` section of config.yaml.
- **Observability Module (Doc 11):** Phase 4 creates HealthContributor instances from all initialized subsystems. The lifecycle module's health loop (Phase 6+) polls these contributors every 30 seconds and feeds HealthReporter.reportWatchdog() for systemd integration.
- **REST API (Doc 09) and WebSocket API (Doc 10):** Both depend on the `/api/v1/system/health` endpoint and WebSocket health streaming, which consume `SystemLifecycleManager.healthSnapshot()` and `subsystemStates()`.
- **Platform Implementations:** HealthReporter and PlatformPaths implementations are Tier 1 deliverables. `SystemdHealthReporter` uses the faljse/SDNotify library for sd_notify message sending. `LinuxSystemPaths` follows FHS conventions.

---
