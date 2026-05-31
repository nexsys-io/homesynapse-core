plugins {
    id("homesynapse.test-fixtures-conventions")
}

description = "Persistence: SQLite event store, telemetry, checkpoints, migrations"

dependencies {
    api(project(":platform:platform-api"))

    // M3.6d-b: promoted from `implementation` to `api` because PersistenceFactory's
    // public API surfaces event-model (EventPublisher, EventStore, DomainEvent),
    // event-bus (CheckpointStore, SubscriberReadConnectionFactory, PersistentDlqWriter,
    // DeadLetter), and state-store (StateStore, StateCheckpointSource, ViewCheckpointStore).
    // The `api` scope matches the `requires transitive` directives in module-info.java.
    api(project(":core:event-model"))
    api(project(":core:event-bus"))
    api(project(":core:state-store"))

    // M4.0b-4a: CheckpointSerializer (package-private) names com.homesynapse.value
    // .AttributeValue/.StringValue internally; not on persistence's public API →
    // `requires com.homesynapse.value` (non-transitive) ↔ implementation scope.
    implementation(project(":core:value-model"))

    implementation(libs.sqlite.jdbc)
    implementation(libs.slf4j.api)

    // M2.4: Jackson serialization infrastructure.
    // jackson-databind transitively pulls in jackson-core and jackson-annotations.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.blackbird)

    // M2.4: Tests construct EventTypeRegistry with the 5 integration lifecycle
    // event records (IntegrationStarted, etc.). The test source set compiles on
    // the classpath (not the module path), so this does not require a JPMS
    // `requires com.homesynapse.integration` in module-info.java.
    testImplementation(project(":integration:integration-api"))

    // M2.5: SqliteEventStoreTest extends the abstract EventStoreContractTest
    // from core:event-model's test fixtures source set. That fixture also
    // provides TestEventTypes and the @EventType-annotated TestPayload record
    // used by the persistence wiring in SqliteEventStoreTest.setUp().
    testImplementation(testFixtures(project(":core:event-model")))

    // M2.6: SqliteCheckpointStoreTest extends the abstract
    // CheckpointStoreContractTest from core:event-bus's test fixtures source
    // set, which defines the 9-method behavioral contract that all
    // CheckpointStore implementations must satisfy.
    testImplementation(testFixtures(project(":core:event-bus")))

    // M2.7: SqliteViewCheckpointStoreTest extends the abstract
    // ViewCheckpointStoreContractTest from core:state-store's test fixtures
    // source set, which defines the 10-method behavioral contract that all
    // ViewCheckpointStore implementations must satisfy.
    testImplementation(testFixtures(project(":core:state-store")))

    // testFixtures dependencies — JUnit + AssertJ for the WriteCoordinatorContractTest
    // abstract class. The java-conventions plugin only adds these to testImplementation,
    // not testFixturesImplementation, so they must be declared explicitly here.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.assertj.core)

    // M3.4a: PersistenceTestHarness (testFixture) wraps SqlitePersistenceLifecycle
    // and exposes its stores via public-interface return types from event-model
    // (EventPublisher, EventStore, DomainEvent), event-bus (CheckpointStore), and
    // state-store (ViewCheckpointStore). These three modules are `implementation`
    // dependencies of the main source set — and the java-test-fixtures plugin does
    // NOT propagate `implementation` deps to testFixtures (it propagates only the
    // main source set's compiled classes). They must be declared explicitly for
    // the testFixtures compile classpath. Using `testFixturesApi` (not just
    // `testFixturesImplementation`) because the types appear in the fixture's
    // public method signatures — downstream consumers of these testFixtures
    // (e.g. testing:integration-tests) need them visible at compile time.
    // platform-api is already `api`-scoped on the main source set, so HomeId
    // is transitively visible without an explicit declaration here.
    testFixturesApi(project(":core:event-model"))
    testFixturesApi(project(":core:event-bus"))
    testFixturesApi(project(":core:state-store"))
}
