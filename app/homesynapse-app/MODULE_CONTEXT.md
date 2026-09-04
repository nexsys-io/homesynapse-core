# homesynapse-app — `com.homesynapse.app` — Scaffold — Assembly apex, manual DI wiring, all requires non-transitive, no exports

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
| `Main` | final class (private constructor) | Application entry point + the M6.2 E2 bridge host | `main(String[])` — still the Phase 2 scaffold body (full bootstrap is a later WU). **M6.2 added the package-private `static PayloadCipher payloadCipher(Path configDir, Clock clock)` factory** (Doc 15 §3.8 / CARRY 1): constructs config's `ScopeKeyManager.create(configDir, clock)` and wraps it in an anonymous adapter of persistence's consumer-defined `PayloadCipher` seam — `app` is the only module reading BOTH `config` and `persistence`, so the bridge closes the key-management cycle with zero new module edges (AMD-45 composition-root discipline). Package-private so `PayloadCipherBridgeTest` exercises the real adapter; the future bootstrap passes it to `HomeSynapseCore`'s 5-arg ctor. |
| `ExitCode` | enum (5 values) | Process exit codes for fatal startup failures | Values: `CONFIGURATION_FAILURE(10)`, `PERSISTENCE_FAILURE(11)`, `EVENT_BUS_FAILURE(12)`, `SUBSYSTEM_INIT_TIMEOUT(13)`, `UNEXPECTED_ERROR(99)`. Method: `code()` → int. Codes map to Doc 12 §3 initialization phases. **WIRED at FAILCHAN (2026-09-04)** through `ExitCodes` (below); `SUBSYSTEM_INIT_TIMEOUT(13)` has NO producer at ef02d13 — reserved, never emitted. |
| `ExitCodes` | package-private final class (private constructor) | FAILCHAN (EXITCODE (a)) — the pure mapping behind the process-exit seam | `static ExitCode forStartupFailure(Optional<StartupFailureReport>)`: empty → `UNEXPECTED_ERROR`; else by `subsystem()` — `configuration` → `CONFIGURATION_FAILURE` (10) · `persistence` → `PERSISTENCE_FAILURE` (11) · `event-bus` → `EVENT_BUS_FAILURE` (12) · anything else (`device-model`, `state-store`, `automation`, `rest-api`, `unknown`) → `UNEXPECTED_ERROR` (99). NPE on a null `Optional`. Unit-tested (`ExitCodesTest` T1–T3); the `System.exit` in `Main` is the untested side of the seam BY DESIGN (the R-10 sitting caveat). |
| `TokenCli` | package-private final class (private constructor) | The read-only `token status` runtime mode (R-6 TOKEN-OPS, 2026-08-22) | `static int run(String[] args, Path configDir, Clock clock)` delegates to the 5-arg `run(args, configDir, clock, PrintStream out, PrintStream err)` test seam. `homesynapse token status` opens `new OpaqueTokenStore(configDir, clock)` (read-only by construction — the ctor only loads), prints a fixed-width table `KEY_ID  NAME  CREATED  EXPIRES  SCOPES  SITE  STATE` (state ∈ `active`/`revoked`/`expired`; control characters in operator strings rendered as `?`) and the trailer `active: N  revoked: M  store: <path>` (suffixed `(absent — no token minted yet)` when no store file exists); exit 0. Any other verb — including the helper's `rotate`/`revoke`/`mint` — prints the usage block to stderr and exits 2. NEVER writes (no directory creation, no file; pinned by `TokenCliTest.statusPerformsNoWrites`). Never prints a token or a hash. The clock is INJECTED although `com.homesynapse.app` is whitelisted — `Main.main` stays the single `Clock.systemUTC()` site and `TokenCliTest` runs on `Clock.fixed`. |

**Total: 2 public types + 2 package-private types + 1 package-info.java + 1 module-info.java = 6 Java files.** (R-6 added `TokenCli`; FAILCHAN added `ExitCodes`.)

**FAILCHAN (2026-09-04, R-10 Row 6 (a) + EXITCODE (a)) — `Main` wires the `ExitCode` contract: the exit seam.** `main()` wraps ONLY `manager.start()`. On a throw it consults the new hook flag `sigtermReceived` (an `AtomicBoolean` set `true` as the FIRST statement inside the hook's `try`, before `manager.shutdown("SIGTERM")`): set ⇒ `main` RETURNS (Doc 12 §6.5 — SIGTERM arrived mid-bootstrap; the hook owns the exit and the JVM's 143 is clean by the unit's `SuccessExitStatus=143`); else `ExitCodes.forStartupFailure(manager.lastStartupFailure())` → ONE Register-C stderr line `HomeSynapse Core exiting: code=<n> (<NAME>) — <recommendation | see the log>` → `System.exit(code)`. The exit happens in `main`, AFTER `start()` threw and the lifecycle ran its own teardown inside `start()` — nothing else to tear down — and NEVER from the hook (`Runtime.exit` during a running shutdown sequence blocks forever; `Runtime.halt` would skip the hooks). The pre-start paths (`Files.createDirectories`, `resolveHomeId`) still exit 1 through `throws Exception` — out of scope, noted. The unit's half (distribution): `SuccessExitStatus=143` · `Restart=always` · `RestartPreventExitStatus=10` — `distribution/docs/boot-contract-map.md` §"Exit codes → restart policy" and §"Shutdown"; the lint `distribution/smoke/unit-directives-test.sh` pins the seven load-bearing directives. Tests: `ExitCodesTest` (+3, app 27 → 30); the `sigtermReceived` guard and the `System.exit` call are REVIEWED, not tested (the seam's boundary — no test can see them). `module-info.java` byte-unchanged (`StartupFailureReport` lives in the already-required, exported `com.homesynapse.lifecycle`).

**R-6 TOKEN-OPS (2026-08-22) — `Main` now reads `args`.** `main` dispatches `token …` to `TokenCli.run(args, resolveBaseDir().resolve("config"), clock)` and `System.exit`s with its status — placed immediately AFTER the sanctioned `Clock.systemUTC()` line (the clock is reused) and BEFORE `resolveBaseDir()`'s `Files.createDirectories` writes and `resolveHomeId`'s `home_id` write, because the CLI mode performs NO writes. The packaged helper (`distribution/deb/homesynapse-token status`) runs it as `sudo -u homesynapse env HOMESYNAPSE_HOME=/var/lib/homesynapse /opt/homesynapse/bin/homesynapse token status` (the launcher forwards `"$@"`); the JVM reserves the launcher's `-Xms512m -Xmx1536m` for the read — acceptable on the Pi (Doc 12 sizing), no second launcher. The mutating verbs (`rotate` · `revoke <keyId>` · `mint <name>`) are the helper's, not the runtime's: they write `config/token_ops.request` for the service to consume at its next start (the store file has one lawful writer — see `api/rest-api/MODULE_CONTEXT.md` §R-6/R-8). A store that exists but cannot be read (the wrong-user case) is ONE Register-C line on stderr + exit 1 (`EXIT_IO`), never a stack trace. Tests: `TokenCliTest` (A, 5 — table + trailer, empty dir, no-writes, unreadable store via a directory at the store path, usage/exit 2). The fixed clock there is lane discipline: `HomeSynapseArchRules.NO_DIRECT_TIME_ACCESS` EXCLUDES `com.homesynapse.app..` — tests included — so nothing mechanical enforces it in this module.

**PKG-SEC-2 (2026-09-03, R-10 Row 13 RULED (a′)) — `Main` supplies the integration schema fragments BEFORE `start()`.** New package-private `static Map<String, String> integrationSchemaFragments()` — one entry per hosted integration type keyed by its `integrations.{type}` section key, in registration order (a `LinkedHashMap` wrapped unmodifiable; the registry composes in registration order, so the on-disk composed schema stays byte-stable across boots): today exactly `zigbee → ZigbeeIntegrationFactory.configSchemaJson()` (static resource text; no adapter instance is involved). `main()` iterates it into `core.registerIntegrationSchema` immediately BEFORE `manager.start()`; the core queues the fragments and drains them in Phase 1 after the core schemas and before `load()`, so `integrations.zigbee` validates against the REAL fragment at Phase-1 validation — R-4 C-1 (`Configuration issue [WARNING] at 'integrations.zigbee': property 'zigbee' is not defined in the schema …` on every start) is closed, and a malformed block is now CAUGHT at boot (§3.6 ERROR/WARNING tiers). The former post-start W10 registration at the old `:130` is REMOVED, not kept as a no-op: the pre-start fragment is already composed, and a second registration would only invalidate the composition cache for no observable gain. Tests: `MainSchemaFragmentsTest` (A, 3 — the supply keyed by type and byte-equal to the shipped resource · the map is unmodifiable · the supplied fragment declares NO `permit_join_duration` default, because a composed fragment's defaults are operative from Phase 1 on and the M9.4-PJ law is absent ⇒ no join window). No module-info / build-file change (`com.homesynapse.integration.zigbee` and `com.homesynapse.lifecycle` were already required). **R-4b's rig check after landing:** `journalctl -u homesynapse.service -b --no-pager | grep -c 'Configuration issue'` → `0`, and exactly one `lifecycle.integration_schema_registered: type=zigbee stage=pre-load` per boot.

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

**GOTCHA (FAILCHAN, 2026-09-04): `ExitCode` is WIRED as of FAILCHAN: `Main` maps `SystemLifecycleManager.lastStartupFailure()` → `ExitCodes.forStartupFailure` → `System.exit` — in `main`, after `start()` threw, NEVER from the hook (Runtime.exit during a running shutdown blocks forever); on a SIGTERM mid-bootstrap `main` returns and the JVM's 143 is the clean exit. 13 (`SUBSYSTEM_INIT_TIMEOUT`) has no producer.** The mapping keys on the lifecycle's `initializing` marker vocabulary (the `recordSubsystem` names) — a new fatal-set subsystem name in `HomeSynapseCore.bootstrap()` maps to 99 until `ExitCodes` learns it; the contract text an operator sees on stderr is the lifecycle's `recommendationFor(...)`, pinned by `HomeSynapseCoreStartupFailureTest`.

## Phase 3 Notes

- **Main.main() implementation:** Construct all subsystems via manual constructor wiring (no DI framework). Create SystemLifecycleManager, call start(), register JVM shutdown hook for shutdown(). Use ExitCode values in catch blocks for fatal startup failures. **DONE at FAILCHAN (2026-09-04): the catch around `start()` maps the lifecycle report to the `ExitCode` (the FAILCHAN paragraph above).**
- **ServiceLoader declarations:** Add `uses com.homesynapse.integration.api.IntegrationFactory;` to module-info.java for integration adapter discovery.
- **platform-systemd JPMS:** Add `requires com.homesynapse.platform.systemd` (or use ServiceLoader) when SystemdHealthReporter is instantiated.
- **jlink packaging:** Add jlink Gradle tasks, custom runtime image configuration, and systemd unit generation to build.gradle.kts.
- **Signal handling:** Register `Runtime.getRuntime().addShutdownHook()` for SIGTERM handling. May also register SIGHUP for config reload trigger.
- **JVM flags:** Configure G1GC (100ms pause target), AppCDS (Class Data Sharing), and module-path resolution in the jlink-generated launch script.


---


## Phase 3 Cross-Module Context

*Updated 2026-05-17 (Post-M3.1 refresh). Phase 3 active — M3.1 `InProcessEventBus` landed 2026-05-17. Next milestone: M3.5a (StateProjection vertical slice). M3 governance: AMD-41/42/43 APPLIED. See `homesynapse-core-docs/design/HomeSynapse_Core_M3_Implementation_Plan_PLAN-M3-CONSOLIDATED-02.md` for the full M3 implementation plan.*

**Phase 3 cross-module decisions register:** `nexsys-hivemind/context/decisions/phase-3-cross-module-decisions.md` is the running list of decisions made during Phase 3 implementation that cross module boundaries. Read this file before starting Phase 3 work on this module — it closes questions the Phase 2 interface spec left open and establishes patterns that every Phase 3 implementation must follow.

**Decisions directly relevant to this module:**

- **D-01** — *DomainEvent non-sealed*: app module composition root wires the EventTypeRegistry with all known event types
- The app module is the composition root — it binds real Clock, real SQLite paths, production EventBus (InProcessEventBus), and registers all subscribers

**Read also:** `nexsys-hivemind/context/status/PROJECT_SNAPSHOT.md` for current milestone state; `nexsys-hivemind/context/lessons/coder-lessons.md` for Phase 3 pattern discoveries (including M3.1 entries on default interface methods, contract test capability hooks, and JPMS-enforced JDBC-free constraints).
