# HomeSynapse Core — Project State Report

**Date:** April 8, 2026
**Version:** 0.1.0-SNAPSHOT
**Phase:** 2 (Interface Specification) → 3 (Test-First Implementation) transition
**Build Status:** GREEN — 129 tasks, all passing

---

## 1. Executive Summary

HomeSynapse Core is a local-first, event-sourced smart home operating system targeting Raspberry Pi 4/5 hardware. The project is organized as a multi-module Gradle build with 20 modules across 8 architectural layers, enforced by both compile-time dependency graph assertions and runtime ArchUnit rules. The codebase currently contains 535 Java source files: 406 in production code (Phase 2 interface specifications), 101 in test code, and 28 in test fixture source sets.

Phase 2 — the interface specification phase — is complete across all modules. The transition to Phase 3 has begun, with five abstract contract tests and their in-memory implementations verified GREEN. The test suite currently comprises 931 `@Test` methods organized across 228 `@Nested` test classes, with 1,250 `@DisplayName` annotations providing human-readable behavioral documentation.

The architecture makes several deliberate choices that distinguish it from competing platforms: single-writer SQLite for race condition elimination, pull-based event bus for deterministic ordering, contract test pattern for scalable verification, and Java 21 virtual threads for clean concurrency without asyncio complexity. These choices directly address known failure modes in Home Assistant, OpenHAB, and cloud-based platforms like SmartThings.

---

## 2. Architecture Overview

### 2.1 Dependency Layer Model

The dependency direction flows strictly inward. The build enforces this with `modules-graph-assert` (compile-time) and ArchUnit (test-time). Maximum hierarchy depth is 7 levels.

```
Layer 0: platform-api           (zero dependencies — java.base only)
Layer 1: event-model            (→ platform-api)
Layer 2: device-model           (→ event-model, platform-api)
         state-store            (→ device-model, event-model, platform-api)
         event-bus              (→ event-model)
Layer 3: persistence            (→ event-model, state-store, platform-api)
         automation             (→ event-model, device-model, state-store, event-bus)
Layer 4: configuration          (→ event-model)
         integration-api        (→ all core modules)
         integration-runtime    (→ integration-api, event-model)
         integration-zigbee     (→ integration-api)
Layer 5: observability          (→ event-model)
         rest-api, websocket-api(→ core, config, integration-api, observability)
Layer 6: lifecycle              (→ all layers except app)
Layer 7: homesynapse-app        (→ everything — assembly apex)
```

Forbidden dependency directions are codified in `build.gradle.kts`: core cannot depend on integration/API/app/lifecycle; platform cannot depend on core; `integration-api` cannot depend on `integration-runtime`.

### 2.2 Key Architectural Patterns

**Event Sourcing.** All state is derived from an append-only event log stored in SQLite. EventEnvelope carries 14 fields including globalPosition, subjectSequence, eventId, correlationId, causationId, and actorRef. The EventStore interface defines cursor-based reading (`readFrom(afterPosition, maxCount)`) where events with `globalPosition > afterPosition` are returned.

**Pull-Based Event Bus.** The EventBus notifies subscribers that new events exist; subscribers then pull from the EventStore at their own pace. This separates notification from delivery and allows subscribers to resume from checkpoints after restart. SubscriptionFilter supports conjunction of event type set, minimum priority, and subject type — with empty eventTypes meaning "match all," not "match none."

**Single-Writer SQLite.** All writes are serialized through a WriteCoordinator using a platform thread executor (not virtual threads, due to JNI carrier pinning with SQLite JDBC). WritePriority establishes a 5-level ordering: EVENT_PUBLISH, STATE_PROJECTION, WAL_CHECKPOINT, RETENTION, BACKUP.

**JPMS Modules.** Every production module has a `module-info.java`. Package-private types (like WriteCoordinator and WritePriority) remain unexported — the testFixtures source set shares the package namespace to access them without making types public.

**Virtual Threads (LTD-11).** No `synchronized` blocks anywhere in the codebase. All synchronization uses `ReentrantLock` or `ReentrantReadWriteLock` to avoid carrier thread pinning on the Pi's 4 cores.

### 2.3 Locked Design Decisions

The project governance tracks locked decisions in an external docs repository. Key decisions visible in the codebase:

- **LTD-04:** ULID for all identity — 8 typed wrappers (DeviceId, EntityId, IntegrationId, AreaId, AutomationId, PersonId, HomeId, SystemId) in platform-api, plus EventId in event-model. Stored as BLOB(16), serialized as Crockford Base32 at API boundaries.
- **LTD-08:** Jackson JSON exclusively for serialization.
- **LTD-10:** All dependency versions pinned in `libs.versions.toml`. Changes require deliberate, tested commits.
- **LTD-11:** ReentrantLock/ReentrantReadWriteLock only — no `synchronized`.
- **INV-ES-04:** Write-ahead persistence — events must be durable before subscriber notification.
- **INV-RF-03:** Integration failures are non-fatal — system degrades to DEGRADED and continues operating.

---

## 3. Module Inventory

### 3.1 Source File Counts by Module and Source Set

| Module | main | test | testFixtures | Total |
|--------|------|------|--------------|-------|
| **platform-api** | 15 | 5 | — | 20 |
| **platform-systemd** | 1 | — | — | 1 |
| **event-model** | 48 | 47 | 6 | 101 |
| **device-model** | 59 | 36 | 4 | 99 |
| **state-store** | 8 | 1 | 3 | 12 |
| **event-bus** | 6 | 6 | 5 | 17 |
| **persistence** | 14 | 1 | 3 | 18 |
| **automation** | 55 | — | — | 55 |
| **configuration** | 24 | 1 | 3 | 28 |
| **integration-api** | 22 | 1 | 4 | 27 |
| **integration-runtime** | 6 | — | — | 6 |
| **integration-zigbee** | 39 | — | — | 39 |
| **observability** | 20 | — | — | 20 |
| **rest-api** | 28 | — | — | 28 |
| **websocket-api** | 26 | — | — | 26 |
| **lifecycle** | 8 | — | — | 8 |
| **homesynapse-app** | 4 | 2 | — | 6 |
| **testing/test-support** | 13 | 1 | — | 14 |
| **spike/wal-validation** | 10 | — | — | 10 |
| **web-ui/dashboard** | — | — | — | — |
| **TOTALS** | **406** | **101** | **28** | **535** |

### 3.2 Module Summaries

**platform-api** (12 types). The dependency root — zero project dependencies. Houses the Ulid record, UlidFactory (monotonic generation with ReentrantLock), 8 typed ID wrappers, PlatformPaths, and HealthReporter interfaces. Fully specified and self-contained.

**event-model** (46 types). The largest core module. EventEnvelope (14 fields), EventDraft, CausalContext, SubjectRef, DomainEvent marker interface, EventPublisher, EventStore, EventPage. 22+ domain event payload records covering state, command, presence, automation, device lifecycle, configuration, system, and telemetry event categories. Exception hierarchy rooted at HomeSynapseException with 6 domain-specific subtypes. testFixtures complete since 2026-03-27/28.

**device-model** (57 types). Sealed Capability hierarchy (15 standard records + CustomCapability final class), sealed AttributeValue hierarchy (5 types: BooleanValue, IntValue, FloatValue, StringValue, EnumValue), sealed Expectation hierarchy (4 types). EntityType enum scoped to 6 MVP values. Device, Entity, CapabilityInstance records. EntityType and entity relationships are the most complex domain model.

**event-bus** (4 types). Deliberately minimal. EventBus interface (subscribe, unsubscribe, notifyEvent, subscriberPosition), SubscriberInfo record, SubscriptionFilter record (conjunction-based matching with factory methods), CheckpointStore interface. The pull-based model is the defining architectural choice — subscribers are notified, then pull from EventStore themselves.

**state-store** (7 types). Availability enum, EntityState (with staleAfter + Clock for staleness computed at read time), StateSnapshot, CheckpointRecord, and three interfaces (StateQueryService, StateStoreLifecycle, ViewCheckpointStore). ViewCheckpointStore is distinct from event-bus's CheckpointStore — the former tracks state projection positions, the latter tracks subscriber positions.

**persistence** (11+2 types). TelemetrySample, RingBufferStats, BackupOptions/Result, RetentionResult, VacuumResult, StorageHealth records. TelemetryWriter, TelemetryQueryService, PersistenceLifecycle, MaintenanceService interfaces. Plus the newly added package-private WriteCoordinator interface and WritePriority enum for internal write serialization. Write cascade: EVENT_PUBLISH → STATE_PROJECTION → WAL_CHECKPOINT → RETENTION → BACKUP.

**automation** (55 types). The richest Phase 2 specification. Covers rule evaluation, trigger conditions, action execution, and scheduling. No test code yet — implementation will be Phase 3.

**configuration** (22 types). ConfigurationService, ConfigAccess, SecretStore, ConfigValidator, ConfigMigrator, SchemaRegistry interfaces. ConfigModel, ConfigSection, ConfigChange, MigrationResult records. Severity, ReloadClassification, ChangeType enums. YAML-based with hot-reload support.

**integration-api** (21 types). IntegrationFactory, IntegrationAdapter, HealthReporter, CommandHandler interfaces. IntegrationLifecycleEvent sealed hierarchy (5 subtypes). IntegrationDescriptor, HealthParameters, IntegrationContext, CommandEnvelope records. Defines the contract that protocol-specific modules (Zigbee, future Z-Wave) implement.

**integration-runtime** (6 types). IntegrationSupervisor interface (9 methods) implementing OTP-style one-for-one supervision. ExceptionClassification enum, SlidingWindow and IntegrationHealthRecord records. Health state machine: HEALTHY → DEGRADED → SUSPENDED → FAILED.

**integration-zigbee** (39 types). IEEEAddress (64-bit long, NOT ULID), ZigbeeFrame sealed interface, ManufacturerCodec sealed interface (with non-sealed TuyaDpCodec and XiaomiTlvCodec), DeviceProfile, ZigbeeDeviceRecord. The most protocol-specific module.

**observability** (18+ types). Three-tier health aggregation (CRITICAL_INFRASTRUCTURE, CORE_SERVICES, INTERFACE_SERVICES). TraceEvent/TraceChain for distributed tracing with String identifiers. MetricsRegistry, MetricsStreamBridge (batch-oriented), LogLevelController interfaces.

**rest-api** (27 types), **websocket-api** (26 types). Phase 2 specifications for the two API layers. REST uses RFC 7807 ProblemDetail, cursor-based pagination, idempotency keys, ETag support. WebSocket uses a sealed WsMessage hierarchy (13 subtypes) with DeliveryMode and subscription filtering.

**lifecycle** (6 types). 10-phase FSM from BOOTSTRAP to STOPPED with 30-second shutdown budget. SubsystemStatus (6 states), SystemHealthSnapshot. SystemLifecycleManager interface coordinates startup/shutdown ordering.

**homesynapse-app** (2 types). Assembly apex with Main class and ExitCode enum. All module dependencies declared non-transitive (unique to this module). Manual DI — no framework.

**test-support** (9 types). Cross-cutting infrastructure: TestClock, SynchronousEventBus, NoRealIoExtension (JUnit extension blocking real I/O), GivenWhenThen, custom AssertJ assertions, EventCollector. No JPMS module-info — consumed on classpath.

**spike/wal-validation** (10 types). Performance validation programs (not JUnit tests) for SQLite WAL-mode: append throughput (C1), checkpoint durability (C2), kill-recovery (C3), virtual thread behavior (C4), jlink native image (C5), and V3 executor pattern validation.

---

## 4. Test Suite Analysis

### 4.1 Test Statistics Summary

| Metric | Count |
|--------|-------|
| `@Test` methods | 931 |
| `@Nested` test classes | 228 |
| `@DisplayName` annotations | 1,250 |
| Abstract contract tests | 5 |
| Concrete wiring tests | 5 |
| In-memory test fixtures | 7 |
| Test factory classes | 5 |
| ArchUnit rule sets | 1 (7 rules) |
| Spike validation programs | 6 |

### 4.2 Contract Test Pattern

The project's most distinctive testing feature is the contract test pattern: an abstract test class defines the complete behavioral specification for an interface, and concrete subclasses wire specific implementations to run against the same specification. This ensures that the in-memory test fixture and the eventual SQLite-backed production implementation are verified against identical behavioral expectations.

**Contract Test #1: EventStoreContractTest** — 27 test methods
Location: `core/event-model/src/testFixtures`
Covers: EventPublisher.publish(), EventStore.readFrom(), EventStore.readBySubject(), global position ordering, subject sequence ordering, causality propagation, EventPage semantics, empty store behavior, boundary conditions.
Concrete implementation: InMemoryEventStoreTest (inherits all 27 tests, adds 0).

**Contract Test #2: CheckpointStoreContractTest** — 9 test methods
Location: `core/event-bus/src/testFixtures`
Covers: unknown subscriber returns 0, write/read round-trip, overwrite semantics, per-subscriber isolation, position zero validity, negative position rejection, null subscriber rejection (read and write), Long.MAX_VALUE boundary.
Concrete implementation: InMemoryCheckpointStoreTest (inherits all 9, adds 0).

**Contract Test #3: EventBusContractTest** — 18 test methods in 4 `@Nested` tiers
Location: `core/event-bus/src/testFixtures`
Tier 1 — Subscription Lifecycle (5 tests): register, replace, unsubscribe, unknown-id no-op, checkpoint retention.
Tier 2 — Notification and Filtering (7 tests): matching filter, non-matching event type, non-matching priority, non-matching subject type, empty-eventTypes-matches-all, multiple-subscribers-only-matching-notified, coalesceExempt behavior.
Tier 3 — Checkpoint Integration (4 tests): unknown returns 0, write reflected, subscribe loads checkpoint, position-below-checkpoint skips.
Tier 4 — Concurrency Safety (2 tests): concurrent subscribe+notify, concurrent subscribe+unsubscribe.
Concrete implementation: InMemoryEventBusTest (inherits all 18, adds 0).

**Contract Test #4: ViewCheckpointStoreContractTest** — 10 test methods
Location: `core/state-store/src/testFixtures`
Covers: checkpoint read/write for state projections, overwrite, isolation, position validation.
Concrete implementation: InMemoryViewCheckpointStoreTest (inherits 10, adds 1).

**Contract Test #5: WriteCoordinatorContractTest** — 11 test methods in 4 `@Nested` tiers
Location: `core/persistence/src/testFixtures`
Tier 1 — Per-Priority Submission (5 tests): one test per WritePriority value.
Tier 2 — Generic Return Types (1 test): String, Integer, Long, Boolean, Void.
Tier 3 — Error Handling (3 tests): RuntimeException propagation, checked exception wrapping, failure isolation.
Tier 4 — Lifecycle and Concurrency (2 tests): shutdown + IllegalStateException, concurrent 4-thread 100-operation stress test.
Concrete implementation: InMemoryWriteCoordinatorTest (inherits all 11, adds 0).

### 4.3 In-Memory Test Fixtures

Each contract test is backed by a full-fidelity in-memory implementation that serves as both a test fixture and a reference implementation:

| Fixture | Location | Key Implementation Detail |
|---------|----------|--------------------------|
| InMemoryEventStore | event-model/testFixtures | ReentrantReadWriteLock, AtomicLong for global positions |
| InMemoryCheckpointStore | event-bus/testFixtures | ConcurrentHashMap, reset() for test isolation |
| InMemoryEventBus | event-bus/testFixtures | ReentrantReadWriteLock, synchronous delivery, filter evaluation |
| InMemoryViewCheckpointStore | state-store/testFixtures | ConcurrentHashMap-based |
| InMemoryWriteCoordinator | persistence/testFixtures | ReentrantLock, volatile shutdown flag, double-check after lock |
| InMemoryConfigAccess | configuration/testFixtures | Builder-pattern configuration access |
| StubIntegrationContext | integration-api/testFixtures | Stub with command routing and state callbacks |

### 4.4 Test Distribution by Module

| Module | @Test Count | Notable Coverage |
|--------|-------------|------------------|
| **event-model** | 434 | 23 event payload tests, EventEnvelope (35 tests), CausalContext (15), SubjectRef (18), exception hierarchy |
| **device-model** | 237 | 15 capability tests (6 each), 5 AttributeValue type tests (8-10 each), CustomCapability (21), Device (14), Entity (13) |
| **platform-api** | 85 | Ulid (52 tests across 9 nested classes), UlidFactory (12), PlatformPaths (11), HealthReporter (8) |
| **event-bus** | 86 | SubscriptionFilter (34 tests, 10 nested), SubscriberInfo (13), EventBus interface (7), CheckpointStore interface (5), + 27 inherited contract tests |
| **integration-api** | 39 | StubIntegrationContext (39 tests, 4 nested) |
| **test-support** | 14 | EventCollector (14) |
| **configuration** | 13 | InMemoryConfigAccess (13) |
| **persistence** | 11 | WriteCoordinator contract (11 inherited) |
| **state-store** | 11 | ViewCheckpointStore contract (10 inherited + 1) |
| **homesynapse-app** | 7 | ArchUnit rules (7 @ArchTest, not @Test) |

### 4.5 Architecture Enforcement Tests

`HomeSynapseArchRulesTest` in homesynapse-app uses ArchUnit's `@AnalyzeClasses` to scan the entire codebase and enforce 7 constitutional constraints:

1. **NO_SYNCHRONIZED_METHODS** — Enforces LTD-11 (virtual thread carrier pinning avoidance)
2. **NO_DIRECT_TIME_ACCESS** — Forces Clock injection for testability
3. **NO_SERVICE_LOADER** — Prevents SPI-based discovery (manual DI only)
4. **NO_REVERSE_DEPENDENCIES** — Enforces inward dependency direction
5. **NO_DIRECT_FILESYSTEM_IN_CORE** — Forces PlatformPaths abstraction
6. **NO_INTERNAL_PACKAGE_ACCESS** — Prevents reaching into `.internal` packages
7. **NO_JSON_TYPE_INFO_IN_EVENTS** — Keeps events serialization-neutral

### 4.6 Test Naming and Organization Conventions

All tests follow strict conventions:

- **Method naming:** `{method}_{scenario}_{expected}` (e.g., `subscribe_registersSubscriber`, `readCheckpoint_unknownSubscriber_returnsZero`)
- **`@DisplayName`** on every `@Test` method and every `@Nested` class — 1,250 total annotations providing prose-like behavioral documentation
- **`@Nested` inner classes** for logical grouping — 228 total, typically organized as tiers (Lifecycle, Filtering, Integration, Concurrency) or by method under test
- **`@BeforeEach`** calls abstract `reset*()` methods in contract tests to ensure test isolation

---

## 5. Build Infrastructure

### 5.1 Gradle Configuration

The build uses Gradle 8.8 with Kotlin DSL and a `build-logic` included build for convention plugins:

- **homesynapse.java-conventions** — Java 21 toolchain, `-Xlint:all -Werror`, UTF-8, JUnit 5 + AssertJ, Spotless (copyright headers, unused imports, trailing whitespace)
- **homesynapse.library-conventions** — Extends java-conventions with `java-library` for API/implementation dependency separation
- **homesynapse.test-fixtures-conventions** — Extends library-conventions with `java-test-fixtures` for contract test infrastructure
- **homesynapse.application-conventions** — Extends java-conventions with `application` for the app module

### 5.2 Dependency Version Catalog (libs.versions.toml)

| Category | Library | Version |
|----------|---------|---------|
| Runtime | Java | 21 |
| Serialization | Jackson | 2.18.6 |
| Storage | SQLite JDBC | 3.51.2.0 |
| Config | SnakeYAML Engine | 2.9 |
| HTTP | Javalin | 6.7.0 |
| Logging | SLF4J / Logback | 2.0.17 / 1.5.32 |
| Validation | JSON Schema Validator | 1.5.6 |
| Testing | JUnit 5 | 5.14.3 |
| Testing | AssertJ | 3.27.7 |
| Testing | ArchUnit | 1.4.0 |
| Build | Spotless | 7.0.2 |
| Build | Modules Graph Assert | 2.7.1 |

### 5.3 Copyright and Formatting

All source files carry the header `HomeSynapse Core / Copyright (c) $YEAR NexSys. All rights reserved.` — enforced by Spotless at build time. No SPDX identifier. Explicit constructors required on all non-record classes per `-Xlint:all -Werror`.

---

## 6. Coverage Gap Analysis

### 6.1 Modules with Zero Test Code

The following modules have completed Phase 2 specifications but contain no test code yet. These represent the primary coverage gaps and the priority queue for Phase 3 test-first implementation:

| Module | Main Types | Gap Severity | Notes |
|--------|-----------|--------------|-------|
| **automation** | 55 | **CRITICAL** | Largest untested module; rule evaluation, trigger conditions, action execution — all complex behavioral logic |
| **integration-zigbee** | 39 | **HIGH** | Protocol-specific parsing, frame encoding/decoding, manufacturer codec logic |
| **rest-api** | 28 | **HIGH** | Request routing, error mapping, pagination, idempotency, rate limiting |
| **websocket-api** | 26 | **HIGH** | Message routing, subscription management, delivery modes |
| **observability** | 20 | **MEDIUM** | Health aggregation, metrics batching, trace query |
| **integration-runtime** | 6 | **MEDIUM** | Supervisor lifecycle, health state machine, exception classification |
| **lifecycle** | 8 | **MEDIUM** | Phase FSM transitions, shutdown coordination, health snapshots |
| **platform-systemd** | 1 | **LOW** | Scaffold only; sd_notify integration |
| **web-ui/dashboard** | — | **LOW** | Preact SPA; separate build pipeline |

### 6.2 Interfaces Lacking Contract Tests

Several key interfaces have been specified but do not yet have abstract contract tests. When Phase 3 implementations begin, these will need contract tests before any production code is written:

| Interface | Module | Priority |
|-----------|--------|----------|
| EventPublisher | event-model | Covered by EventStoreContractTest (combined publisher+store) |
| StateQueryService | state-store | **HIGH** — complex query semantics, staleness calculation |
| StateStoreLifecycle | state-store | MEDIUM — lifecycle coordination |
| PersistenceLifecycle | persistence | MEDIUM — CompletableFuture startup, migration |
| MaintenanceService | persistence | MEDIUM — backup, retention, vacuum |
| TelemetryWriter / TelemetryQueryService | persistence | MEDIUM — ring buffer semantics |
| ConfigurationService / ConfigAccess | configuration | HIGH — hot-reload, validation, migration |
| IntegrationFactory / IntegrationAdapter | integration-api | HIGH — adapter lifecycle contract |
| IntegrationSupervisor | integration-runtime | **HIGH** — supervision state machine |
| AutomationEngine (future) | automation | **CRITICAL** — rule evaluation is the most complex domain logic |
| RestApiServer / RestApiLifecycle | rest-api | HIGH — request lifecycle |
| SystemLifecycleManager | lifecycle | HIGH — 10-phase FSM |
| HealthAggregator | observability | MEDIUM — three-tier aggregation |

### 6.3 Specific Test Gaps Within Tested Modules

**event-model:** The stale `com.homesynapse.event.InMemoryEventStore` (444 lines, ReentrantLock) exists alongside the canonical `com.homesynapse.event.test.InMemoryEventStore` (425 lines, ReentrantReadWriteLock). The stale version should be removed.

**device-model:** CustomCapability has the most complex test (21 tests), but capability *interaction* tests (e.g., what happens when an entity has conflicting capabilities, or capability schema validation at the entity level) are not yet covered.

**event-bus:** SubscriptionFilter has excellent coverage (34 tests), but filter *composition* (multiple filters applied to the same event stream) is not tested at the contract level.

**persistence:** WriteCoordinator contract is complete, but the telemetry ring buffer contract (slot-based overwrite with `seq % max_rows`) has no contract test yet. This is the most subtle persistence contract and will need careful testing.

---

## 7. Comparative Analysis: HomeSynapse vs. Industry

### 7.1 Strengths Relative to Other Platforms

**Race Condition Elimination (vs. Home Assistant, OpenHAB).** Home Assistant's thread-per-integration model combined with asyncio creates race conditions where simultaneous state reads return stale values. OpenHAB's OSGi-based distributed state has weak consistency guarantees. HomeSynapse's single-writer SQLite model eliminates this entire failure class at the architectural level. The WriteCoordinator serializes all database writes through a ReentrantLock on a dedicated platform thread, and the event store's append-only model means reads never conflict with writes.

**Deterministic Event Ordering (vs. SmartThings, cloud platforms).** Cloud-based platforms suffer from network reordering where device commands arrive out-of-sequence. HomeSynapse's pull-based event bus with monotonic global positions guarantees total ordering. Subscribers can always resume from a known checkpoint and process events in exactly the order they were committed.

**Contract Test Scalability (vs. OpenHAB's integration test burden).** OpenHAB documentation explicitly warns to use integration tests "sparingly" because they require starting OSGi services. This creates a tragic trade-off: either test properly and accept 30+ minute CI cycles, or skip integration tests and ship undertested code. HomeSynapse's contract test pattern achieves integration-test confidence at unit-test speed. The 5 existing contract tests (75 methods total) run in under a second because they use in-memory fixtures, but they verify the exact same behavioral contracts that SQLite-backed implementations will satisfy.

**Clean Concurrency Model (vs. Python asyncio).** Home Assistant's pytest-asyncio model runs async tests sequentially by default, and developers regularly encounter "event loop is already running" errors. HomeSynapse's virtual threads eliminate the event loop abstraction entirely — synchronous-looking code runs on lightweight threads with no colored function problem.

**Compile-Time Architecture Enforcement (unique).** Neither Home Assistant nor OpenHAB have compile-time dependency direction enforcement. HomeSynapse uses `modules-graph-assert` at build time AND ArchUnit at test time, creating two independent enforcement layers. The 7 ArchUnit rules (no synchronized, no direct time access, no service loader, no reverse dependencies, no direct filesystem in core, no internal package access, no JSON type info in events) are constitutional constraints that cannot be violated without a test failure.

### 7.2 Weaknesses to Address

**No Idempotency Testing.** Research on event-sourced systems identifies idempotent event handling as the critical safety property. If event handlers aren't idempotent, event replay breaks state. HomeSynapse currently has no tests that verify "processing the same event twice produces identical state." This should be a contract-level requirement for every event handler.

**No Given-When-Then Domain Tests.** The emerging best practice for event-sourced systems is the Given-When-Then pattern: load a stream of past events, issue a command, assert the new events produced. This tests business logic without any infrastructure. HomeSynapse's test-support module includes a `GivenWhenThen` class, but it has not yet been used in any domain tests.

**No Constrained Hardware Testing.** The spike/wal-validation programs measure SQLite throughput, but the test suite itself has not been profiled for memory footprint on Pi 4/5 hardware. A test suite consuming 500 MB per runner could be problematic at scale on constrained devices.

**No Failure Mode Tests.** The project has no tests for graceful degradation scenarios: disk full during event persistence, SQLite database corruption, integration adapter crash mid-operation, WAL checkpoint failure. These are the scenarios that differentiate a production-grade system from a prototype. INV-RF-03 (integration failures are non-fatal) is a stated invariant but has no tests verifying it.

**No State Verification Pattern.** Current tests verify "was the event persisted?" but not "did the state projection reflect the event correctly?" The stronger pattern — as used by Amazon Alexa and Google Home test tools — verifies the complete chain: pre-condition state → command → event → state transition → post-condition state.

### 7.3 Lessons from Platform Failures

**Home Assistant's 800+ Integration Problem.** With hundreds of integrations, each requiring its own test infrastructure, HA's CI cycle is measured in tens of minutes. HomeSynapse should ensure that the contract test pattern scales to integration adapter testing — define a single `IntegrationAdapterContractTest` that all protocol adapters satisfy, rather than allowing each adapter to build its own test infrastructure.

**OpenHAB's Inconsistent Test Conventions.** OpenHAB's documentation reveals inconsistent naming ("Test," "Stub," "Mock" used interchangeably) and no standardized test patterns across add-ons. HomeSynapse's strict conventions (method naming, @DisplayName, @Nested organization, testFixtures source sets) already address this, but must be maintained as the team grows.

**SmartThings' Simulation Gap.** SmartThings' virtual devices pass commands synchronously, creating false confidence. Tests pass against simulated devices but fail in production because simulations don't model timing, network delays, or state verification failures. HomeSynapse should ensure that its in-memory test fixtures faithfully model the timing and failure characteristics of real implementations, not just the happy path.

---

## 8. Prioritized Recommendations

### 8.1 Immediate (Next 2-3 Milestones)

**R-01: Add event handler idempotency contract.** Define an abstract test that verifies: given an event handler, processing the same event envelope twice produces identical state. This should be a mandatory property for every subscriber implementation. Severity: CRITICAL.

**R-02: Implement StateQueryService contract test.** The state store is the most-queried interface in the system. Its staleness calculation (staleAfter + Clock, computed at read time) is subtle and must be tested before any implementation. Severity: HIGH.

**R-03: Implement IntegrationAdapterContractTest.** Define the behavioral contract for all protocol adapters once, so Zigbee (and future Z-Wave, Matter) adapters all satisfy the same test specification. Severity: HIGH.

**R-04: Remove stale InMemoryEventStore.** The non-canonical copy at `com.homesynapse.event.InMemoryEventStore` (ReentrantLock, 444 lines) should be deleted. Only `com.homesynapse.event.test.InMemoryEventStore` (ReentrantReadWriteLock, 425 lines) should exist. Severity: LOW (housekeeping).

### 8.2 Medium-Term (Phase 3 Foundation)

**R-05: Build Given-When-Then domain test harness.** Leverage the existing GivenWhenThen class in test-support to create the event-sourced testing pattern: given(past events) → when(command) → then(new events). This should be the standard pattern for all automation and state projection tests.

**R-06: Add failure mode tests.** Create a test category for degradation scenarios: disk full, database corruption, adapter crash, WAL failure. Verify that INV-RF-03 (non-fatal integration failures) holds under each condition. These should be integration tests, not unit tests.

**R-07: Profile test suite on Pi 4/5.** Measure memory footprint, execution time, and SQLite I/O during the full test run on target hardware. Establish baseline metrics and fail the build if test memory exceeds a threshold.

**R-08: Add telemetry ring buffer contract test.** The ring buffer's slot-based overwrite semantics (`seq % max_rows`) are the most subtle persistence contract. A contract test should verify: writes wrap correctly, reads return most recent data, capacity limits are enforced, and concurrent writes are serialized.

### 8.3 Long-Term (Production Readiness)

**R-09: Implement mutation testing.** Use a tool like PIT to verify that test assertions actually catch bugs. Contract tests with high line coverage but weak assertions create false confidence. Mutation testing reveals where assertions should be strengthened.

**R-10: Add property-based tests for ULID ordering.** The Ulid type is the foundation of all identity and ordering in the system. Property-based tests (using jqwik or similar) should verify: monotonic generation, Crockford Base32 round-trip, byte-array round-trip, comparison consistency, and overflow behavior.

**R-11: Implement end-to-end scenario tests.** Define 3-5 critical user scenarios (e.g., "turn on light via REST API → event persisted → state projected → WebSocket notification delivered") and test them end-to-end with all modules wired together. These should run on every CI build but are expected to be slow.

---

## 9. Design Document and Traceability Status

### 9.1 Design Documents (docs/handoff/)

All 17 Phase 2 handoff blocks are complete:

| Block | Module | Status |
|-------|--------|--------|
| B | Event Envelope | Complete |
| D | Publisher/Store | Complete |
| E | Event Bus | Complete |
| F | Platform API | Complete |
| G | Device Model | Complete |
| H | State Store | Complete |
| I | Integration API | Complete |
| J | Persistence | Complete |
| K | Configuration | Complete |
| L | Automation | Complete |
| M | REST API | Complete |
| N | WebSocket API | Complete |
| O | Integration Runtime | Complete |
| P | Integration Zigbee | Complete |
| Q | Observability | Complete |
| R | Lifecycle | Complete |
| S | HomeSynapse App | Complete |

### 9.2 Traceability Matrix

14 traceability documents exist under `docs/traceability/`, mapping design document sections to interface/type definitions and test classes. Document 14 (Master Architecture) is a template that is currently unpopulated.

---

## 10. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Contract tests pass for in-memory but fail for SQLite impl | MEDIUM | HIGH | Ensure in-memory fixtures faithfully model SQLite semantics (ACID, WAL, single-writer) |
| Test suite too slow on Pi 4/5 hardware | MEDIUM | MEDIUM | Profile early, set memory/time thresholds, keep unit tests under 30s total |
| Event handler idempotency violations discovered late | HIGH | HIGH | Add idempotency contract testing (R-01) before any handler implementation |
| Automation module complexity explosion | MEDIUM | HIGH | Start with contract test for simplest automation (single trigger → single action) and expand incrementally |
| Virtual thread carrier pinning on JNI calls | LOW | HIGH | Already mitigated by platform thread executor for SQLite writes; spike/C4 validates |
| Stale in-memory implementations diverge from production | MEDIUM | MEDIUM | Contract tests prevent this by definition — both must pass the same spec |

---

## 11. Appendix: Complete File Tree (Test Code Only)

```
app/homesynapse-app/
  src/test/
    HomeSynapseArchRulesTest.java    (7 @ArchTest rules)
    HomeSynapseArchRules.java        (rule definitions)

config/configuration/
  src/test/
    InMemoryConfigAccessTest.java    (13 @Test)
  src/testFixtures/
    InMemoryConfigAccess.java
    TestConfigFactory.java
    package-info.java

core/device-model/
  src/test/
    36 test files                    (237 @Test total)
  src/testFixtures/
    TestCapabilityFactory.java
    TestDeviceFactory.java
    TestEntityFactory.java
    package-info.java

core/event-bus/
  src/test/
    SubscriptionFilterTest.java      (34 @Test)
    SubscriberInfoTest.java          (13 @Test)
    EventBusTest.java                (7 @Test)
    CheckpointStoreTest.java         (5 @Test)
    InMemoryEventBusTest.java        (0 — inherits 18)
    InMemoryCheckpointStoreTest.java (0 — inherits 9)
  src/testFixtures/
    EventBusContractTest.java        (18 @Test, abstract)
    CheckpointStoreContractTest.java (9 @Test, abstract)
    InMemoryEventBus.java
    InMemoryCheckpointStore.java
    package-info.java

core/event-model/
  src/test/
    47 test files                    (434 @Test total)
  src/testFixtures/
    EventStoreContractTest.java      (27 @Test, abstract)
    InMemoryEventStore.java
    TestEventFactory.java
    TestCausalContext.java
    package-info.java

core/persistence/
  src/test/
    InMemoryWriteCoordinatorTest.java (0 — inherits 11)
  src/testFixtures/
    WriteCoordinatorContractTest.java (11 @Test, abstract)
    InMemoryWriteCoordinator.java
    package-info.java (in .test subpackage)

core/state-store/
  src/test/
    InMemoryViewCheckpointStoreTest.java (1 @Test + inherits 10)
  src/testFixtures/
    ViewCheckpointStoreContractTest.java (10 @Test, abstract)
    InMemoryViewCheckpointStore.java
    package-info.java

integration/integration-api/
  src/test/
    StubIntegrationContextTest.java  (39 @Test)
  src/testFixtures/
    StubIntegrationContext.java
    StubCommandHandler.java
    TestAdapter.java
    package-info.java

platform/platform-api/
  src/test/
    UlidTest.java                    (52 @Test)
    UlidFactoryTest.java             (12 @Test)
    TypedIdTest.java                 (2 @Test)
    PlatformPathsTest.java           (11 @Test)
    HealthReporterTest.java          (8 @Test)

testing/test-support/
  src/test/
    EventCollectorTest.java          (14 @Test)
```
