# Block F — PlatformPaths and HealthReporter Interfaces

You are implementing Block F of HomeSynapse Core Phase 2 (Interface Specification). You are acting as the NexSys Coder — an implementation engineer writing constraint-compliant, infrastructure-grade Java 21 for a local-first, event-sourced smart home operating system running on constrained hardware.

**Read the NexSys Coder skill** before writing any code.

---

## Project Location

Repository root: `homesynapse-core`
Platform API module: `platform/platform-api/src/main/java/com/homesynapse/platform/`
Design doc: `homesynapse-core-docs/design/12-startup-lifecycle-shutdown.md`

---

## Context: What Exists Now

The `platform-api` module (`com.homesynapse.platform` package) is a leaf module with no internal project dependencies. It currently contains identity types built in Block A. All files compile cleanly with `-Xlint:all -Werror`. **Do NOT modify any existing files except `module-info.java`.**

**Identity types (in `com.homesynapse.platform.identity`):**
- `Ulid.java` — `record Ulid(long msb, long lsb) implements Comparable<Ulid>` with parse/toString, toBytes/fromBytes, extractTimestamp, isValid
- `UlidFactory.java` — monotonic generator with ReentrantLock + SecureRandom
- `EntityId`, `DeviceId`, `AreaId`, `AutomationId`, `PersonId`, `SystemId`, `HomeId`, `IntegrationId` — all `record XxxId(Ulid value) implements Comparable<XxxId>` with `of(Ulid)` and `parse(String)`

**Module structure:**
- `platform-api/build.gradle.kts` — `homesynapse.java-conventions` + `java-library`, no project dependencies
- `module-info.java`: `module com.homesynapse.platform { exports com.homesynapse.platform.identity; }`
- `com/homesynapse/platform/package-info.java` — exists (scaffold, `/** Platform abstraction API. */`)
- `com/homesynapse/platform/identity/package-info.java` — exists

**Build conventions:**
- `-Xlint:all -Werror` — zero warnings, zero unused imports
- Spotless copyright header: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`

---

## Design Authority

All interface designs are derived from Doc 12 (Startup, Lifecycle & Shutdown). The critical sections are:

- **§8.1 Interfaces table** — lists PlatformPaths and HealthReporter as platform-api interfaces
- **§8.2 HealthReporter** — full interface definition, method contracts, tier-based implementation notes
- **§8.3 PlatformPaths** — full interface definition, method contracts, path conventions
- **§7.1–§7.2 Portability Architecture** — the design rationale for abstracting platform-specific behavior

Read §8.2 and §8.3 fully before starting.

---

## Locked Design Decision: Platform Types Live in platform-api Root Package

`PlatformPaths` and `HealthReporter` belong in the `com.homesynapse.platform` package (NOT in `identity`). These are platform abstraction interfaces that every subsystem may depend on. The `identity` subpackage is specifically for ULID-based typed identity wrappers.

The `module-info.java` must be updated to export `com.homesynapse.platform` in addition to the existing `com.homesynapse.platform.identity` export.

---

## Exact Deliverables (in execution order)

### Step 1: Create PlatformPaths.java

Location: `platform/platform-api/src/main/java/com/homesynapse/platform/PlatformPaths.java`

```java
public interface PlatformPaths {

    Path binaryDir();

    Path configDir();

    Path dataDir();

    Path logDir();

    Path backupDir();

    Path tempDir();
}
```

6 methods, all returning `java.nio.file.Path`. Based on Doc 12 §8.3:

- **`binaryDir()`** — read-only runtime image location. On Linux Tier 1: `/opt/homesynapse/`. Contains the application JAR/image and static resources.
- **`configDir()`** — writable configuration files. On Linux Tier 1: `/etc/homesynapse/`. Contains `config.yaml` and any user-edited configuration.
- **`dataDir()`** — writable persistent data. On Linux Tier 1: `/var/lib/homesynapse/`. Contains SQLite databases (`homesynapse-events.db`, `homesynapse-telemetry.db`) and the unclean shutdown marker.
- **`logDir()`** — writable log files and JFR recordings. On Linux Tier 1: `/var/log/homesynapse/`. Contains `homesynapse.log` and `flight.jfr`.
- **`backupDir()`** — writable pre-update snapshots. On Linux Tier 1: `/var/lib/homesynapse/backups/`.
- **`tempDir()`** — writable temporary files, cleaned on startup. On Linux Tier 1: `/var/lib/homesynapse/tmp/`. Contents are deleted at the start of each process run (Phase 0). No other directory is cleaned on startup.

**Interface-level Javadoc must explain:**
- PlatformPaths abstracts platform-specific directory conventions (Doc 12 §8.3, Portability Architecture §7.2)
- All methods return absolute `Path` instances
- Paths are resolved once during Phase 0 and cached — subsequent calls return the same `Path` object (C12-10: immutable after Phase 0)
- All writable directories are verified to exist and be writable during Phase 0; if creation fails, initialization fails with a diagnostic
- `tempDir()` contents are deleted at the start of each process run; no other directory is cleaned on startup
- Implementations are selected based on deployment tier detection (Doc 12 §8.3)
- Thread-safe: implementations must be safe for concurrent access from any subsystem

**Per-method Javadoc must include:**
- `@return` describing the absolute path and what it contains
- Reference to the Linux Tier 1 default path for clarity
- Whether the directory is read-only or writable

### Step 2: Create HealthReporter.java

Location: `platform/platform-api/src/main/java/com/homesynapse/platform/HealthReporter.java`

```java
public interface HealthReporter {

    void reportReady();

    void reportWatchdog();

    void reportStopping();

    void reportStatus(String message);
}
```

4 methods. Based on Doc 12 §8.2:

- **`reportReady()`** — called exactly once, at the end of Phase 5, after APIs are accepting connections. On Tier 1, sends `READY=1` via `sd_notify`. After this call, the platform supervisor considers the service fully started and begins enforcing the watchdog interval.

- **`reportWatchdog()`** — called periodically from the health loop (every `WatchdogSec/2` seconds, default 30s). On Tier 1, sends `WATCHDOG=1` via `sd_notify`. Missing calls for longer than `WatchdogSec` trigger a process restart (C12-03).

- **`reportStopping()`** — called once at the start of the shutdown sequence. On Tier 1, sends `STOPPING=1` via `sd_notify`. The platform supervisor begins its shutdown timeout.

- **`reportStatus(String message)`** — called at lifecycle transitions with a human-readable status string. On Tier 1, sends `STATUS=<message>` via `sd_notify`. Visible in `systemctl status homesynapse`. The `message` parameter must not be null.

**Interface-level Javadoc must explain:**
- HealthReporter abstracts platform-specific lifecycle health reporting (Doc 12 §8.2, Portability Architecture §7.1)
- The implementation is selected during Phase 0 based on environment detection: `SystemdHealthReporter` if `$NOTIFY_SOCKET` is set, `NoOpHealthReporter` otherwise
- On non-systemd platforms (macOS, Docker without sd_notify, development), all methods are no-ops
- The watchdog contract (C12-03): `reportWatchdog()` must be called at least every `WatchdogSec/2` seconds or the platform supervisor kills the process
- Thread-safe: the health loop runs on a dedicated virtual thread; reportStatus may be called from any thread during lifecycle transitions
- This interface defines the REPORTING side only — it does not define health ASSESSMENT (that's `HealthContributor` from Doc 11)

**Per-method Javadoc must include:**
- `@param` for `reportStatus(String message)` — document non-null requirement
- `@see` tags referencing `PlatformPaths` (both are Phase 0 platform abstractions)
- When each method is called in the lifecycle (phase number)

### Step 3: Update module-info.java

Location: `platform/platform-api/src/main/java/module-info.java`

Current content:
```java
/**
 * Platform abstraction API — health reporting, system paths, and typed identity types.
 */
module com.homesynapse.platform {
    exports com.homesynapse.platform.identity;
}
```

Updated content:
```java
/**
 * Platform abstraction API — health reporting, system paths, and typed identity types.
 */
module com.homesynapse.platform {
    exports com.homesynapse.platform;
    exports com.homesynapse.platform.identity;
}
```

Add `exports com.homesynapse.platform;` BEFORE the existing identity export. This makes `PlatformPaths` and `HealthReporter` visible to downstream modules. The `identity` export remains unchanged.

**IMPORTANT:** This is the ONE existing file that gets modified in this block. The copyright header, module name, and Javadoc comment remain exactly as they are. Only add the new `exports` line.

### Step 4: Compile Gate

Run `./gradlew :platform:platform-api:compileJava` from the repository root. Must pass with zero errors and zero warnings (`-Xlint:all -Werror`).

**Common pitfalls:**
- `java.nio.file.Path` import needed for PlatformPaths — no other external imports needed
- `HealthReporter` has no imports beyond the copyright header and package declaration (all method types are primitives or `java.lang.String`)
- The existing `package-info.java` in `com.homesynapse.platform` should remain untouched
- Verify `module-info.java` has BOTH exports: `com.homesynapse.platform` AND `com.homesynapse.platform.identity`

---

## Constraints

1. **Java 21** — use interfaces as appropriate
2. **-Xlint:all -Werror** — zero warnings, zero unused imports
3. **Spotless copyright header** — exact format: `/* \n * HomeSynapse Core\n * Copyright (c) 2026 NexSys. All rights reserved.\n */`
4. **No external dependencies** — `PlatformPaths` uses only `java.nio.file.Path`; `HealthReporter` uses only `java.lang.String`
5. **Javadoc on every public type and method** — `@param`, `@return`, `@throws`, `@see` tags as applicable
6. **PlatformPaths and HealthReporter go in `com.homesynapse.platform` package** — NOT in `identity`
7. **Do NOT create implementations** — these are interfaces only (implementations are Phase 3)
8. **Do NOT create test files** — tests are Phase 3
9. **Do NOT modify any existing files** except `module-info.java` (which gets one new export line)

---

## Execution Order

1. Create PlatformPaths.java (standalone, uses only `java.nio.file.Path`)
2. Create HealthReporter.java (standalone, no imports beyond java.lang)
3. Update module-info.java to add `exports com.homesynapse.platform;`
4. Run `./gradlew :platform:platform-api:compileJava` and fix any issues

---

## Summary of New Files

| File | Module | Kind | Methods |
|------|--------|------|---------|
| PlatformPaths.java | platform-api | interface | 6 methods: binaryDir, configDir, dataDir, logDir, backupDir, tempDir |
| HealthReporter.java | platform-api | interface | 4 methods: reportReady, reportWatchdog, reportStopping, reportStatus |
| module-info.java | platform-api | module descriptor (MODIFIED) | adds `exports com.homesynapse.platform` |

---

## Context Delta

When this block is complete, the following changes to project state:
- **platform-api module:** 14 files (was 12) — adds PlatformPaths.java, HealthReporter.java
- **platform-api exports:** 2 packages (was 1) — adds `com.homesynapse.platform`
- **Total production Java files:** 42 (was 40)
- **Block F status:** DONE
- **Sprint 1 remaining:** Javadoc quality pass, traceability doc, full project compile gate, end-of-week report
