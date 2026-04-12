# Block S — homesynapse-app (Application Assembly)

**Phase:** 2-Interface
**Design Doc:** Doc 14 — Master Architecture Document (Locked), Doc 12 — Startup, Lifecycle & Shutdown (Locked)
**Module:** `app/homesynapse-app`
**Package:** `com.homesynapse.app`

---

## What This Block Produces

Block S specifies the **homesynapse-app assembly module** — the top-level module that wires all subsystem modules together and serves as the application entry point. This module sits at the apex of the dependency graph: it depends on every other module but no other module depends on it.

In Phase 2, the homesynapse-app module does not define domain interfaces consumed by other modules. Instead, its Phase 2 deliverables are:

1. **`module-info.java`** — JPMS module descriptor that `requires` all subsystem modules, giving the assembly module access to the full type graph
2. **`package-info.java`** — Updated from stub with comprehensive Javadoc describing the assembly module's role
3. **`ExitCode.java`** — Enum defining process exit codes for deterministic diagnostics of fatal startup failures
4. **`build.gradle.kts`** — Updated to include all production modules (adding dashboard classpath resource)
5. **`MODULE_CONTEXT.md`** — Populated from stub

**Total: 3 files (1 new enum, 1 new module-info, 1 updated package-info) + build.gradle.kts update + MODULE_CONTEXT.md**

---

## Files to Read Before Starting

| File | Why |
|---|---|
| `lifecycle/lifecycle/MODULE_CONTEXT.md` | SystemLifecycleManager is the primary type the app module calls; understand its API surface |
| `observability/observability/MODULE_CONTEXT.md` | HealthStatus used by lifecycle types consumed from app; audit revert lesson (Gotcha §8) |
| `platform/platform-api/MODULE_CONTEXT.md` | HealthReporter and PlatformPaths are the platform abstraction interfaces called during bootstrap |
| `config/configuration/MODULE_CONTEXT.md` | ConfigurationService is initialized in Phase 1 of the startup sequence |
| `core/event-model/MODULE_CONTEXT.md` | EventPublisher, EventStore — foundational types in the transitive graph |
| This handoff document | Authoritative source for Block S specification |

---

## Execution Steps

### Step 1: Create `module-info.java`

**File:** `app/homesynapse-app/src/main/java/module-info.java`
**Action:** CREATE

```java
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

**JPMS analysis:**

- **All `requires` are NON-TRANSITIVE.** This is the one module in the project where the LD#10 default rule does NOT apply. The homesynapse-app module has no exported packages (it is the top of the graph — no other module depends on it). Since it exports nothing, no types from required modules can appear in an exported API. Therefore, `requires transitive` would be semantically meaningless and produce no compiler benefit.
- **No `exports` clause.** The `com.homesynapse.app` package is NOT exported — the app module is the top of the dependency graph and is not consumed by any other module.
- **No `uses` or `provides` declarations in Phase 2.** ServiceLoader-based integration discovery (LTD-17) is Phase 3 implementation work. The `uses com.homesynapse.integration.api.IntegrationFactory;` declaration will be added in Phase 3.

**JPMS module names have been verified against the actual source files.** Three names differ from what their directory path might suggest — pay attention to these: `event-bus` → `com.homesynapse.event.bus`, `integration-api` → `com.homesynapse.integration` (no `.api` suffix), `websocket-api` → `com.homesynapse.api.ws` (not `.websocket`). Full verified table:

| Module Path | Verified JPMS Name | Source |
|---|---|---|
| `lifecycle/lifecycle` | `com.homesynapse.lifecycle` | Verified from source |
| `observability/observability` | `com.homesynapse.observability` | Verified from source |
| `core/event-model` | `com.homesynapse.event` | Verified from source |
| `core/device-model` | `com.homesynapse.device` | Verified from source |
| `core/state-store` | `com.homesynapse.state` | Verified from source |
| `core/persistence` | `com.homesynapse.persistence` | Verified from source |
| `core/event-bus` | `com.homesynapse.event.bus` | Verified from source — note `.bus` not `bus` |
| `core/automation` | `com.homesynapse.automation` | Verified from source |
| `integration/integration-api` | `com.homesynapse.integration` | Verified from source — NOT `.integration.api` |
| `integration/integration-runtime` | `com.homesynapse.integration.runtime` | Verified from source |
| `integration/integration-zigbee` | `com.homesynapse.integration.zigbee` | Verified from source |
| `config/configuration` | `com.homesynapse.config` | Verified from source |
| `api/rest-api` | `com.homesynapse.api.rest` | Verified from source |
| `api/websocket-api` | `com.homesynapse.api.ws` | Verified from source — NOT `.api.websocket` |
| `platform/platform-api` | `com.homesynapse.platform` | Verified from source |

**NOTE:** `platform-systemd` is intentionally NOT in the `requires` list for Phase 2. It is a Tier 1 implementation module that will be added in Phase 3 when SystemdHealthReporter and LinuxSystemPaths are implemented. The app module will discover it at runtime via ServiceLoader or direct instantiation.

**NOTE:** `web-ui/dashboard` is NOT a JPMS module (it has no `module-info.java` — it contains only pre-built static files). It is accessed via classpath resources, not module requires. The Gradle `implementation` dependency provides classpath access; no JPMS declaration needed.

### Step 2: Create `ExitCode.java`

**File:** `app/homesynapse-app/src/main/java/com/homesynapse/app/ExitCode.java`
**Action:** CREATE

```java
/**
 * Process exit codes for HomeSynapse Core.
 *
 * <p>Each exit code maps to a specific fatal failure category,
 * enabling deterministic diagnosis of startup failures from
 * the process exit status alone. The systemd unit file can
 * use these codes to distinguish between restartable and
 * non-restartable failures.
 *
 * <p>Exit code 0 is never used explicitly — a clean shutdown
 * returns 0 via normal JVM exit. All codes in this enum
 * represent abnormal termination.
 *
 * @see com.homesynapse.lifecycle.SystemLifecycleManager
 */
public enum ExitCode {

    /** Configuration file missing, unparseable, or fails schema validation. */
    CONFIGURATION_FAILURE(10),

    /** SQLite database cannot be opened, migrated, or passes integrity check. */
    PERSISTENCE_FAILURE(11),

    /** Event bus initialization failed — cannot distribute events. */
    EVENT_BUS_FAILURE(12),

    /** A required core subsystem failed to initialize within its timeout. */
    SUBSYSTEM_INIT_TIMEOUT(13),

    /** Unhandled exception escaped the startup sequence. */
    UNEXPECTED_ERROR(99);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric exit code for use with {@link System#exit(int)}.
     *
     * @return the process exit code, always positive and non-zero
     */
    public int code() {
        return code;
    }
}
```

**Design rationale:**
- Doc 12 §1: "Fail-fast on critical infrastructure" — the app module must exit with a meaningful code when a fatal subsystem fails.
- Doc 12 §6 classifies fatal vs non-fatal failures. Fatal categories: Configuration (Phase 1), Persistence/Event Bus (Phase 2), core domain timeouts (Phase 3). Non-fatal: integrations, observability.
- Exit codes 10–13 map to the Doc 12 §3 initialization phases where fatal failures can occur. Code 99 is the catch-all.
- No exit code for integration failures — those are non-fatal per Doc 12 §1.

**Behavioral contracts:**
- `code()` returns a positive, non-zero integer
- Enum values are stable — adding new codes is permitted; changing existing code numbers is a breaking change for systemd unit configuration

### Step 3: Update `package-info.java`

**File:** `app/homesynapse-app/src/main/java/com/homesynapse/app/package-info.java`
**Action:** UPDATE (replace stub content)

```java
/**
 * Application assembly and entry point for HomeSynapse Core.
 *
 * <p>This package contains the top-level application wiring that connects
 * all subsystem modules into a running system. It is the apex of the
 * module dependency graph — every other HomeSynapse module is reachable
 * from here, but no other module depends on this package.
 *
 * <p>The application lifecycle is delegated to
 * {@link com.homesynapse.lifecycle.SystemLifecycleManager}, which
 * orchestrates the seven-phase initialization sequence defined in
 * Doc 12 (Startup, Lifecycle &amp; Shutdown):
 *
 * <ul>
 *   <li><b>Phase 0 — BOOTSTRAP:</b> Platform paths, logging, JFR recording,
 *       health reporter initialization</li>
 *   <li><b>Phase 1 — FOUNDATION:</b> Configuration loading and validation</li>
 *   <li><b>Phase 2 — DATA_INFRASTRUCTURE:</b> Persistence layer, event bus</li>
 *   <li><b>Phase 3 — CORE_DOMAIN:</b> Device model, state store, automation engine</li>
 *   <li><b>Phase 4 — OBSERVABILITY:</b> Health aggregation, JFR metrics</li>
 *   <li><b>Phase 5 — EXTERNAL_INTERFACES:</b> REST API, WebSocket API, web dashboard</li>
 *   <li><b>Phase 6 — INTEGRATIONS:</b> Integration adapter discovery and startup</li>
 * </ul>
 *
 * <p>Phase 3 implementation will expand {@link com.homesynapse.app.Main}
 * with subsystem construction, dependency injection (manual wiring, no
 * framework), JVM shutdown hook registration, and signal handling.
 *
 * @see com.homesynapse.lifecycle.SystemLifecycleManager
 * @see com.homesynapse.lifecycle.LifecyclePhase
 * @see com.homesynapse.platform.HealthReporter
 * @see com.homesynapse.platform.PlatformPaths
 */
package com.homesynapse.app;
```

### Step 4: Update `build.gradle.kts`

**File:** `app/homesynapse-app/build.gradle.kts`
**Action:** UPDATE

The existing `build.gradle.kts` is almost complete. Add the `web-ui/dashboard` dependency for classpath access to static files, and ensure all modules are present:

```kotlin
plugins {
    id("homesynapse.java-conventions")
    application
}

description = "Application assembly: main class, dependency wiring, jlink packaging"

application {
    mainClass.set("com.homesynapse.app.Main")
}

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

**Changes from existing:**
- Added section comments for organization (platform, core, integration, config, API, observability, lifecycle, web, logging)
- Added `runtimeOnly(project(":web-ui:dashboard"))` — classpath access to pre-built static files served by Javalin at `/dashboard/` (Doc 13 §3.2, Doc 14 §3.4 Phase 5)
- All 16 module dependencies already present in the existing file — no missing modules

### Step 5: Compile Gate

Run `./gradlew compileJava` for the full project. Expect GREEN. The app module now has:
- `module-info.java` requiring all 15 subsystem modules
- `Main.java` (existing scaffold — unchanged)
- `ExitCode.java` (new enum)
- `package-info.java` (updated)

---

## Locked Decisions That Apply

- **LTD-01 (Java 21):** The application module entry point runs on Java 21 with G1GC and AppCDS.
- **LTD-10 (Gradle multi-module):** The app module is the assembly point in the multi-module build. Convention plugins apply.
- **LTD-13 (jlink + systemd):** Phase 3 will add jlink packaging configuration to this module's build.gradle.kts. Phase 2 does not include jlink config.
- **LTD-15 (SLF4J + Logback):** Logging runtime dependencies are declared here (runtimeOnly) — subsystem modules depend only on the SLF4J API.
- **LTD-17 (ServiceLoader):** Phase 3 will add `uses` declarations to module-info.java for integration factory discovery.

## Invariants That Apply

- **INV-RF-01 (Integration Isolation):** The app module wires integrations through the IntegrationSupervisor, not directly. No direct integration→core coupling.
- **INV-TO-01 (Observable behavior):** Logging and JFR must initialize before any subsystem code. This is enforced by Phase 0 ordering in SystemLifecycleManager — the app module's Main.main() delegates to SystemLifecycleManager.start().

## What to Watch Out For

1. **JPMS module names must be verified.** The module names in Step 1 are best-effort. If any module uses a different JPMS name than expected, the compile gate will catch it immediately. Read each `module-info.java` before writing the app's module-info.
2. **No `requires transitive` in this module.** The homesynapse-app module exports nothing, so `transitive` has no effect. Using `requires transitive` here would compile but be misleading — it implies consumers exist when none do.
3. **No `exports` clause.** The `com.homesynapse.app` package should NOT be exported. Adding `exports` would expose the Main class and ExitCode to other modules, which violates the assembly module's role as a leaf in the dependency graph.
4. **Do NOT modify `Main.java`.** The existing scaffold `Main.java` is correct for Phase 2. Phase 3 will replace its body with subsystem construction and lifecycle delegation. Do not add implementation logic now.
5. **`web-ui/dashboard` is `runtimeOnly`, not `implementation`.** The dashboard module has no Java types to compile against — it's a JAR of static files. Using `implementation` would work but is semantically wrong.
6. **`platform-systemd` is `implementation` in Gradle but NOT in module-info.java.** The Gradle dependency provides platform-systemd on the module path. The JPMS `requires` is deferred to Phase 3 because the app module doesn't yet reference any platform-systemd types in code. Phase 3 will add `requires com.homesynapse.platform.systemd` (or use ServiceLoader discovery) when SystemdHealthReporter is instantiated.

## Coder Pushback Welcome

If you discover during execution that any specification in this document is impractical, contradicts a MODULE_CONTEXT.md gotcha, or could be done better while maintaining the same behavioral contract — raise it. Technical insight from the implementation level improves the architecture.

## Out of Scope

- **Subsystem construction and wiring** — Phase 3. The app module's Phase 2 deliverable defines the module structure; Phase 3 adds the wiring logic in Main.java.
- **jlink packaging configuration** — Phase 3. Gradle jlink tasks, custom runtime image, and systemd unit generation are implementation work.
- **ServiceLoader declarations** — Phase 3. `uses IntegrationFactory;` in module-info.java comes when the integration discovery mechanism is implemented.
- **Signal handling and shutdown hooks** — Phase 3. `Runtime.getRuntime().addShutdownHook()` and SIGTERM handling are implementation.
- **Dependency injection** — Phase 3. Manual constructor wiring of all subsystems happens in Main.main().
- **Test support module (Block T)** — separate block.

## Block Completion (BCP Phase 1)

After the compile gate passes, execute BCP Phase 1 from `nexsys-hivemind/context/protocols/block-completion-protocol.md`. The five steps are: update MODULE_CONTEXT.md, update coder-handoff.md, append to coder-lessons.md (if applicable), post cross-agent note (if applicable), and append the BCP Phase 1 checklist to this handoff's completion section. The block is not done until the checklist is complete.
