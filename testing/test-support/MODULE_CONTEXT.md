# test-support — Cross-cutting test infrastructure — TestClock, SynchronousEventBus, NoRealIoExtension, GivenWhenThen DSL, custom AssertJ assertions

## Purpose

Cross-cutting test infrastructure for all HomeSynapse modules. Provides deterministic time control, synchronous event bus for testing, I/O prevention enforcement, event-sourced assertion DSL, and custom AssertJ assertions for domain types. Consumed exclusively via `testImplementation` dependencies — not a runtime module.

## JPMS Status

**Decision:** `module-info.java` removed — 2026-03-27

**Why:** The `test-support` module is consumed exclusively via Gradle `testImplementation` dependencies, and test source sets run on the classpath, bypassing JPMS entirely. Maintaining a `module-info.java` in a classpath-consumed test library created recurring friction with automatic module dependencies (JUnit 5, AssertJ, ArchUnit) under the project's `-Xlint:all -Werror` compiler policy. This friction already forced the relocation of ArchUnit architectural rules from `test-support` to `homesynapse-app` — a concrete architectural compromise documented in `HomeSynapseArchRules.java`. Gradle's `api()` dependency scope provides equivalent transitive dependency management without JPMS, so the `module-info.java` provided no value that Gradle declarations alone could not achieve.

**Impact on Phase 3 test authors:** Test authors adding `testImplementation(project(":testing:test-support"))` to any module will receive test-support's classes and all its transitive dependencies (JUnit 5, AssertJ, event-model, event-bus, device-model, integration-api) on the test classpath automatically via Gradle's `api()` scope. No `requires` directive is needed or possible in test source sets. Future test-only dependencies (Mockito, WireMock, Testcontainers, ArchUnit, etc.) can be added to `test-support` without JPMS automatic-module friction.

**Precedent:** See `app/homesynapse-app/src/test/java/com/homesynapse/app/HomeSynapseArchRules.java` class-level Javadoc for the documented ArchUnit relocation that motivated this evaluation.

## Package Structure

- **`com.homesynapse.test`** — Core test utilities: `TestClock`, `SynchronousEventBus`, `NoRealIoExtension`, `@RealIo`, `GivenWhenThen`.
- **`com.homesynapse.test.assertions`** — Custom AssertJ assertions: `EventEnvelopeAssert`, `CausalContextAssert`, `SubjectRefAssert`, `HomeSynapseAssertions` (entry point).

## Complete Type Inventory

### Package: `com.homesynapse.test`

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `TestClock` | class | Controllable `java.time.Clock` for deterministic time-dependent tests | Allows advancing, setting, and freezing time. Replaces `Clock.systemUTC()` in test contexts. |
| `SynchronousEventBus` | class | Single-threaded event bus ensuring deterministic subscriber ordering | Dispatches events synchronously on the calling thread. Eliminates concurrency non-determinism in tests. |
| `NoRealIoExtension` | class (JUnit 5 extension) | Prevents accidental real network/filesystem I/O in unit tests | Registers as a JUnit 5 extension. Fails tests that attempt real I/O unless opted out with `@RealIo`. |
| `@RealIo` | annotation | Opts a test class out of `NoRealIoExtension` enforcement | Applied at class level. Used for integration tests that legitimately need real I/O. |
| `GivenWhenThen` | class | Event-sourced assertion DSL for command/event testing | Pattern: `given(events).when(command).then(expectedEvents)`. Simplifies event-sourced test setup and assertion. |

### Package: `com.homesynapse.test.assertions`

| Type | Kind | Purpose | Key Details |
|---|---|---|---|
| `EventEnvelopeAssert` | class extends `AbstractAssert` | Custom AssertJ assertions for `EventEnvelope` | Provides fluent assertions: `hasEventType()`, `hasSubjectRef()`, `hasPriority()`, etc. |
| `CausalContextAssert` | class extends `AbstractAssert` | Custom AssertJ assertions for `CausalContext` | Provides fluent assertions: `hasCorrelationId()`, `hasCausationId()`, `isRootCause()`, etc. |
| `SubjectRefAssert` | class extends `AbstractAssert` | Custom AssertJ assertions for `SubjectRef` | Provides fluent assertions: `hasSubjectType()`, `hasId()`, etc. |
| `HomeSynapseAssertions` | class | AssertJ entry point for all custom assertions | Static `assertThat()` factory methods for domain types. Entry point for the custom assertion DSL. |

**Total: 9 public types + 2 package-info.java files = 11 Java files.**

## Dependencies

### Gradle Dependencies

```kotlin
dependencies {
    api(project(":core:event-model"))
    api(project(":core:event-bus"))
    api(project(":core:device-model"))
    api(project(":integration:integration-api"))
    api(libs.junit.jupiter)
    api(libs.assertj.core)
}
```

All dependencies are `api` scope so that test consumers receive them transitively via `testImplementation(project(":testing:test-support"))`.

## Consumers

All modules with test source sets are potential consumers. Any module adding `testImplementation(project(":testing:test-support"))` receives the full test infrastructure.

## Constraints

- **No JPMS module-info.java.** Consumed on the classpath only. See JPMS Status above.
- **No production runtime usage.** This module must never appear in `implementation` or `api` scope of a non-test module.

## Gotchas

**GOTCHA: ArchUnit rules live in homesynapse-app test, NOT test-support.** The 7 ArchUnit architecture rules (dependency direction, Clock injection, etc.) were relocated to `app/homesynapse-app/src/test/java/com/homesynapse/app/HomeSynapseArchRules.java` due to JPMS automatic module conflict between ArchUnit and the project's module-info.java policy. Do not attempt to move them back to test-support.

**GOTCHA: `EventStoreContractTest` (27 methods) lives in event-model testFixtures, NOT test-support.** The contract test suite for EventStore implementations is in `core/event-model/src/testFixtures/`. `InMemoryEventStore` must pass all 27 contract test methods. This is separate from test-support because it tests a specific module contract, not cross-cutting infrastructure.

**GOTCHA: No module-info.java means no JPMS enforcement in tests.** Test source sets run entirely on the classpath. Package visibility and module boundaries are not enforced at test time. ArchUnit rules in homesynapse-app partially compensate for this.

## Phase 3 Notes

- **Per-module testFixtures are placeholder-only.** The following modules have `testFixtures` source sets with only `package-info.java` — implementations are the first Phase 3 targets:
  - event-model: `InMemoryEventStore` (first implementation target), `TestEventFactory`, `TestCausalContext`
  - device-model: `TestDeviceFactory`, `TestEntityFactory`, `TestCapabilityFactory`
  - state-store: `InMemoryStateStore`, `TestProjectionFixture`
  - persistence: `InMemoryCheckpointStore`, `InMemoryTelemetryStore`
  - integration-api: `StubIntegrationContext`, `TestAdapter`, `StubCommandHandler`
  - configuration: `InMemoryConfigStore`, `TestConfigFactory`
- **Additional test dependencies may be added:** Mockito, WireMock, Testcontainers, etc. can be added as `api` scope dependencies without JPMS friction.
