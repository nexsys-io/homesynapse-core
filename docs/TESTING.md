# Test Strategy

## Test Categories

1. **Unit tests** — isolated, no I/O, fast. Use `test-support` fixtures.
2. **Integration tests** — verify module boundaries with real (in-memory) stores.
3. **Performance tests** — regression benchmarks for critical paths (event store
   throughput, projection rebuild time).
4. **Failure tests** — verify graceful degradation (disk full, database corruption,
   adapter crash).
5. **Architecture tests** — ArchUnit rules enforcing dependency direction and
   package isolation.

## Running Tests

```bash
./gradlew check          # all tests + Spotless + architecture checks
./gradlew test           # unit tests only
./gradlew :core:event-model:test  # single module
```

## Test Infrastructure

The `testing:test-support` module provides shared fixtures:

- `InMemoryEventStore` — non-persistent EventStore for unit tests
- `SynchronousEventBus` — synchronous dispatch for deterministic tests
- `TestFixtures` — builders for Event, Device, Entity instances
- `TestIntegrationContext` — mock IntegrationContext for adapter tests
- `TestClock` — controllable clock for time-dependent tests
- `NoRealIoExtension` — JUnit extension that fails on real network/filesystem I/O

## Conventions

- Every public interface gets a corresponding test class
- Test class naming: `{ClassName}Test.java`
- Integration test naming: `{ClassName}IntegrationTest.java`
- Use AssertJ for assertions (`assertThat(...).isEqualTo(...)`)
- Use `-XX:+EnableDynamicAgentLoading` JVM flag (configured in convention plugin)
