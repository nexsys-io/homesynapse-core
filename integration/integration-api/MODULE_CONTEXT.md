# integration-api — `com.homesynapse.integration` — 21 types — Adapter-facing API boundary, re-exports all core modules, IntegrationFactory/Adapter/Context

## Purpose

The Integration API module defines the adapter-facing contract boundary between protocol-specific integration adapters (Zigbee, MQTT, cloud APIs) and the HomeSynapse event-sourced core (Doc 05 §8). It specifies *what an adapter declares and receives* — factory discovery, lifecycle phases, health reporting, command handling, context injection, and lifecycle event types. The companion module `integration-runtime` (Block O) specifies *what the supervisor does with those declarations* — lifecycle management, health state machine, restart intensity, and thread allocation.

This is the single module that every integration adapter depends on. It re-exports all the core modules an adapter needs (event-model, device-model, state-store, persistence, configuration, platform-api, java.net.http) via `requires transitive`, so adapter modules only need to declare `requires com.homesynapse.integration`.

The Phase 2 specification contains 21 public Java types: 4 enums (HealthState, IoType, RequiredService, DataPath), 9 records (4 non-lifecycle: IntegrationDescriptor, HealthParameters, IntegrationContext, CommandEnvelope; 5 lifecycle event subtypes: IntegrationStarted, IntegrationStopped, IntegrationHealthChanged, IntegrationRestarted, IntegrationResourceExceeded), 1 sealed interface (IntegrationLifecycleEvent), 4 service interfaces (IntegrationFactory, IntegrationAdapter, HealthReporter, CommandHandler), 2 optional service interfaces (SchedulerService, ManagedHttpClient), and 1 exception class (PermanentIntegrationException).

## Design Doc Reference

**Doc 05 — Integration Runtime** is the governing design document:
- §3.2: Thread allocation — IoType determines virtual vs platform thread
- §3.7: Exception classification — PermanentIntegrationException → FAILED, other RuntimeException → transient retry
- §3.8: IntegrationContext composition — the composed API surface injected into each adapter
- §3.9: ManagedHttpClient — resource-controlled HTTP client for cloud-connected adapters
- §4.1: IntegrationDescriptor — static declaration of adapter requirements
- §4.2: HealthParameters — per-integration health thresholds and restart limits
- §4.3: HealthState — four-state health model (HEALTHY, DEGRADED, SUSPENDED, FAILED)
- §4.4: IntegrationLifecycleEvent — sealed hierarchy of lifecycle event payloads
- §8.1: IntegrationFactory, IntegrationAdapter, HealthReporter, CommandHandler interfaces
- §8.2: CommandEnvelope, lifecycle event records
- §8.3: IntegrationFactory (direct construction per DECIDE-04)
- §8.4: IntegrationAdapter lifecycle phases (initialize → run → close)
- §8.5: HealthReporter — adapter-to-supervisor health signal channel
- §8.6: CommandHandler — command dispatch callback

## JPMS Module

```
module com.homesynapse.integration {
    requires transitive com.homesynapse.platform;
    requires transitive com.homesynapse.event;
    requires transitive com.homesynapse.device;
    requires transitive com.homesynapse.state;
    requires transitive com.homesynapse.persistence;
    requires transitive com.homesynapse.config;
    requires transitive java.net.http;

    exports com.homesynapse.integration;
}
```

All `requires transitive` because adapter modules use types from all these modules (EventPublisher, EntityRegistry, StateQueryService, TelemetryWriter, ConfigurationAccess, IntegrationId, HttpRequest/HttpResponse) and should not need to re-declare them. The `java.net.http` transitive is for ManagedHttpClient's method signatures which reference `HttpRequest` and `HttpResponse`.

## Package Structure

**`com.homesynapse.integration`** — Single flat package. 21 Java files total.

## Complete Type Inventory

### Enums (4)

| Type | Purpose |
|---|---|
| `HealthState` (4 values) | HEALTHY, DEGRADED, SUSPENDED, FAILED. Four-state health model for integration lifecycle (Doc 05 §4.3). |
| `IoType` (2 values) | SERIAL (platform thread, JNI pinning), NETWORK (virtual thread). Determines supervisor thread allocation. |
| `RequiredService` (3 values) | HTTP_CLIENT, SCHEDULER, TELEMETRY_WRITER. Gates optional service provisioning in IntegrationContext. |
| `DataPath` (2 values) | DOMAIN (event store), TELEMETRY (ring buffer). Declares adapter data routing. |

### Records (9)

| Type | Fields | Purpose |
|---|---|---|
| `IntegrationDescriptor` (8 fields) | integrationType, displayName, ioType, requiredServices, dataPaths, healthParameters, dependsOn, schemaVersion | Static adapter declaration. Collection fields defensively copied to unmodifiable sets. |
| `HealthParameters` (11 fields) | heartbeatTimeout, healthWindowSize, maxDegradedDuration, maxSuspendedDuration, maxSuspensionCycles, maxRestarts, restartWindow, probeInitialDelay, probeMaxDelay, probeCount, probeSuccessThreshold | Health monitoring thresholds. `defaults()` factory for network polling adapters. |
| `IntegrationContext` (10 fields) | integrationId, integrationType, eventPublisher, entityRegistry, stateQueryService, healthReporter, configAccess, schedulerService (nullable), telemetryWriter (nullable), httpClient (nullable) | Composed API surface injected into adapter. Optional fields null if RequiredService not declared. |
| `CommandEnvelope` (6 fields) | entityRef, commandName, parameters, commandEventId, correlationId, integrationId | Dispatched command delivered to CommandHandler. Parameters defensively copied. |
| `IntegrationStarted` (4 fields) | integrationId, integrationType, newState, reason | Lifecycle event. previousState() always null (initial start). |
| `IntegrationStopped` (5 fields) | integrationId, integrationType, previousState, newState, reason | Lifecycle event. Shutdown or health threshold breach. |
| `IntegrationHealthChanged` (6 fields) | integrationId, integrationType, previousState, newState, reason, healthScore | Lifecycle event. Includes healthScore at transition time. |
| `IntegrationRestarted` (6 fields) | integrationId, integrationType, previousState, newState, reason, restartCount | Lifecycle event. Includes restart count within intensity window. |
| `IntegrationResourceExceeded` (8 fields) | integrationId, integrationType, previousState, newState, reason, resourceType, currentValue, limitValue | CRITICAL lifecycle event. Resource quota breach. |

### Sealed Interface Hierarchy (1 + 5 subtypes)

| Type | Purpose |
|---|---|
| `IntegrationLifecycleEvent` | Sealed root extending DomainEvent. 5 common accessor methods: integrationId(), integrationType(), previousState() (nullable for IntegrationStarted), newState(), reason(). |
| `IntegrationStarted` | `integration_started` — initial startup. previousState() returns null. Carries `@EventType(EventTypes.INTEGRATION_STARTED)` (M2.i). |
| `IntegrationStopped` | `integration_stopped` — shutdown or health threshold breach. Carries `@EventType(EventTypes.INTEGRATION_STOPPED)` (M2.i). |
| `IntegrationHealthChanged` | `integration_health_changed` — health state transition. Adds healthScore field. Carries `@EventType(EventTypes.INTEGRATION_HEALTH_CHANGED)` (M2.i). |
| `IntegrationRestarted` | `integration_restarted` — successful restart after failure. Adds restartCount field. Carries `@EventType(EventTypes.INTEGRATION_RESTARTED)` (M2.i). |
| `IntegrationResourceExceeded` | `integration_resource_exceeded` — CRITICAL resource quota breach. Adds resourceType, currentValue, limitValue fields. Carries `@EventType(EventTypes.INTEGRATION_RESOURCE_EXCEEDED)` (M2.i). |

### Service Interfaces (6)

| Type | Kind | Purpose | Key Methods |
|---|---|---|---|
| `IntegrationFactory` | interface | Adapter construction — one per integration module | `descriptor()` → IntegrationDescriptor (pure, no I/O), `create(IntegrationContext)` → IntegrationAdapter |
| `IntegrationAdapter` | interface extends AutoCloseable | Adapter lifecycle — three phases | `initialize()` (no external I/O, INV-RF-03), `run()` (main processing loop, blocks on I/O), `close()` (resource cleanup, idempotent), `commandHandler()` → CommandHandler (nullable for read-only adapters) |
| `HealthReporter` | interface | Adapter-to-supervisor health signals | `reportHeartbeat()`, `reportKeepalive(Instant)`, `reportError(Throwable)`, `reportHealthTransition(HealthState, String)` |
| `CommandHandler` | @FunctionalInterface | Command dispatch callback | `handle(CommandEnvelope)` throws Exception |
| `SchedulerService` | interface | Timer and periodic tasks | `schedule(Runnable, Duration)`, `scheduleAtFixedRate(Runnable, Duration, Duration)`, `shutdown()`. Lifecycle tied to adapter. |
| `ManagedHttpClient` | interface | Resource-controlled HTTP client | `send(HttpRequest, BodyHandler)` (sync), `sendAsync(HttpRequest, BodyHandler)` (async), `close()`. Connection pool isolation. |

### Exception (1)

| Type | Purpose |
|---|---|
| `PermanentIntegrationException` extends HomeSynapseException | Unrecoverable adapter failure — supervisor transitions to FAILED without retry. Error code: `integration.permanent_failure`. HTTP status: 503. |

## Dependencies

### Phase 2: 5 core modules (all `api` scope)

| Module | Why | Key Types Used |
|---|---|---|
| event-model (`com.homesynapse.event`) | IntegrationLifecycleEvent extends DomainEvent; IntegrationContext holds EventPublisher; PermanentIntegrationException extends HomeSynapseException; CommandEnvelope Javadoc references CausalContext | `DomainEvent`, `EventPublisher`, `HomeSynapseException`, `CausalContext` |
| device-model (`com.homesynapse.device`) | IntegrationContext holds EntityRegistry | `EntityRegistry` |
| state-store (`com.homesynapse.state`) | IntegrationContext holds StateQueryService | `StateQueryService` |
| persistence (`com.homesynapse.persistence`) | IntegrationContext holds TelemetryWriter | `TelemetryWriter` |
| configuration (`com.homesynapse.config`) | IntegrationContext holds ConfigurationAccess | `ConfigurationAccess` |

All are `api(project(...))` in build.gradle.kts because these types appear in integration-api's public API (IntegrationContext record components, IntegrationLifecycleEvent's DomainEvent supertype). Platform-api (`IntegrationId`, `EntityId`, `Ulid`) is transitively available through event-model.

### Gradle (build.gradle.kts)

```kotlin
api(project(":core:event-model"))
api(project(":core:device-model"))
api(project(":core:state-store"))
api(project(":core:persistence"))
api(project(":config:configuration"))
```

## Consumers

### Current consumers:
- **integration-runtime** (`com.homesynapse.integration.runtime`, Block O) — `requires transitive com.homesynapse.integration`. Consumes IntegrationFactory, IntegrationId, HealthState, HealthParameters in IntegrationSupervisor and IntegrationHealthRecord type signatures.

### Planned consumers:
- **integration-zigbee** — The first (MVP) integration adapter. Will `requires com.homesynapse.integration` and implement IntegrationFactory + IntegrationAdapter.
- **lifecycle** — Will call IntegrationSupervisor.start()/stop(), which transitively requires integration-api types (IntegrationFactory parameter).
- **rest-api** (Phase 3) — Will call IntegrationSupervisor.health()/allHealth(), which returns IntegrationHealthRecord containing HealthState.
- **observability** (Phase 3) — Composite health indicator reads IntegrationSupervisor.allHealth().

## Cross-Module Contracts

- **IntegrationLifecycleEvent extends DomainEvent.** Integration lifecycle events flow through the standard event pipeline (EventPublisher → SQLite WAL → Event Bus → subscribers). They carry EventOrigin.SYSTEM, not PHYSICAL.
- **IntegrationContext is integration-scoped.** EntityRegistry and StateQueryService are filtered to return only entities owned by the adapter's integration. The adapter cannot see other integrations' data. This is the runtime enforcement of LTD-17.
- **PermanentIntegrationException extends HomeSynapseException.** Integrates with the structured exception hierarchy from event-model (Block D). Carries errorCode() and suggestedHttpStatus() for REST API ProblemDetailMapper.
- **CommandEnvelope.commandEventId and correlationId enable causal context propagation.** The adapter must use these to construct CausalContext for the resulting command_result event, maintaining the causal chain (INV-ES-06).
- **DECIDE-04: Direct factory construction, no ServiceLoader.** IntegrationFactory is instantiated explicitly by the application module (e.g., `new ZigbeeIntegrationFactory()`). The supervisor receives `List<IntegrationFactory>` at start(). If post-MVP community integrations need dynamic discovery, LTD-17 can be amended.
- **HealthReporter signals flow to the supervisor's health state machine (in integration-runtime).** The adapter calls reportHeartbeat(), reportKeepalive(), reportError() — the supervisor consumes these and updates IntegrationHealthRecord.
- **IntegrationDescriptor.dependsOn() declares startup ordering.** The supervisor (integration-runtime) uses Kahn's algorithm with cycle detection to topologically sort integrations for startup. Shutdown proceeds in reverse order.

## Constraints

| Constraint | Description |
|---|---|
| LTD-01 | IoType.NETWORK → virtual thread; IoType.SERIAL → platform thread. Adapter does not choose its thread. |
| LTD-04 | IntegrationId, EntityId are typed ULID wrappers (via platform-api). |
| LTD-11 | No `synchronized` in adapter code — ReentrantLock only. Virtual thread carrier pinning on Pi's 4 cores. |
| LTD-17 | Adapters use IntegrationContext only. No core-internal imports. Build-time (Gradle) and JPMS enforcement. |
| INV-RF-01 | Integration crash isolation — adapter exceptions are caught by the supervisor. |
| INV-RF-03 | Startup independence — initialize() must not block on external device connectivity. |
| INV-ES-06 | Causal context propagation — CommandEnvelope carries commandEventId and correlationId. |
| INV-HO-04 | Self-explaining errors — lifecycle event reason fields use Register C voice. |
| INV-CE-02 | Zero-config valid — configAccess always provided, even when empty. |

## Key Design Decisions

1. **IntegrationContext is a record, not a builder.** All required fields are enforced at construction via compact constructor null checks. Optional services (schedulerService, telemetryWriter, httpClient) are nullable — null means "not requested." This makes construction explicit and catches wiring errors at supervisor startup.

2. **IntegrationLifecycleEvent is a sealed interface, not an abstract class.** Records cannot extend abstract classes, so a sealed interface + record subtypes is the only way to get both exhaustive pattern matching and immutable value semantics. Each subtype carries its own additional fields (healthScore for HealthChanged, restartCount for Restarted, resource metrics for ResourceExceeded).

3. **PermanentIntegrationException extends HomeSynapseException, not RuntimeException directly.** This integrates with the REST API's ProblemDetailMapper — the exception carries errorCode() and suggestedHttpStatus() for structured HTTP error responses.

4. **HealthReporter has four methods, not a single report(HealthEvent).** Each signal type has distinct semantics: heartbeat is a liveness pulse (no parameters), keepalive carries a protocol-level timestamp, reportError records for the sliding window, and reportHealthTransition is a state suggestion. A single method with a discriminated union would be less self-documenting and harder to implement correctly.

5. **CommandHandler is a @FunctionalInterface.** One method, one responsibility. The adapter may implement it as a lambda or method reference. The throws Exception clause allows transient errors to propagate to the supervisor for retry classification.

## Gotchas

**GOTCHA: `IntegrationContext.schedulerService`, `telemetryWriter`, and `httpClient` are nullable.** These are gated by RequiredService — null unless the adapter declared the service in IntegrationDescriptor.requiredServices(). Do not add null checks that throw; null is the expected value when not requested.

**GOTCHA: `IntegrationAdapter.commandHandler()` may return null.** Null means the adapter is read-only (e.g., a sensor-only adapter). The supervisor must check for null before attempting command dispatch.

**GOTCHA: `IntegrationStarted.previousState()` always returns null.** This is by design — no previous health state exists at initial startup. Unlike all other IntegrationLifecycleEvent subtypes where previousState is non-null.

**GOTCHA: `IntegrationLifecycleEvent` extends `DomainEvent`, which is in event-model.** This creates a compile-time dependency from integration-api to event-model. The dependency is correct — lifecycle events are domain events that flow through the standard event pipeline. Do not try to remove this dependency. It is also the direct reason `DomainEvent` is permanently non-sealed (AMD-33): JEP 409 requires a sealed interface and all its permits to live in the same JPMS module, and these lifecycle records live in `com.homesynapse.integration` rather than `com.homesynapse.event`.

**GOTCHA: `@EventType` annotation lives on the 5 concrete subtypes only — NOT on the sealed `IntegrationLifecycleEvent` interface.** Only concrete records are serialized, so only concrete records are registered. The sealed parent is a dispatch root, not a registerable type. `IntegrationEventTypeAnnotationTest.sealedParent_doesNotHaveAnnotation` pins this.

**GOTCHA: `@EventType` annotation values must use the `integration_` prefix.** This prevents collisions with core event types in `EventTypes` (which are in a flat namespace shared with integration lifecycle events). `IntegrationEventTypeAnnotationTest.annotationValues_doNotCollideWithCoreEvents` pins this.

**GOTCHA: `IntegrationDescriptor.dependsOn()` uses `Set<String>` (integration type strings), not `Set<IntegrationId>`.** Dependencies are declared against software types ("zigbee"), not instance IDs (ULIDs). The supervisor resolves type→ID mapping at startup.

**GOTCHA: `CommandEnvelope.commandEventId` and `correlationId` are `Ulid`, not `EventId`.** This is because the command envelope is constructed by the supervisor from an event envelope's fields, and the adapter doesn't need the typed EventId wrapper for causal context construction.

**GOTCHA: `ManagedHttpClient.send()` throws checked exceptions (IOException, InterruptedException).** These are standard java.net.http exceptions. The supervisor classifies IOException as TRANSIENT and InterruptedException as SHUTDOWN_SIGNAL per Doc 05 §3.7.

**GOTCHA: No `@Nullable` annotations.** HomeSynapse uses Javadoc `{@code null} if...` patterns. No nullability annotation library in libs.versions.toml.

## Test Fixtures and Contract Tests

The `testFixtures` source set (`src/testFixtures/java/com/homesynapse/integration/test/`) provides three stub types that downstream modules use to construct valid `IntegrationContext` instances and exercise adapter / command-handler lifecycles without standing up real wiring.

### testFixtures Type Inventory

| Type | Kind | Package | Purpose |
|---|---|---|---|
| `StubIntegrationContext` | final utility class (private constructor) | `com.homesynapse.integration.test` | Factory producing valid `IntegrationContext` records. The static `defaults()` method returns a fully populated `IntegrationContext` whose required fields are non-null (an `InMemoryEventStore` from event-model testFixtures, a stub `EntityRegistry`, a stub `StateQueryService`, a stub `HealthReporter`, and an `InMemoryConfigAccess` from configuration testFixtures), and whose optional fields (`schedulerService`, `telemetryWriter`, `httpClient`) are `null`. A nested `Builder` enables targeted overrides for tests that need to substitute individual collaborators. Internal helpers (`StubEntityRegistry`, `StubStateQueryService`, `StubHealthReporter`, `StubConfigAccess`, plus `HealthSignal` records `Heartbeat`, `Keepalive`, `ErrorReport`, etc.) are package-private. |
| `TestAdapter` | final class implementing `IntegrationAdapter` | `com.homesynapse.integration.test` | Minimal `IntegrationAdapter` implementation for testing adapter lifecycle. Exposes static factories for common shapes (`noop()`, `echo()`, `failing()`, `failing(String message)`) and a public nested `Builder` for fully configurable start/stop behavior. Used by integration-runtime and supervisor tests to drive lifecycle transitions without a real protocol implementation. |
| `StubCommandHandler` | final class implementing `CommandHandler` | `com.homesynapse.integration.test` | Stub `CommandHandler` for testing command routing and supervisor error classification. Tracks received commands for assertion. Static factories: `accepting()` (always succeeds), `rejecting(Exception)` (always throws the supplied exception), `conditional(...)` (predicate-driven success / failure for permanent vs. transient error tests). |

### Validation Coverage

`StubIntegrationContextTest` (`src/test/java/com/homesynapse/integration/test/`) contains 39 `@Test` methods organized into 4 `@Nested` sections, validating that the fixtures produce structurally correct `IntegrationContext` records, that all required fields are non-null, that optional fields default to `null`, and that the builder correctly applies overrides without corrupting the rest of the context.

### Consumption by Downstream Modules

Downstream modules that depend on these fixtures must declare **both** of the following in their `build.gradle.kts`:

```kotlin
testFixturesImplementation(testFixtures(project(":integration:integration-api")))
testImplementation(testFixtures(project(":integration:integration-api")))
```

Both declarations are required for the same reason described in the event-model MODULE_CONTEXT: the `java-conventions` plugin only adds JUnit / AssertJ to `testImplementation`, so any consuming module that writes contract or stub-based tests in its own `testFixtures` source set must re-declare both lines.

## Phase 3 Notes

- **IntegrationContext construction:** The supervisor (integration-runtime) constructs IntegrationContext per adapter, assembling integration-scoped wrappers around core services (EntityRegistry filter, StateQueryService filter, integration-scoped SchedulerService, ManagedHttpClient with connection pool isolation).
- **HealthReporter implementation:** Lives in integration-runtime. Records heartbeat/keepalive timestamps, feeds errors to sliding windows, evaluates health transition suggestions against the state machine.
- **SchedulerService implementation:** Lives in integration-runtime. Uses ScheduledExecutorService backed by virtual threads. Lifecycle tied to adapter — all tasks cancelled on stop.
- **ManagedHttpClient implementation:** Lives in integration-runtime. Wraps java.net.http.HttpClient with semaphore-based concurrency limiting and token bucket rate limiting.
- **CommandHandler dispatch:** The supervisor subscribes to command_dispatched events on the event bus, filters by integration ownership, constructs CommandEnvelope, and invokes the adapter's handler on the adapter's thread.
- **Event type namespace enforcement:** Phase 3 should validate that adapters only publish permitted event types (state_reported, command_result, availability_changed, device_discovered, presence_signal).
- **`@EventType` annotation — IMPLEMENTED (M2.i, 2026-04-10).** The 5 `IntegrationLifecycleEvent` subtypes in this module now carry `@EventType(EventTypes.INTEGRATION_*)` from `com.homesynapse.event`. The annotation unblocked M2.4's `EventTypeRegistry` (IMPLEMENTED 2026-04-10, persistence module), which discovers these classes via a dedicated integration-api registration call alongside the 22 core event-model records. `EventPayloadCodecTest` in the persistence module now round-trips all 5 integration lifecycle subtypes through `EventPayloadCodec.encode()`/`decode()` (the `IntegrationEvents` nested test class, 5 dedicated tests). This is the only place in the codebase where `core/persistence` depends on `integration/integration-api`, and the dependency is `testImplementation`-scope only — no production ripple. Coverage lives in `IntegrationEventTypeAnnotationTest` (6 methods: all-subtypes-annotated, sealed-parent-unannotated, values-unique, values-match-EventTypes-constants, values-use-integration_-prefix, exactly-5-subtypes). The hardcoded `EXPECTED_SUBTYPES` list in that test is the authoritative set of registrable integration lifecycle events — update it whenever a subtype is added, removed, or renamed. The 5 `INTEGRATION_*` constants live in `EventTypes` in event-model, not in this module.
