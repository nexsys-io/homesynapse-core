# Block I — Integration API

**Module:** `integration/integration-api`
**Package:** `com.homesynapse.integration`
**Design Doc:** Doc 05 — Integration Runtime (§3.8, §4, §8) — Locked
**Phase:** P2 Interface Specification — no implementation code, no tests
**Compile Gate:** `./gradlew :integration:integration-api:compileJava`

---

## Strategic Context

The Integration API defines the **adapter contract** — the set of interfaces and types that every hardware integration implements against. This is the API boundary between protocol-specific code (Zigbee coordinators, MQTT brokers, cloud APIs) and the HomeSynapse event-sourced core. Getting this right is critical because: (1) every future integration module depends on these types, (2) the Zigbee adapter (Block P) will be the first real consumer, and (3) the contract stability invariant (INV-CS-04) means changes after Phase 3 are expensive.

The integration-api module contains **contracts only** — interfaces and types that adapters import. The **implementation** (IntegrationSupervisor, thread management, health state machine, restart logic) lives in integration-runtime (Block O, Sprint 4). This split follows Doc 05 P1: "The API boundary is the investment; the isolation mechanism is swappable."

## Scope

**IN:** All adapter-facing interfaces, records, enums, sealed interfaces, and exceptions from Doc 05 §8.1 and §8.2 that adapters depend on. Full Javadoc with contracts, nullability, thread-safety, and `@see` cross-references. `module-info.java` with correct exports and requires.

**OUT:** Implementation code. Tests. IntegrationSupervisor (internal — adapters don't import it; Block O). IntegrationHealthRecord (supervisor-internal tracking; Block O). Health state machine logic (§3.4). Thread allocation logic (§3.2). Restart intensity tracking (§3.4). Sliding window implementations (SlidingWindow is an internal type used by IntegrationHealthRecord). Metrics and structured logging implementation (§11). Health indicator implementation (§11.3). Configuration parsing (§9). Event production enforcement logic (§3.11).

---

## Locked Decisions

1. **IntegrationId already exists in platform-api.** `com.homesynapse.platform.identity.IntegrationId` is one of the 8 typed ULID wrappers created in Block A. Do NOT create a duplicate. Import via `requires transitive com.homesynapse.platform;`.

2. **IntegrationDescriptor is a record, not an interface.** Doc 05 §4.1 specifies it as a data carrier. All fields are declared at discovery time and are immutable. The supervisor assigns the `IntegrationId` separately — it is NOT a field on IntegrationDescriptor (the descriptor declares the integration *type*; the supervisor assigns the instance *identity*).

3. **HealthParameters is a record with a static `defaults()` factory.** Doc 05 §4.2 specifies default values for all fields. The `defaults()` method returns a HealthParameters instance with all defaults — this is the common case for adapters that don't need custom thresholds.

4. **HealthState is a simple enum, not a state machine.** The enum defines the four states: `HEALTHY`, `DEGRADED`, `SUSPENDED`, `FAILED`. The state machine transitions and guards (§3.4) are supervisor-internal logic (Block O). The enum is in integration-api because adapters reference it in `HealthReporter.reportHealthTransition()`.

5. **CommandEnvelope is a record carrying dispatched command data.** Per Doc 05 §8.2: entity reference, command type, parameters, causal context. It does NOT carry the full `EventEnvelope` — it is a purpose-built record for the adapter's `CommandHandler.handle()` method.

6. **IntegrationAdapter extends AutoCloseable.** The `close()` method from AutoCloseable serves as the resource cleanup contract (Doc 05 §8.4). This enables try-with-resources in the supervisor.

7. **PermanentIntegrationException extends HomeSynapseException.** It signals an unrecoverable failure with a user-readable message. The supervisor uses this to distinguish permanent failures (transition to FAILED) from transient exceptions (retry with backoff). Per the exception hierarchy established in event-model (Block D).

8. **IntegrationLifecycleEvent is a sealed interface.** Doc 05 §4.4 defines five lifecycle event types. These are the payload types for `integration_started`, `integration_stopped`, `integration_health_changed`, `integration_restarted`, `integration_resource_exceeded`. Each is a record implementing IntegrationLifecycleEvent.

9. **IoType and DataPath are simple enums.** IoType: `SERIAL`, `NETWORK`. DataPath: `DOMAIN`, `TELEMETRY`. RequiredService: `HTTP_CLIENT`, `SCHEDULER`, `TELEMETRY_WRITER`. These drive supervisor behavior but are declared by the adapter in its IntegrationDescriptor.

10. **Module requires:** `com.homesynapse.event` (for EventPublisher, DomainEvent, CausalContext, EventEnvelope references in Javadoc), `com.homesynapse.device` (for EntityRegistry, CommandValidator, AttributeValue, HardwareIdentifier references), and `com.homesynapse.platform` (for IntegrationId, EntityId, DeviceId, Ulid). Use `requires transitive` for platform since downstream consumers of integration-api types will need IntegrationId. Use `requires transitive` for event since adapters need EventPublisher.

11. **Collections in records must be unmodifiable.** IntegrationDescriptor's `requiredServices()`, `dataPaths()`, `dependsOn()` fields all return unmodifiable sets. Same pattern as device-model and state-store records.

---

## Deliverables — Dependency Order

Execute in this order. Each group compiles after the previous group exists.

### Group 1: Enums (no inter-dependencies)

| File | Type | Notes |
|------|------|-------|
| `IoType.java` | enum | `SERIAL`, `NETWORK`. §3.2, §4.1. Javadoc: SERIAL means dedicated platform thread (JNI pins carrier), NETWORK means virtual thread. The supervisor uses this to determine thread allocation. |
| `DataPath.java` | enum | `DOMAIN`, `TELEMETRY`. §4.1. Javadoc: DOMAIN means the adapter publishes domain events (state_reported, command_result, etc.) via EventPublisher. TELEMETRY means the adapter also produces high-frequency numeric samples routed to TelemetryWriter. |
| `RequiredService.java` | enum | `HTTP_CLIENT`, `SCHEDULER`, `TELEMETRY_WRITER`. §4.1. Javadoc: declares which optional services the adapter requires in its IntegrationContext. The supervisor only provisions services the adapter declares — undeclared services are not available. |
| `HealthState.java` | enum | `HEALTHY`, `DEGRADED`, `SUSPENDED`, `FAILED`. §3.4, §4.3. Javadoc: documents each state's meaning per the four-state health model. HEALTHY: all metrics nominal. DEGRADED: health score below threshold, increased monitoring. SUSPENDED: adapter stopped, probing for recovery. FAILED: unrecoverable, manual restart required. |

### Group 2: Data Records (depends on enums, imports from platform-api)

| File | Type | Notes |
|------|------|-------|
| `HealthParameters.java` | record | Fields per Doc 05 §4.2: `heartbeatTimeout` (Duration), `healthWindowSize` (int), `maxDegradedDuration` (Duration), `maxSuspendedDuration` (Duration), `maxSuspensionCycles` (int), `maxRestarts` (int), `restartWindow` (Duration), `probeInitialDelay` (Duration), `probeMaxDelay` (Duration), `probeCount` (int), `probeSuccessThreshold` (int). Static `defaults()` factory method returns all-defaults instance. Javadoc documents each field's purpose and the default values. |
| `IntegrationDescriptor.java` | record | Fields per Doc 05 §4.1: `integrationType` (String — e.g., "zigbee"), `displayName` (String), `ioType` (IoType), `requiredServices` (Set\<RequiredService\>, unmodifiable), `dataPaths` (Set\<DataPath\>, unmodifiable), `healthParameters` (HealthParameters), `dependsOn` (Set\<String\>, unmodifiable — integration types this adapter depends on, per AMD-14), `schemaVersion` (int). Javadoc: `integrationType` is the software identity (e.g., "zigbee"), NOT the instance identity — the supervisor assigns IntegrationId (ULID) at first load. Documents the Zigbee adapter example from §4.1. |
| `CommandEnvelope.java` | record | Fields per Doc 05 §8.2 and §8.6: `entityRef` (EntityId — the target entity), `commandName` (String — capability-defined command name, e.g., "set_on_off"), `parameters` (Map\<String, Object\> — command parameters, unmodifiable), `commandEventId` (Ulid — the event ID of the command_dispatched event), `correlationId` (Ulid — for causal context propagation), `integrationId` (IntegrationId — which integration should handle this). Javadoc: the adapter receives this from the supervisor via CommandHandler.handle(), translates to protocol-specific operations, and publishes a command_result event. |

### Group 3: Exception (depends on event-model HomeSynapseException)

| File | Type | Notes |
|------|------|-------|
| `PermanentIntegrationException.java` | class (extends HomeSynapseException) | §3.7, §8.2. Constructor takes `String message` and optional `Throwable cause`. Javadoc: signals an unrecoverable adapter failure — the supervisor transitions to FAILED and does not retry. Contrast with transient exceptions (any other RuntimeException), which trigger retry with backoff. The message must be user-readable (Register C voice — direct, neutral) because it appears in the dashboard and logs. |

### Group 4: Sealed Interface — IntegrationLifecycleEvent (depends on enums, platform-api)

| File | Type | Notes |
|------|------|-------|
| `IntegrationLifecycleEvent.java` | sealed interface | §4.4, §8.2. Root of the integration lifecycle event payload hierarchy. Five permitted subtypes, all records. Every subtype carries `integrationId` (IntegrationId), `integrationType` (String), `previousState` (HealthState, nullable — null for integration_started), `newState` (HealthState), and `reason` (String — human-readable). |
| `IntegrationStarted.java` | record implements IntegrationLifecycleEvent | Payload for `integration_started` event. Fields: `integrationId`, `integrationType`, `newState` (always HEALTHY), `reason`. `previousState` is null. |
| `IntegrationStopped.java` | record implements IntegrationLifecycleEvent | Payload for `integration_stopped` event. Fields: `integrationId`, `integrationType`, `previousState`, `newState` (always FAILED or contextual), `reason` (e.g., "shutdown requested", "health threshold exceeded"). |
| `IntegrationHealthChanged.java` | record implements IntegrationLifecycleEvent | Payload for `integration_health_changed` event. Fields: `integrationId`, `integrationType`, `previousState`, `newState`, `reason`, `healthScore` (double — the current weighted health score at time of transition). |
| `IntegrationRestarted.java` | record implements IntegrationLifecycleEvent | Payload for `integration_restarted` event. Fields: `integrationId`, `integrationType`, `previousState` (the state before restart), `newState` (HEALTHY after successful restart), `reason`, `restartCount` (int — how many restarts within the current intensity window). |
| `IntegrationResourceExceeded.java` | record implements IntegrationLifecycleEvent | Payload for `integration_resource_exceeded` event. CRITICAL priority. Fields: `integrationId`, `integrationType`, `previousState`, `newState`, `reason`, `resourceType` (String — e.g., "memory", "cpu", "connections"), `currentValue` (String), `limitValue` (String). |

### Group 5: Adapter-Facing Interfaces (depends on records, enums, exception)

| File | Type | Notes |
|------|------|-------|
| `IntegrationFactory.java` | interface | §8.1, §8.3. Two methods: `IntegrationDescriptor descriptor()` — returns the static descriptor, must be a pure method with no side effects, called once during ServiceLoader discovery. `IntegrationAdapter create(IntegrationContext context)` — creates an adapter instance, may throw PermanentIntegrationException. Javadoc: factories are discovered via ServiceLoader (LTD-17), one factory per integration type. |
| `IntegrationAdapter.java` | interface extends AutoCloseable | §8.1, §8.4. Three methods: `void initialize()` — startup work independent of device connectivity (INV-RF-03), must complete within configured timeout, must not block on network/serial. `void run()` — main loop, blocks on I/O, returns normally when signaled to stop. `void close()` — release resources, idempotent, from AutoCloseable. Javadoc: `run()` is invoked on the adapter's allocated thread (platform for SERIAL, virtual for NETWORK). |
| `HealthReporter.java` | interface | §8.1, §8.5. Four methods: `void reportHeartbeat()` — update heartbeat timestamp, call on every loop iteration. `void reportKeepalive(Instant lastSuccess)` — protocol-level keepalive timestamp. `void reportError(Throwable error)` — register error in sliding window. `void reportHealthTransition(HealthState suggestedState, String reason)` — adapter suggests a health transition, supervisor may accept or override. Javadoc: thread-safe, may be called from any thread within the adapter's thread group. |
| `CommandHandler.java` | interface | §8.1, §8.6. Single method: `void handle(CommandEnvelope command)` — invoked by the supervisor when a command_dispatched event targets this integration's device. Javadoc: invoked on the adapter's thread. The adapter translates to protocol-specific operations and publishes a command_result event via EventPublisher. If the method throws, the exception is classified per §3.7 (transient → retry, PermanentIntegrationException → FAILED). |
| `SchedulerService.java` | interface | §3.8, §8.1. Methods: `ScheduledFuture<?> schedule(Runnable task, Duration delay)` — one-shot delayed execution. `ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period)` — periodic execution. `void shutdown()` — cancel all scheduled tasks. Javadoc: integration-scoped, executes callbacks on the integration's virtual thread group. Replaces ad-hoc Timer/ScheduledExecutorService usage. Only available if `RequiredService.SCHEDULER` declared. |
| `ManagedHttpClient.java` | interface | §3.8, §3.9, §8.1. Wraps `java.net.http.HttpClient` with concurrency limits and rate limiting. Methods: `<T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException` — send with concurrency/rate enforcement. `<T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler)` — async variant. `void close()` — release connection pool. Javadoc: integration-scoped, lifecycle tied to adapter. Each integration receives its own instance with isolated connection pool. Only available if `RequiredService.HTTP_CLIENT` declared. |

### Group 6: IntegrationContext (depends on all interfaces)

| File | Type | Notes |
|------|------|-------|
| `IntegrationContext.java` | record | §3.8, §8.1. The composed API surface injected into each adapter at construction. Fields: `integrationId` (IntegrationId — the instance identity assigned by the supervisor), `integrationType` (String — from the descriptor), `eventPublisher` (EventPublisher — write-only, adapter may only produce permitted event types per Doc 01 §3.1), `entityRegistry` (EntityRegistry — read-only, integration-scoped, returns only entities owned by this integration), `stateQueryService` (StateQueryService — read-only, integration-scoped), `healthReporter` (HealthReporter), `commandHandler` (CommandHandler — nullable, set by the adapter during initialize(), the supervisor invokes it when commands arrive), `schedulerService` (SchedulerService — nullable, only if RequiredService.SCHEDULER declared), `telemetryWriter` (Object — nullable, placeholder for TelemetryWriter from persistence module which doesn't exist yet; type will be refined in Block J), `httpClient` (ManagedHttpClient — nullable, only if RequiredService.HTTP_CLIENT declared). Javadoc: this is the complete API surface available to the adapter (P4 — no god object). No other entry point into the core exists. |

**Note on `telemetryWriter` field:** The Persistence Layer module (Doc 04) defines `TelemetryWriter` but is not yet specified (Block J). The `IntegrationContext` must reference it. Options: (a) use `Object` as a placeholder and cast in Phase 3, (b) forward-declare a minimal `TelemetryWriter` interface in integration-api, (c) use a generic type parameter. **PM decision: use `@Nullable Object` as a placeholder.** The type will be refined when Block J (persistence) is complete. This avoids creating a forward reference that might not match the actual interface. The Javadoc should document this as a temporary placeholder.

**Note on `commandHandler` field:** IntegrationContext is a record, but `commandHandler` represents a callback the adapter registers during `initialize()`. Since records are immutable, the `commandHandler` field is set at construction time by the supervisor as null, and the adapter provides its CommandHandler implementation via a different mechanism. **PM decision: remove `commandHandler` from IntegrationContext fields.** Instead, `IntegrationAdapter` gets an additional method: `CommandHandler commandHandler()` — the supervisor calls this after `initialize()` to obtain the adapter's command handler. This is cleaner than mutable state on a record.

### Group 7: Module Info

| File | Notes |
|------|-------|
| `module-info.java` | `exports com.homesynapse.integration;`. `requires transitive com.homesynapse.platform;` (for IntegrationId, EntityId, Ulid). `requires transitive com.homesynapse.event;` (for EventPublisher, DomainEvent, CausalContext, HomeSynapseException — adapters need these). `requires com.homesynapse.device;` (for EntityRegistry, AttributeValue — referenced in Javadoc and IntegrationContext). `requires java.net.http;` (for ManagedHttpClient's HttpRequest/HttpResponse types). |

---

## File Placement

All files go in: `integration/integration-api/src/main/java/com/homesynapse/integration/`
Module info: `integration/integration-api/src/main/java/module-info.java` (already exists — update it)

Delete the existing `package-info.java` file at `integration/integration-api/src/main/java/com/homesynapse/integration/package-info.java` — it's a scaffold placeholder that will be replaced by real types.

---

## Cross-Module Type Dependencies

The integration-api module imports types from three existing modules:

**From `com.homesynapse.platform.identity` (platform-api):**
- `IntegrationId` — adapter instance identity
- `EntityId` — entity references in CommandEnvelope, IntegrationContext
- `DeviceId` — referenced in Javadoc for discovery events
- `Ulid` — command event ID and correlation ID in CommandEnvelope

**From `com.homesynapse.event` (event-model):**
- `EventPublisher` — field type in IntegrationContext, adapters publish events through it
- `HomeSynapseException` — parent class for PermanentIntegrationException
- `DomainEvent`, `CausalContext`, `EventEnvelope` — referenced in Javadoc for behavioral contracts

**From `com.homesynapse.device` (device-model):**
- `EntityRegistry` — field type in IntegrationContext (integration-scoped)
- Referenced in Javadoc: `HardwareIdentifier`, `AttributeValue`, `CommandValidator`

**From `com.homesynapse.state` (state-store):**
- `StateQueryService` — field type in IntegrationContext (integration-scoped)

**Not yet available (deferred references):**
- `TelemetryWriter` from persistence module (Block J) — use `@Nullable Object` placeholder
- `ConfigurationAccess` from configuration module (Block K) — not included in IntegrationContext for now; will be added when the configuration module is specified

---

## Module Dependency Update

The existing `build.gradle.kts` has:
```kotlin
dependencies {
    api(project(":core:event-model"))
    api(project(":core:device-model"))
}
```

**Update to:**
```kotlin
dependencies {
    api(project(":core:event-model"))
    api(project(":core:device-model"))
    api(project(":core:state-store"))
}
```

State-store is needed because `StateQueryService` is a field type in `IntegrationContext`.

---

## Javadoc Standards

Per Sprint 1, Block G, and Block H lessons:
1. Every `@param` documents nullability
2. Every type has `@see` cross-references to related types
3. Thread-safety explicitly stated on interfaces (HealthReporter: thread-safe; IntegrationAdapter: single-threaded — run() is called on one thread)
4. Class-level Javadoc explains the "why" — what role this type plays in HomeSynapse
5. Reference Doc 05 sections in class-level Javadoc: `@see` or inline `{@code Doc 05 §X.Y}`
6. Collections documented as unmodifiable in their contracts
7. IntegrationDescriptor Javadoc should document the integrationType vs IntegrationId distinction (software identity vs instance identity, §4.1)
8. IntegrationAdapter Javadoc should document the three lifecycle phases: initialize (no blocking on connectivity), run (main loop), close (resource cleanup, idempotent)
9. IntegrationContext Javadoc should document the scoping model: EntityRegistry and StateQueryService are integration-scoped (only return entities owned by this integration). EventPublisher is write-only with event type restrictions.
10. CommandEnvelope Javadoc should document the flow: command_dispatched event → supervisor extracts → CommandEnvelope → adapter's CommandHandler.handle() → adapter publishes command_result event
11. HealthReporter Javadoc should document the difference between heartbeat (liveness), keepalive (protocol-level connectivity), and health transition (self-assessed degradation)

---

## Key Design Details for Javadoc Accuracy

These details from Doc 05 MUST be reflected accurately in Javadoc. The Coder should verify each against the design doc after writing.

1. **IntegrationDescriptor.integrationType vs IntegrationId.** The descriptor declares the software type ("zigbee"). The supervisor assigns the instance identity (IntegrationId, a ULID) when the integration is first loaded. The ULID is stable across restarts. Do NOT confuse these two identities (Doc 05 §4.1).

2. **IntegrationAdapter.initialize() must not block on connectivity.** This is INV-RF-03 (startup independence). The adapter must register its identity, declare capabilities, and set up data structures even when its external device is unreachable. Connection to the device is handled by the adapter's internal reconnection logic during `run()` (Doc 05 §3.3, P2).

3. **IntegrationAdapter.run() thread type depends on IoType.** SERIAL adapters run on a platform thread (JNI pins the carrier). NETWORK adapters run on a virtual thread. The adapter does NOT choose — the supervisor allocates based on `IntegrationDescriptor.ioType()` (Doc 05 §3.2).

4. **HealthReporter.reportHealthTransition() is a suggestion.** The adapter suggests a state, but the supervisor may accept or override based on its own metrics. This allows adapters to signal semantic errors that external monitoring can't detect (Doc 05 §8.5).

5. **CommandHandler.handle() is invoked on the adapter's thread.** For network adapters: the virtual thread. For serial adapters: the virtual thread event processor (not the platform thread reader). The adapter translates the command to protocol-specific operations and publishes a `command_result` event (Doc 05 §8.6).

6. **ManagedHttpClient is optional.** Only provisioned if the adapter declares `RequiredService.HTTP_CLIENT`. Each integration gets its own instance with an isolated connection pool and lifecycle tied to the adapter (Doc 05 §3.9).

7. **SchedulerService is optional.** Only provisioned if the adapter declares `RequiredService.SCHEDULER`. Callbacks execute on the integration's virtual thread group (Doc 05 §3.8).

8. **IntegrationContext scoping.** EntityRegistry and StateQueryService filter by `integration_id` at the query level. The adapter never receives a reference to the full registry or state store. Build-time and runtime enforcement prevents access to other integrations' data (Doc 05 §3.8, LTD-17).

9. **PermanentIntegrationException message is user-facing.** The message appears in the dashboard and structured logs. Use Register C voice: direct, neutral, no self-reference, no apology (Doc 05 §3.7).

10. **IntegrationLifecycleEvent subtypes carry EventOrigin.SYSTEM.** All lifecycle events are system-initiated, not integration-initiated. The `reason` field is human-readable (Doc 05 §4.4, INV-HO-04).

---

## Compile Gate

```bash
./gradlew :integration:integration-api:compileJava
```

Must pass with `-Xlint:all -Werror`. Run full project gate after:

```bash
./gradlew compileJava
```

All 19 modules must still compile (no regressions from module-info changes).

---

## Estimated Size

~20 files (4 enums + 5 records + 1 exception + 6 interfaces + 1 sealed interface + 5 sealed subtypes - some combined; roughly 18-22 files including module-info), ~800–1200 lines. This is a medium-sized block. The primary complexity is getting the IntegrationContext composition right and ensuring Javadoc accurately captures the scoping model, lifecycle contracts, and the adapter vs. supervisor responsibility boundary.

Expect 2–3 hours. The type count is higher than state-store but each type is relatively simple — most are small records or interfaces with 1–4 methods.

---

## Notes

- `IntegrationId` is NOT created in this block — it already exists in platform-api (Block A). Import it.
- `IntegrationHealthRecord` is NOT in this block — it's supervisor-internal (Block O).
- `IntegrationSupervisor` is NOT in this block — it's the runtime implementation (Block O).
- `SlidingWindow` is NOT in this block — it's an implementation detail of IntegrationHealthRecord (Block O).
- `ConfigurationAccess` is NOT in this block — it depends on the configuration module (Block K). It will be added to IntegrationContext when Block K is complete.
- `TelemetryWriter` is NOT in this block — it depends on the persistence module (Block J). IntegrationContext uses `@Nullable Object` as a placeholder.
- The existing `build.gradle.kts` needs one addition: `api(project(":core:state-store"))` for StateQueryService.
- The existing `module-info.java` needs complete replacement with proper exports and requires.
- The existing `package-info.java` scaffold should be deleted.
- IntegrationAdapter.commandHandler() is a getter, not a lifecycle method — the adapter returns its CommandHandler instance so the supervisor can invoke it when commands arrive.
