# homesynapse-app

## Purpose

The homesynapse-app module is the application assembly point — the apex of the entire HomeSynapse Core dependency graph. It wires all subsystem modules together into a running system, provides the process entry point (`Main.main()`), and defines process exit codes (`ExitCode`) for deterministic diagnosis of fatal startup failures. This module depends on every other module but no other module depends on it. It exports no packages and has no downstream consumers.

## Design Doc Reference

- **Doc 14 — Master Architecture Document** (Locked) — Module graph, assembly structure, dependency rules.
- **Doc 12 — Startup, Lifecycle & Shutdown** (Locked) — §1: Fail-fast on critical infrastructure. §3: Seven-phase initialization sequence. §6: Fatal vs non-fatal failure classification (maps to ExitCode values).

## JPMS Module

```
module com.homesynapse.app {
    requires com.homesynapse.lifecycle;
    requires com.homesynapse.observability;
    requires com.homesynapse.event;
    requires com.homesynapse.device;
    requires com.homesynapse.state;
    requires com.homesynapse.persistence;
    requires com.homesynapse.event.bus;
    requires com.homesynapse.automation;
    requires com.homesynapse.integration;
    requires com.homesynapse.integration.runtime;
    requires com.homesynapse.integration.zigbee;
    requires com.homesynapse.config;
    requires com.homesynapse.api.rest;
    requires com.homesynapse.api.ws;
    requires com.homesynapse.platform;
}
```

All `requires` are **non-transitive**. This is the one module where the LD#10 default rule does NOT apply — the module exports nothing, so `requires transitive` would be semantically meaningless.

No `exports` clause — the `com.homesynapse.app` package is not consumed by any other module.

No `uses` or `provides` in Phase 2 — ServiceLoader-based integration discovery (`uses com.homesynapse.integration.api.IntegrationFactory`) will be added in Phase 3.

## Gradle Dependencies

```kotlin
dependencies {
    // Platform layer
    implementation(project(":platform:platform-api"))
    implementation(project(":platform:platform-systemd"))

    // Core subsystems
    implementation(project(":core:event-model"))
    implementation(project(":core:device-model"))
    implementation(project(":core:state-store"))
    implementation(project(":core:persistence"))
    implementation(project(":core:event-bus"))
    implementation(project(":core:automation"))

    // Integration layer
    implementation(project(":integration:integration-api"))
    implementation(project(":integration:integration-runtime"))
    implementation(project(":integration:integration-zigbee"))

    // Configuration
    implementation(project(":config:configuration"))

    // API layer
    implementation(project(":api:rest-api"))
    implementation(project(":api:websocket-api"))

    // Observability
    implementation(project(":observability:observability"))

    // Lifecycle
    implementation(project(":lifecycle:lifecycle"))

    // Web dashboard (static files on classpath — not a JPMS module)
    runtimeOnly(project(":web-ui:dashboard"))

    // Logging implementation (only at the app level)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logback.core)
}
```

All module dependencies are `implementation` scope — no `api` because nothing is exported. Dashboard is `runtimeOnly` (no Java types to compile against). Logback is `runtimeOnly` (subsystem modules depend only on the SLF4J API).

## Package Structure

- **`com.homesynapse.app`** — Single flat package. Contains: `Main` (entry point), `ExitCode` (exit code enum), `package-info.java` (comprehensive Javadoc).

## Complete Type Inventory

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Main` | final class (private constructor) | Application entry point | `main(String[])` — Phase 2 scaffold. Phase 3 replaces body with subsystem construction, lifecycle delegation, shutdown hook registration. |
| `ExitCode` | enum (5 values) | Process exit codes for fatal startup failures | Values: `CONFIGURATION_FAILURE(10)`, `PERSISTENCE_FAILURE(11)`, `EVENT_BUS_FAILURE(12)`, `SUBSYSTEM_INIT_TIMEOUT(13)`, `UNEXPECTED_ERROR(99)`. Method: `code()` → int. Codes map to Doc 12 §3 initialization phases. |

**Total: 2 public types + 1 package-info.java + 1 module-info.java = 4 Java files.**

## Dependencies

| Module | Relationship | Why |
|---|---|---|
| `com.homesynapse.lifecycle` | `requires` (implementation) | `SystemLifecycleManager.start()` and `.shutdown()` called from Main |
| `com.homesynapse.observability` | `requires` (implementation) | Transitive types (HealthStatus) used by lifecycle types |
| `com.homesynapse.event` | `requires` (implementation) | EventPublisher, EventStore — foundational types in transitive graph |
| `com.homesynapse.device` | `requires` (implementation) | Device model types — Phase 3 construction |
| `com.homesynapse.state` | `requires` (implementation) | State store — Phase 3 construction |
| `com.homesynapse.persistence` | `requires` (implementation) | SQLite persistence layer — Phase 3 construction |
| `com.homesynapse.event.bus` | `requires` (implementation) | Event bus — Phase 3 construction |
| `com.homesynapse.automation` | `requires` (implementation) | Automation engine — Phase 3 construction |
| `com.homesynapse.integration` | `requires` (implementation) | Integration API types — Phase 3 construction |
| `com.homesynapse.integration.runtime` | `requires` (implementation) | Integration supervisor — Phase 3 construction |
| `com.homesynapse.integration.zigbee` | `requires` (implementation) | Zigbee adapter — Phase 3 construction |
| `com.homesynapse.config` | `requires` (implementation) | ConfigurationService — Phase 3 startup sequence |
| `com.homesynapse.api.rest` | `requires` (implementation) | REST API — Phase 3 construction |
| `com.homesynapse.api.ws` | `requires` (implementation) | WebSocket API — Phase 3 construction |
| `com.homesynapse.platform` | `requires` (implementation) | PlatformPaths, HealthReporter — Phase 3 bootstrap |

## Consumers

None. This module is the top of the dependency graph — no other module depends on it.

## Cross-Module Contracts

- **Main delegates lifecycle to SystemLifecycleManager.** The app module does not implement initialization logic directly. Phase 3 `Main.main()` will construct a `SystemLifecycleManager`, call `start()`, and register a shutdown hook calling `shutdown(reason)`.
- **ExitCode values are stable.** Adding new exit codes is permitted; changing existing code numbers is a breaking change for systemd unit configuration. The systemd unit file maps exit codes to restart policies.
- **No integration failures produce exit codes.** Integration failures are non-fatal per Doc 12 §1 — the system degrades to DEGRADED but continues running.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-01** | Java 21 with G1GC and AppCDS. The application module entry point runs on Java 21. |
| **LTD-10** | Gradle multi-module assembly point. Convention plugins apply. |
| **LTD-13** | jlink packaging — Phase 3. This module's build.gradle.kts will add jlink configuration. |
| **LTD-15** | SLF4J + Logback. Logging runtime declared as runtimeOnly here; subsystem modules use SLF4J API only. |
| **LTD-17** | ServiceLoader — Phase 3. `uses IntegrationFactory;` added to module-info.java when integration discovery is implemented. |
| **INV-RF-01** | Integration isolation. App wires integrations through IntegrationSupervisor, not directly. |
| **INV-TO-01** | Observable behavior. Logging and JFR initialized before any subsystem code (Phase 0 ordering in SystemLifecycleManager). |

## Sealed Hierarchies

None. This module contains no sealed types.

## Gotchas

**GOTCHA: All `requires` are NON-TRANSITIVE.** This is the one module where the LD#10 default (`requires transitive`) does NOT apply. The app module exports nothing, so `transitive` has no effect. Using `requires transitive` here would compile but be misleading.

**GOTCHA: No `exports` clause.** Adding `exports com.homesynapse.app` would expose Main and ExitCode to other modules, violating the assembly module's role as a leaf node. Do not add exports.

**GOTCHA: `platform-systemd` is in Gradle but NOT in module-info.java.** The Gradle `implementation` dependency provides it on the module path, but the JPMS `requires` is deferred to Phase 3 when the app module actually references platform-systemd types (SystemdHealthReporter, LinuxSystemPaths). Phase 2 does not reference them in code.

**GOTCHA: `web-ui/dashboard` is `runtimeOnly`, not `implementation`.** Dashboard has no Java types — it's a JAR of static files served by Javalin. Using `implementation` would compile but is semantically incorrect.

**GOTCHA: Do NOT modify Main.java in Phase 2.** The existing scaffold is correct. Phase 3 replaces the body with subsystem construction and lifecycle delegation.

**GOTCHA: ExitCode values map to Doc 12 §3 initialization phases.** CONFIGURATION_FAILURE(10) → Phase 1. PERSISTENCE_FAILURE(11) and EVENT_BUS_FAILURE(12) → Phase 2. SUBSYSTEM_INIT_TIMEOUT(13) → Phase 3. UNEXPECTED_ERROR(99) → catch-all. No exit code for integration failures (non-fatal).

## Phase 3 Notes

- **Main.main() implementation:** Construct all subsystems via manual constructor wiring (no DI framework). Create SystemLifecycleManager, call start(), register JVM shutdown hook for shutdown(). Use ExitCode values in catch blocks for fatal startup failures.
- **ServiceLoader declarations:** Add `uses com.homesynapse.integration.api.IntegrationFactory;` to module-info.java for integration adapter discovery.
- **platform-systemd JPMS:** Add `requires com.homesynapse.platform.systemd` (or use ServiceLoader) when SystemdHealthReporter is instantiated.
- **jlink packaging:** Add jlink Gradle tasks, custom runtime image configuration, and systemd unit generation to build.gradle.kts.
- **Signal handling:** Register `Runtime.getRuntime().addShutdownHook()` for SIGTERM handling. May also register SIGHUP for config reload trigger.
- **JVM flags:** Configure G1GC (100ms pause target), AppCDS (Class Data Sharing), and module-path resolution in the jlink-generated launch script.
