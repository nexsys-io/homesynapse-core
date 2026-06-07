# platform-systemd — `com.homesynapse.platform.systemd` — 4 types — Deployment-tier impls of PlatformPaths + HealthReporter (Tier-1 systemd / FHS + dev fallbacks)

## Purpose

The platform-systemd module provides the concrete deployment-tier implementations of the two `platform-api` abstraction interfaces — `PlatformPaths` (filesystem layout) and `HealthReporter` (service-manager health reporting). It supplies a Tier-1 (Linux / systemd) implementation of each plus a development/non-systemd fallback, so the rest of HomeSynapse Core depends only on the `platform-api` interfaces and never on deployment specifics. **It performs no tier detection or implementation selection** — instantiating and choosing the right pair is the composition root's job (lifecycle / M13, Doc 12 §7).

## Design Doc Reference

- **Doc 12 — Startup, Lifecycle & Shutdown** — §8.2 (HealthReporter contract, C12-03 watchdog), §8.3 (PlatformPaths contract, C12-10 immutable-after-Phase-0 + tempDir clearing), §7.1–§7.2 (Portability architecture / tier selection).
- Implements the `platform-api` interfaces specified in `platform/platform-api/MODULE_CONTEXT.md`.

## JPMS Module

```
module com.homesynapse.platform.systemd {
    requires transitive com.homesynapse.platform;
    requires org.slf4j;

    exports com.homesynapse.platform.systemd;
}
```

`com.homesynapse.platform` is **`requires transitive`** (M5-A gate fix) because the four public impl classes expose `PlatformPaths`/`HealthReporter` (platform-api types) as supertypes in the exported `com.homesynapse.platform.systemd` package — `-Xlint:exports` + `-Werror` makes a plain `requires` a fatal warning ("not indirectly exported using requires transitive"). This is the recurring house pattern (cf. `core/persistence` module-info; coder-lessons M2.9, M3.6e.1, M5-A); Gradle uses `api(project(":platform:platform-api"))` in lockstep. `requires org.slf4j` (M5-A) is the one addition beyond the original instruction's embedded module-info (flagged `[REVIEW]`, PM-ACCEPTED): `SystemdHealthReporter` logs send-and-forget `sd_notify` failures at WARN per LTD-15 (it must not propagate a transient socket error into the lifecycle/watchdog loop); it stays non-transitive (internal-only, not on the public API).

## Package Structure

- **`com.homesynapse.platform.systemd`** — flat package holding all four implementation classes. No sub-packages.

## Complete Type Inventory

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `LinuxSystemPaths` | final class implements `PlatformPaths` | Tier-1 FHS filesystem layout | binary `/opt/homesynapse`, config `/etc/homesynapse`, data `/var/lib/homesynapse`, log `/var/log/homesynapse`, backup `<data>/backups`, temp `<data>/tmp`. Public no-arg ctor resolves under `/`; package-private `LinuxSystemPaths(Path fsRoot)` test seam resolves the same layout under an arbitrary root. Resolves+caches all six `Path`s once; creates the five writable dirs (not the read-only `binaryDir`); clears `tempDir` contents on construction. Constructor `throws IOException`. |
| `LocalPaths` | final class implements `PlatformPaths` | Development-tier layout rooted under one base dir | binary = base, config `<base>/config`, data `<base>/data`, log `<base>/logs`, backup `<base>/data/backups`, temp `<base>/data/tmp`. Public no-arg ctor roots at the working directory (`Path.of("")` → `toAbsolutePath()`); package-private `LocalPaths(Path baseDir)` test seam. Same resolve-once-cache + create-writable + clear-temp behaviour as `LinuxSystemPaths`. Constructor `throws IOException`. |
| `SystemdHealthReporter` | final class implements `HealthReporter`, `AutoCloseable` | Tier-1 `sd_notify` reporter | `reportReady()`→`READY=1` (sent at most once, `AtomicBoolean` CAS), `reportWatchdog()`→`WATCHDOG=1`, `reportStopping()`→`STOPPING=1`, `reportStatus(msg)`→`STATUS=<msg>`. Sends serialised under a `ReentrantLock` (LTD-11); send-and-forget — `IOException` logged at WARN, never propagated. Public ctor `SystemdHealthReporter(String notifySocketName)` (rejects null/blank, `@`-prefix → abstract-socket NUL); package-private ctor `SystemdHealthReporter(NotifyTransport)` for tests. `close()` closes the socket. Nested package-private `NotifyTransport` seam + private `UnixDatagramTransport` (AF_UNIX SOCK_DGRAM). |
| `NoOpHealthReporter` | final class implements `HealthReporter` | Non-systemd / dev fallback | All four methods are no-ops; stateless; performs no I/O. |

**Total: 4 public types + 1 module-info.java = 5 Java files** (plus a nested package-private `NotifyTransport` interface and private `UnixDatagramTransport` class inside `SystemdHealthReporter`).

## Dependencies

| Module | Why | Specific Types Used |
|---|---|---|
| **platform-api** (`com.homesynapse.platform`) | `implementation` — implements the two abstraction interfaces | `PlatformPaths`, `HealthReporter`. |
| **org.slf4j** | `implementation` (M5-A) — WARN logging of `sd_notify` send failures (LTD-15) | `Logger`, `LoggerFactory` (in `SystemdHealthReporter` only). |
| **test-support** (`com.homesynapse.test`) | `testImplementation` — `NoRealIoExtension` guards `NoOpHealthReporterTest` against network I/O | `NoRealIoExtension`. |

### Gradle Dependencies
```kotlin
dependencies {
    implementation(project(":platform:platform-api"))
    implementation(libs.slf4j.api)
    testImplementation(project(":testing:test-support"))
}
```
JUnit Jupiter + AssertJ come from `homesynapse.java-conventions` (no explicit declaration needed).

## Consumers

- **lifecycle / app composition root (M13, planned):** selects `LinuxSystemPaths` + `SystemdHealthReporter` on Tier-1 (`/opt/homesynapse` present, `$NOTIFY_SOCKET` set) or `LocalPaths` + `NoOpHealthReporter` otherwise, and owns the watchdog heartbeat scheduling. No current consumers — this module is leaf-ward of the composition root.

## Cross-Module Contracts

- **No selection/wiring logic here.** Tier detection, reading `$NOTIFY_SOCKET`, and choosing impls are the composition root's responsibility (Doc 12 §7). This module only provides constructable impls.
- **`PlatformPaths` impls are immutable after construction.** All six `Path`s are resolved once and cached; each accessor returns the same instance (C12-10). The five writable dirs exist after construction; `tempDir` is empty after construction.
- **`SystemdHealthReporter` is send-and-forget and thread-safe.** `reportStatus(...)` (and the others) may be called from any thread; `READY=1` is sent at most once; send failures are logged, not thrown (the service manager handles persistent failure via restart, C12-03).

## Constraints

| Constraint | How it applies |
|---|---|
| **LTD-11 / AMD-26** | `SystemdHealthReporter` serialises sends with `ReentrantLock`, never `synchronized`. |
| **LTD-13** | `SystemdHealthReporter` targets the systemd `$NOTIFY_SOCKET`; `NoOpHealthReporter` is the non-systemd fallback. |
| **LTD-15** | SLF4J for the one logging site; no `System.out`/`System.err`. |
| **C12-10** | `tempDir()` contents cleared on construction; paths immutable after Phase 0. |
| **C12-03** | After `reportReady()`, `reportWatchdog()` sends `WATCHDOG=1` (heartbeat scheduling is lifecycle's). |
| **NO_DIRECT_TIME_ACCESS** | `com.homesynapse.platform..` is whitelisted, but no production or test code here accesses time anyway. |
| **`-Xlint:all -Werror`** | All classes `final` with explicit constructors; no redundant casts (the path-test null cases call the single-arg ctors without a cast). |

## Gotchas

**GOTCHA: pure JDK 21 cannot send/receive AF_UNIX `SOCK_DGRAM`.** `DatagramChannel.open(StandardProtocolFamily.UNIX)` compiles but throws `UnsupportedOperationException` at runtime on OpenJDK ≤ 21 (JEP 380 delivered Unix-domain *stream* sockets only). `SystemdHealthReporter`'s production `UnixDatagramTransport` is written to the correct API shape and is **isolated behind the `NotifyTransport` seam**, so unit tests (which inject a capturing transport) and the compile gate are GREEN. On a stock JDK the production transport reframes the opaque UOE as a clear `IllegalStateException` naming the M13 deferral (M5-A gate fix). A fully-functional Tier-1 reporter will need a native binding (JNR/JNA — not in the version catalog) or a `systemd-notify` subprocess fallback (PM ruling: deferred to M13; mechanism folded into the M5-D evidence lane alongside the GraalVM native-image decision). This is a composition-root / M13 concern; flagged as a TECHNICAL PUSHBACK in the M5-A coder-handoff + cross-agent-notes 2026-06-06. The same limitation makes a faithful real-socket unit test impossible in pure JDK, which is why the transport seam exists.

**GOTCHA: abstract-namespace sockets.** `$NOTIFY_SOCKET` may start with `@`, which maps to a leading NUL byte in the address. `UnixDatagramTransport` performs the `@`→`\0` translation, but `java.net.UnixDomainSocketAddress` is path-oriented and may not support NUL-containing (abstract) names — another reason the real transport is deployment-verified, not unit-tested.

**GOTCHA: never call the no-arg `PlatformPaths` constructors in tests.** `new LinuxSystemPaths()` would create `/var/lib/homesynapse` etc. (and `C:\var\...` on Windows); `new LocalPaths()` would pollute the real working tree. All tests use the package-private `fsRoot`/`baseDir` seam with a `@TempDir`.

## Phase 3 Notes

- **DP-1 (placement) resolved as instructed:** all four impls live in this one module (smallest footprint). The dev impls (`LocalPaths`/`NoOpHealthReporter`) are not strictly "systemd" — a future split into the composition root was offered but not taken (M5-A kept them here per instruction).
- **Directory-prep duplication:** `prepareWritableDirectories()` + `clearDirectoryContents()` are duplicated (identical) in `LinuxSystemPaths` and `LocalPaths` rather than extracted to a shared base/helper, to honour the instruction's exact file list ("deliberately small"). An abstract base or package-private helper is the obvious DRY refactor if a future WU wants it (flagged `[INFO]`).
- **Build gate deferred to Nick** (CLAUDE.md discipline). The one runtime risk (AF_UNIX datagram UOE) is NOT exercised by `./gradlew check` — it surfaces only when the real `SystemdHealthReporter(String)` ctor runs on a Tier-1 host (M13).
