# platform-api — `com.homesynapse.platform` — 12 types — Dependency root, zero project dependencies, typed ULID identity system

## Purpose

The platform-api module is the dependency root of the entire HomeSynapse Core project. It provides two categories of cross-cutting infrastructure: (1) the typed ULID identity system that every domain object in HomeSynapse uses for globally unique, monotonically sortable identification, and (2) platform abstraction interfaces (PlatformPaths, HealthReporter) that decouple the core from deployment-tier specifics like filesystem layout and systemd health reporting. This module has zero dependencies on other project modules — every other module in the system depends on it.

## Design Doc Reference

No single governing design doc — platform-api is cross-cutting infrastructure referenced by multiple documents:
- **Identity and Addressing Model v1** (foundations) — §1 through §2.1: defines the three-layer identity architecture, ULID format, generation rules, and encoding (binary BLOB(16) vs. Crockford Base32 text). This is the primary reference for all typed ID wrappers.
- **Doc 12 — Startup, Lifecycle & Shutdown** — §8.2: HealthReporter interface specification; §8.3: PlatformPaths interface specification; §7.1–§7.2: Portability architecture.

## JPMS Module

```
module com.homesynapse.platform {
    exports com.homesynapse.platform;
    exports com.homesynapse.platform.identity;
}
```

No `requires` clauses — this module depends only on `java.base`.

## Package Structure

- **`com.homesynapse.platform`** — Platform abstraction interfaces: `PlatformPaths` (filesystem layout contract) and `HealthReporter` (supervisor health reporting contract).
- **`com.homesynapse.platform.identity`** — The ULID value type (`Ulid`), its generator (`UlidFactory`), and 8 typed ID wrapper records that provide compile-time type safety for domain object identity.

## Complete Type Inventory

### Package: `com.homesynapse.platform.identity`

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `Ulid` | record(`long msb`, `long lsb`) implements `Comparable<Ulid>` | Immutable 128-bit ULID value type — the foundation identity type for all HomeSynapse domain objects | Methods: `toString()` (26-char Crockford Base32), `parse(String)`, `toBytes()` (16-byte big-endian), `fromBytes(byte[])`, `extractTimestamp()`, `isValid(String)`, `compareTo(Ulid)`. Unsigned comparison preserves chronological + lexicographic ordering. |
| `UlidFactory` | final class (utility, no instantiation) | Thread-safe monotonic ULID generator | Static methods: `generate()`, `generate(Clock)`. Uses `ReentrantLock` (not `synchronized`) per LTD-01 for virtual thread compatibility. `SecureRandom` for randomness. Monotonic within millisecond (increments random component). Clock-backward tolerant. |
| `DeviceId` | record(`Ulid value`) implements `Comparable<DeviceId>` | Typed identifier for a physical device | Factory: `of(Ulid)`, `parse(String)`. Identifies hardware. New ID on device replacement. |
| `EntityId` | record(`Ulid value`) implements `Comparable<EntityId>` | Typed identifier for a logical entity (functional unit of a device) | Factory: `of(Ulid)`, `parse(String)`. Stable across hardware replacements via `entity_transferred`. Primary subject of state/command events. |
| `IntegrationId` | record(`Ulid value`) implements `Comparable<IntegrationId>` | Typed identifier for an integration adapter instance | Factory: `of(Ulid)`, `parse(String)`. Appears in event origin metadata. Used for command routing. |
| `AreaId` | record(`Ulid value`) implements `Comparable<AreaId>` | Typed identifier for a spatial area (room, zone, floor) | Factory: `of(Ulid)`, `parse(String)`. Used for scoped automation and UI organization. |
| `AutomationId` | record(`Ulid value`) implements `Comparable<AutomationId>` | Typed identifier for an automation rule definition | Factory: `of(Ulid)`, `parse(String)`. Stable across edits. Subject reference for automation execution events. |
| `PersonId` | record(`Ulid value`) implements `Comparable<PersonId>` | Typed identifier for a person (occupant/user) | Factory: `of(Ulid)`, `parse(String)`. Privacy-sensitive — presence events keyed by PersonId are a crypto-shredding boundary (INV-PD-07). |
| `HomeId` | record(`Ulid value`) implements `Comparable<HomeId>` | Typed identifier for the physical dwelling/site | Factory: `of(Ulid)`, `parse(String)`. One per installation in MVP. Distinct from SystemId — survives reinstallation. |
| `SystemId` | record(`Ulid value`) implements `Comparable<SystemId>` | Typed identifier for the HomeSynapse software instance | Factory: `of(Ulid)`, `parse(String)`. Assigned at first startup. Subject reference for system lifecycle events (system_started, system_stopped, config_changed). |

### Package: `com.homesynapse.platform`

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `PlatformPaths` | interface | Abstracts deployment-tier filesystem layout | Methods: `binaryDir()`, `configDir()`, `dataDir()`, `logDir()`, `backupDir()`, `tempDir()`. Returns absolute `Path` instances. Resolved once during Phase 0, cached, immutable after. `tempDir()` cleaned on each startup. |
| `HealthReporter` | interface | Abstracts platform supervisor health reporting | Methods: `reportReady()`, `reportWatchdog()`, `reportStopping()`, `reportStatus(String)`. On Tier 1 (Linux/systemd): sends sd_notify messages. On other tiers: no-op. Watchdog must be called every WatchdogSec/2 (default 30s) after reportReady(). |

**Total: 12 public types + 2 package-info.java files + 1 module-info.java = 15 Java files.**

## Dependencies

None. This module has zero project dependencies. It depends only on `java.base` (JDK standard library). This is by design — platform-api is the dependency root of the entire project.

## Consumers

### Current consumers (modules with completed Phase 2 specs):
- **event-model** (`com.homesynapse.event`) — `requires transitive com.homesynapse.platform`. Uses: `Ulid`, `UlidFactory`, `EntityId`, `DeviceId`, `IntegrationId`, `AutomationId`, `SystemId`, `PersonId` for `SubjectRef` construction and `EventId` wrapping.
- **event-bus** (`com.homesynapse.event.bus`) — Transitive through event-model. Uses typed IDs indirectly through `EventEnvelope` and `SubjectRef` in filter evaluation.
- **device-model** (`com.homesynapse.device`) — `requires transitive com.homesynapse.platform`. Uses: `DeviceId`, `EntityId`, `IntegrationId`, `AreaId` as fields on `Device`, `Entity`, and discovery pipeline types.

### Planned consumers (from design doc dependency graph):
- **state-store** — Will use `EntityId` for state queries.
- **persistence** — Will use `Ulid` for BLOB(16) storage, all typed IDs for table key columns.
- **integration-runtime** — Will use `IntegrationId`, `EntityId`, `DeviceId`, `HealthReporter`, `PlatformPaths`.
- **configuration** — Will use `PlatformPaths.configDir()`.
- **automation** — Will use `AutomationId`, `EntityId`.
- **rest-api** — Will use all typed IDs for request/response serialization (Crockford Base32 text form).
- **startup-lifecycle** — Will use `PlatformPaths`, `HealthReporter`, `SystemId`.
- Eventually ALL modules — every module needs typed identity.

## Cross-Module Contracts

- **Typed ID wrappers are compile-time type safety, not runtime validation.** Passing a raw `Ulid` where a `DeviceId` is expected is a type error. There is no runtime type tag inside the ULID itself — the type safety is purely structural (single-field record wrapping `Ulid`).
- **`UlidFactory.generate()` is thread-safe and monotonic.** Multiple threads calling `generate()` concurrently will receive strictly ordered ULIDs within the same millisecond. Uses `ReentrantLock`, not `synchronized`, so virtual threads unmount correctly while waiting.
- **`Ulid.toBytes()` produces big-endian BLOB(16).** SQLite byte-comparison on BLOB(16) preserves ULID lexicographic ordering. Text representation (Crockford Base32) is used ONLY at API boundaries — never in storage.
- **`PlatformPaths` is immutable after Phase 0.** All path values are resolved once during platform detection and cached. Implementations must be thread-safe for concurrent reads from any subsystem.
- **`HealthReporter.reportReady()` is called exactly once.** After this call, the watchdog interval is enforced. Missing `reportWatchdog()` calls cause process restart on Tier 1.

## Constraints

| Constraint | Description |
|---|---|
| **LTD-04** | ULID for all event and entity identity. 128-bit, BLOB(16) storage, Crockford Base32 text. |
| **LTD-08** | Jackson JSON for all serialization. Typed ID wrappers need Jackson serializer/deserializer support (Phase 3). |
| **Virtual Thread Risk Audit (AMD-26)** | No `synchronized` blocks — use `ReentrantLock` for virtual thread compatibility (reinforced by LTD-04's carrier thread safety requirement). `UlidFactory` already complies. |
| **INV-CS-02** | Entity identifiers are stable. EntityId never changes unless explicitly renamed. System upgrades and config changes must not alter identifiers. |
| **INV-CE-04** | Protocol agnosticism in the device model. Typed IDs carry no protocol semantics — identity is opaque and stable (Principle P1 from Identity & Addressing Model). |
| **INV-ES-07** | Event schema evolution. Typed ID text representations must remain forward-compatible within major version. |

## Sealed Hierarchies

None. This module contains no sealed types.

## Key Design Decisions

1. **Typed ULID wrappers instead of raw String/UUID.** Each domain concept (Device, Entity, Integration, etc.) has its own record wrapping `Ulid`, preventing accidental cross-domain identity confusion at compile time. A raw `Ulid` or `String` can never be passed where a `DeviceId` is expected. The alternative (raw ULIDs with runtime checks) was rejected because it defers errors to runtime. Reference: Identity & Addressing Model §2.1.

2. **`UlidFactory` uses `ReentrantLock` instead of `synchronized`.** `synchronized` blocks pin virtual threads to carrier threads. On a Raspberry Pi with 4 cores (4 carrier threads), one pinned carrier is a 25% capacity loss. `ReentrantLock` allows the virtual thread to unmount while waiting. Reference: Virtual Thread Risk Audit (AMD-26).

3. **8 typed ID wrappers in the identity package** (DeviceId, EntityId, IntegrationId, AreaId, AutomationId, PersonId, HomeId, SystemId). EventId is deliberately in event-model (`com.homesynapse.event`), not here, because it is event-specific. SubscriberId in event-bus is a plain `String`, not a typed wrapper, because subscribers are not domain objects.

4. **No external ULID library dependency.** `Ulid` and `UlidFactory` are implemented from scratch rather than using an external library (e.g., ulid-creator). This eliminates an external dependency for the lowest-level module and ensures full control over the monotonic generation algorithm and virtual thread compatibility.

5. **`PlatformPaths` and `HealthReporter` are interfaces, not concrete classes.** Implementations are selected during Phase 0 based on deployment tier detection. On Tier 1 (Linux/systemd): `LinuxSystemPaths` + `SystemdHealthReporter`. On development tier: `LocalPaths` + `NoOpHealthReporter`. This supports the portability architecture (Doc 12 §7).

## Gotchas

**GOTCHA: EventId is NOT in platform-api.** Despite being a typed ULID wrapper like the others, `EventId` lives in `com.homesynapse.event` (the event-model module), not in `com.homesynapse.platform.identity`. This is because EventId is specific to the event subsystem. Do not add it here.

**GOTCHA: SubscriberId is a plain String, not a typed wrapper.** Unlike domain object IDs, subscriber identifiers in the event-bus module are plain `String` values. This is deliberate — subscribers are not domain objects and don't need ULID identity.

**GOTCHA: `UlidFactory` has static mutable state.** The `lastTimestamp`, `lastMsb`, and `lastLsb` fields are static. This means monotonicity is global to the JVM process. In tests, be aware that ULIDs generated across different test methods share this state. The `generate(Clock)` overload exists for deterministic testing.

**GOTCHA: `Ulid.compareTo()` uses unsigned comparison.** Java `long` is signed, but ULID comparison must treat the 64-bit halves as unsigned values. `Long.compareUnsigned()` is used. Do not use `Long.compare()` — it will produce incorrect ordering for ULIDs with high bit set.

**GOTCHA: `Ulid.toString()` is Crockford Base32, not standard Base32.** Crockford Base32 excludes I, L, O, U and maps them to 1, 1, 0, (invalid) respectively during decoding. Standard Base32 (RFC 4648) uses a different alphabet. Do not confuse them.

## Phase 3 Notes

- **PlatformPaths needs implementations:** `LinuxSystemPaths` (Tier 1) and `LocalPaths` (development). Both are straightforward — resolve paths and cache them. Test with temporary directories.
- **HealthReporter needs implementations:** `SystemdHealthReporter` (sends sd_notify via Unix domain socket) and `NoOpHealthReporter` (all methods are no-ops). The systemd implementation is Tier 1 only.
- **Jackson serialization for typed IDs — IMPLEMENTED externally (M2.4, 2026-04-10, persistence module).** Typed ID wrappers remain Jackson-annotation-free by design. Serialization is handled externally in `com.homesynapse.persistence.PersistenceJacksonModule`, which registers a generic `TypedUlidSerializer<T>` / `TypedUlidDeserializer<T>` pair per wrapper using method references to the wrapper's existing `toString()` and static `parse(String)` methods (`EntityId::toString`, `EntityId::parse`, etc.). This keeps `platform-api` free of any Jackson dependency — the JPMS module does not `require` Jackson, and no source file imports `com.fasterxml.jackson.*`. All 8 typed wrappers (`EntityId`, `DeviceId`, `AreaId`, `AutomationId`, `PersonId`, `HomeId`, `IntegrationId`, `SystemId`) plus the raw `Ulid` are covered. Do NOT add Jackson annotations to this module — the external serde approach is the locked pattern and enforcing Jackson isolation to `core/persistence` is an M2.4 invariant.
- **Testing strategy:** Unit tests for `Ulid` (encode/decode round-trip, comparison ordering, edge cases), `UlidFactory` (monotonicity within millisecond, clock backward tolerance, thread safety), and each typed wrapper (null rejection, parse/format round-trip). No integration tests needed — this module has no external dependencies.
- **Performance:** `UlidFactory.generate()` is on the hot path for every event publication. The `ReentrantLock` contention under high event throughput should be profiled. The target is sub-microsecond generation time.
