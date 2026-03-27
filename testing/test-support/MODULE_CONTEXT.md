# test-support — Cross-cutting test infrastructure — TestClock, SynchronousEventBus, NoRealIoExtension, GivenWhenThen DSL, custom AssertJ assertions

## JPMS Status

**Decision:** `module-info.java` removed — 2026-03-27

**Why:** The `test-support` module is consumed exclusively via Gradle `testImplementation` dependencies, and test source sets run on the classpath, bypassing JPMS entirely. Maintaining a `module-info.java` in a classpath-consumed test library created recurring friction with automatic module dependencies (JUnit 5, AssertJ, ArchUnit) under the project's `-Xlint:all -Werror` compiler policy. This friction already forced the relocation of ArchUnit architectural rules from `test-support` to `homesynapse-app` — a concrete architectural compromise documented in `HomeSynapseArchRules.java`. Gradle's `api()` dependency scope provides equivalent transitive dependency management without JPMS, so the `module-info.java` provided no value that Gradle declarations alone could not achieve.

**Impact on Phase 3 test authors:** Test authors adding `testImplementation(project(":testing:test-support"))` to any module will receive test-support's classes and all its transitive dependencies (JUnit 5, AssertJ, event-model, event-bus, device-model, integration-api) on the test classpath automatically via Gradle's `api()` scope. No `requires` directive is needed or possible in test source sets. Future test-only dependencies (Mockito, WireMock, Testcontainers, ArchUnit, etc.) can be added to `test-support` without JPMS automatic-module friction.

**Precedent:** See `app/homesynapse-app/src/test/java/com/homesynapse/app/HomeSynapseArchRules.java` class-level Javadoc for the documented ArchUnit relocation that motivated this evaluation.

## Design Doc Reference
<!-- Link to the governing design document in homesynapse-core-docs -->

## Dependencies
<!-- Which modules this module depends on and why -->

## Consumers
<!-- Which modules depend on this module -->

## Constraints
<!-- Locked decisions, invariants, and rules that apply to this module -->

## Gotchas
<!-- Non-obvious implementation details, known quirks, things to watch for -->
