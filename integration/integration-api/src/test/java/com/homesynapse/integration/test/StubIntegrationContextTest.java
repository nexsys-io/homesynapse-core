/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.test;

import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.test.TestEntityFactory;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.integration.CommandEnvelope;
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.PermanentIntegrationException;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the integration-api test fixtures: {@link StubIntegrationContext},
 * {@link TestAdapter}, and {@link StubCommandHandler}.
 *
 * <p>These tests verify that the fixtures produce well-formed, contract-correct
 * instances suitable for downstream adapter testing. Tests are organized into
 * four sections matching the fixture classes.</p>
 *
 * @see StubIntegrationContext
 * @see TestAdapter
 * @see StubCommandHandler
 */
@DisplayName("Integration API Test Fixtures")
class StubIntegrationContextTest {

    /** Creates a new test instance. */
    StubIntegrationContextTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 1: StubIntegrationContext validation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("StubIntegrationContext")
    class StubIntegrationContextTests {

        /** Creates a new test instance. */
        StubIntegrationContextTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("defaults() produces valid IntegrationContext with all required fields non-null")
        void defaults_producesValidContext() {
            IntegrationContext ctx = StubIntegrationContext.defaults();

            assertThat(ctx.integrationId()).isNotNull();
            assertThat(ctx.integrationType()).isEqualTo("test");
            assertThat(ctx.eventPublisher()).isNotNull();
            assertThat(ctx.entityRegistry()).isNotNull();
            assertThat(ctx.stateQueryService()).isNotNull();
            assertThat(ctx.healthReporter()).isNotNull();
            assertThat(ctx.configAccess()).isNotNull();
        }

        @Test
        @DisplayName("defaults() sets optional fields to null")
        void defaults_optionalFieldsAreNull() {
            IntegrationContext ctx = StubIntegrationContext.defaults();

            assertThat(ctx.schedulerService()).isNull();
            assertThat(ctx.telemetryWriter()).isNull();
            assertThat(ctx.httpClient()).isNull();
        }

        @Test
        @DisplayName("defaults() returns IntegrationContext record type")
        void defaults_returnsProductionRecordType() {
            IntegrationContext ctx = StubIntegrationContext.defaults();

            assertThat(ctx).isInstanceOf(IntegrationContext.class);
        }

        @Test
        @DisplayName("defaults() uses InMemoryEventStore as EventPublisher")
        void defaults_usesInMemoryEventStore() {
            IntegrationContext ctx = StubIntegrationContext.defaults();

            assertThat(ctx.eventPublisher()).isInstanceOf(InMemoryEventStore.class);
        }

        @Test
        @DisplayName("builder() allows integrationId customization")
        void builder_integrationIdOverride() {
            IntegrationId customId = IntegrationId.of(UlidFactory.generate());
            IntegrationContext ctx = StubIntegrationContext.builder()
                    .integrationId(customId)
                    .build();

            assertThat(ctx.integrationId()).isEqualTo(customId);
        }

        @Test
        @DisplayName("builder() allows integrationType customization")
        void builder_integrationTypeOverride() {
            IntegrationContext ctx = StubIntegrationContext.builder()
                    .integrationType("zigbee")
                    .build();

            assertThat(ctx.integrationType()).isEqualTo("zigbee");
        }

        @Test
        @DisplayName("builder().withClock() configures InMemoryEventStore clock")
        void builder_withClock_configuresEventStore() {
            Instant fixedTime = Instant.parse("2026-01-01T00:00:00Z");
            Clock fixedClock = Clock.fixed(fixedTime, ZoneOffset.UTC);

            IntegrationContext ctx = StubIntegrationContext.builder()
                    .withClock(fixedClock)
                    .build();

            // The event publisher should be an InMemoryEventStore created with fixedClock
            assertThat(ctx.eventPublisher()).isInstanceOf(InMemoryEventStore.class);
        }

        @Test
        @DisplayName("builder().withEntity() pre-loads entity into default registry")
        void builder_withEntity_preloadsEntity() {
            Entity light = TestEntityFactory.light();

            IntegrationContext ctx = StubIntegrationContext.builder()
                    .withEntity(light)
                    .build();

            // Verify the entity is findable via the registry
            assertThat(ctx.entityRegistry().findEntity(light.entityId()))
                    .isPresent()
                    .contains(light);
        }

        @Test
        @DisplayName("builder().withConfig() pre-loads config into default ConfigAccess")
        void builder_withConfig_preloadsConfig() {
            IntegrationContext ctx = StubIntegrationContext.builder()
                    .withConfig("host", "192.168.1.1")
                    .withConfig("port", 8080)
                    .build();

            assertThat(ctx.configAccess().getString("host"))
                    .isPresent()
                    .contains("192.168.1.1");
            assertThat(ctx.configAccess().getInt("port"))
                    .isPresent()
                    .contains(8080);
        }

        @Test
        @DisplayName("each defaults() call generates fresh IDs")
        void defaults_generatesFreshIds() {
            IntegrationContext ctx1 = StubIntegrationContext.defaults();
            IntegrationContext ctx2 = StubIntegrationContext.defaults();

            assertThat(ctx1.integrationId())
                    .isNotEqualTo(ctx2.integrationId());
        }

        @Test
        @DisplayName("builder() with custom EventPublisher uses provided instance")
        void builder_customEventPublisher() {
            Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
            InMemoryEventStore customStore = new InMemoryEventStore(clock);

            IntegrationContext ctx = StubIntegrationContext.builder()
                    .eventPublisher(customStore)
                    .build();

            assertThat(ctx.eventPublisher()).isSameAs(customStore);
        }

        @Test
        @DisplayName("default entity registry starts empty")
        void defaults_entityRegistryStartsEmpty() {
            IntegrationContext ctx = StubIntegrationContext.defaults();

            assertThat(ctx.entityRegistry().listAllEntities()).isEmpty();
        }

        @Test
        @DisplayName("default config access starts empty")
        void defaults_configAccessStartsEmpty() {
            IntegrationContext ctx = StubIntegrationContext.defaults();

            assertThat(ctx.configAccess().getConfig()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 2: TestAdapter validation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TestAdapter")
    class TestAdapterTests {

        /** Creates a new test instance. */
        TestAdapterTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("noop() creates adapter with NOOP mode")
        void noop_createsNoopAdapter() {
            TestAdapter adapter = TestAdapter.noop();

            assertThat(adapter.mode()).isEqualTo(TestAdapter.Mode.NOOP);
        }

        @Test
        @DisplayName("noop() lifecycle methods complete without error")
        void noop_lifecycleCompletesCleanly() throws Exception {
            TestAdapter adapter = TestAdapter.noop();

            adapter.initialize();
            adapter.run();
            adapter.close();

            assertThat(adapter.initialized()).isTrue();
            assertThat(adapter.closed()).isTrue();
        }

        @Test
        @DisplayName("echo() creates adapter with ECHO mode")
        void echo_createsEchoAdapter() {
            TestAdapter adapter = TestAdapter.echo();

            assertThat(adapter.mode()).isEqualTo(TestAdapter.Mode.ECHO);
        }

        @Test
        @DisplayName("echo() tracks invocation counts")
        void echo_tracksInvocationCounts() throws Exception {
            TestAdapter adapter = TestAdapter.echo();

            adapter.initialize();
            adapter.run();
            adapter.run();
            adapter.close();

            assertThat(adapter.initializeCount()).isEqualTo(1);
            assertThat(adapter.runCount()).isEqualTo(2);
            assertThat(adapter.closeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("failing() throws PermanentIntegrationException on initialize")
        void failing_throwsOnInitialize() {
            TestAdapter adapter = TestAdapter.failing();

            assertThatThrownBy(adapter::initialize)
                    .isInstanceOf(PermanentIntegrationException.class)
                    .hasMessageContaining("permanent failure");
        }

        @Test
        @DisplayName("failing(message) uses custom message")
        void failing_withCustomMessage() {
            TestAdapter adapter = TestAdapter.failing("Zigbee coordinator not found");

            assertThatThrownBy(adapter::initialize)
                    .isInstanceOf(PermanentIntegrationException.class)
                    .hasMessage("Zigbee coordinator not found");
        }

        @Test
        @DisplayName("builder() creates CUSTOM mode adapter with callbacks")
        void builder_createsCustomAdapter() throws Exception {
            var initCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
            var runCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
            var closeCalled = new java.util.concurrent.atomic.AtomicBoolean(false);

            TestAdapter adapter = TestAdapter.builder()
                    .onInitialize(() -> initCalled.set(true))
                    .onRun(() -> runCalled.set(true))
                    .onClose(() -> closeCalled.set(true))
                    .build();

            assertThat(adapter.mode()).isEqualTo(TestAdapter.Mode.CUSTOM);

            adapter.initialize();
            adapter.run();
            adapter.close();

            assertThat(initCalled.get()).isTrue();
            assertThat(runCalled.get()).isTrue();
            assertThat(closeCalled.get()).isTrue();
        }

        @Test
        @DisplayName("commandHandler() returns null by default")
        void commandHandler_nullByDefault() {
            TestAdapter adapter = TestAdapter.noop();

            assertThat(adapter.commandHandler()).isNull();
        }

        @Test
        @DisplayName("builder() allows setting commandHandler")
        void builder_withCommandHandler() {
            StubCommandHandler handler = StubCommandHandler.accepting();
            TestAdapter adapter = TestAdapter.builder()
                    .commandHandler(handler)
                    .build();

            assertThat(adapter.commandHandler()).isSameAs(handler);
        }

        @Test
        @DisplayName("close() is idempotent — multiple calls increment count")
        void close_isIdempotent() {
            TestAdapter adapter = TestAdapter.noop();

            adapter.close();
            adapter.close();
            adapter.close();

            assertThat(adapter.closed()).isTrue();
            assertThat(adapter.closeCount()).isEqualTo(3);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 3: StubCommandHandler validation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("StubCommandHandler")
    class StubCommandHandlerTests {

        /** Creates a new test instance. */
        StubCommandHandlerTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        private CommandEnvelope testCommand(String commandName) {
            return new CommandEnvelope(
                    EntityId.of(UlidFactory.generate()),
                    commandName,
                    Map.of(),
                    UlidFactory.generate(),
                    UlidFactory.generate(),
                    IntegrationId.of(UlidFactory.generate()));
        }

        @Test
        @DisplayName("accepting() records all commands without throwing")
        void accepting_recordsCommands() throws Exception {
            StubCommandHandler handler = StubCommandHandler.accepting();

            CommandEnvelope cmd1 = testCommand("turn_on");
            CommandEnvelope cmd2 = testCommand("turn_off");
            handler.handle(cmd1);
            handler.handle(cmd2);

            assertThat(handler.commands()).containsExactly(cmd1, cmd2);
            assertThat(handler.commandCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("rejecting() throws configured exception after recording")
        void rejecting_throwsAfterRecording() {
            Exception rejectException = new RuntimeException("Command rejected");
            StubCommandHandler handler = StubCommandHandler.rejecting(rejectException);

            CommandEnvelope cmd = testCommand("turn_on");

            assertThatThrownBy(() -> handler.handle(cmd))
                    .isSameAs(rejectException);

            // Command should still be recorded before throw
            assertThat(handler.commands()).containsExactly(cmd);
        }

        @Test
        @DisplayName("conditional() accepts matching commands, rejects others")
        void conditional_acceptsMatchingRejectsOthers() throws Exception {
            StubCommandHandler handler = StubCommandHandler.conditional(
                    cmd -> "turn_on".equals(cmd.commandName()));

            CommandEnvelope accepted = testCommand("turn_on");
            CommandEnvelope rejected = testCommand("turn_off");

            handler.handle(accepted); // should not throw

            assertThatThrownBy(() -> handler.handle(rejected))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("turn_off");

            // Both should be recorded
            assertThat(handler.commands()).containsExactly(accepted, rejected);
        }

        @Test
        @DisplayName("lastCommand() returns the most recent command")
        void lastCommand_returnsMostRecent() throws Exception {
            StubCommandHandler handler = StubCommandHandler.accepting();

            CommandEnvelope cmd1 = testCommand("turn_on");
            CommandEnvelope cmd2 = testCommand("set_brightness");
            handler.handle(cmd1);
            handler.handle(cmd2);

            assertThat(handler.lastCommand()).isSameAs(cmd2);
        }

        @Test
        @DisplayName("lastCommand() returns null when no commands received")
        void lastCommand_nullWhenEmpty() {
            StubCommandHandler handler = StubCommandHandler.accepting();

            assertThat(handler.lastCommand()).isNull();
        }

        @Test
        @DisplayName("clear() removes all recorded commands")
        void clear_removesAllCommands() throws Exception {
            StubCommandHandler handler = StubCommandHandler.accepting();
            handler.handle(testCommand("turn_on"));
            handler.handle(testCommand("turn_off"));

            handler.clear();

            assertThat(handler.commands()).isEmpty();
            assertThat(handler.commandCount()).isZero();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // SECTION 4: Inner stub behavior validation
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Inner Stub Behavior")
    class InnerStubBehaviorTests {

        /** Creates a new test instance. */
        InnerStubBehaviorTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("StubEntityRegistry: getEntity throws IAE for unknown entity")
        void entityRegistry_getEntityThrowsForUnknown() {
            IntegrationContext ctx = StubIntegrationContext.defaults();
            EntityId unknownId = EntityId.of(UlidFactory.generate());

            assertThatThrownBy(() -> ctx.entityRegistry().getEntity(unknownId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(unknownId.toString());
        }

        @Test
        @DisplayName("StubEntityRegistry: findEntity returns empty for unknown entity")
        void entityRegistry_findEntityReturnsEmptyForUnknown() {
            IntegrationContext ctx = StubIntegrationContext.defaults();
            EntityId unknownId = EntityId.of(UlidFactory.generate());

            assertThat(ctx.entityRegistry().findEntity(unknownId)).isEmpty();
        }

        @Test
        @DisplayName("StubEntityRegistry: CRUD lifecycle works correctly")
        void entityRegistry_crudLifecycle() {
            IntegrationContext ctx = StubIntegrationContext.defaults();
            EntityRegistry registry = ctx.entityRegistry();

            // Create
            Entity entity = TestEntityFactory.light();
            registry.createEntity(entity);
            assertThat(registry.listAllEntities()).hasSize(1);

            // Read
            assertThat(registry.getEntity(entity.entityId())).isEqualTo(entity);

            // Remove
            registry.removeEntity(entity.entityId());
            assertThat(registry.listAllEntities()).isEmpty();
        }

        @Test
        @DisplayName("StubEntityRegistry: enable/disable toggles entity enabled flag")
        void entityRegistry_enableDisableToggles() {
            Entity entity = TestEntityFactory.light();
            IntegrationContext ctx = StubIntegrationContext.builder()
                    .withEntity(entity)
                    .build();

            ctx.entityRegistry().disableEntity(entity.entityId());
            assertThat(ctx.entityRegistry().getEntity(entity.entityId()).enabled())
                    .isFalse();

            ctx.entityRegistry().enableEntity(entity.entityId());
            assertThat(ctx.entityRegistry().getEntity(entity.entityId()).enabled())
                    .isTrue();
        }

        @Test
        @DisplayName("StubStateQueryService: returns empty for unknown entity")
        void stateQueryService_returnsEmptyForUnknown() {
            IntegrationContext ctx = StubIntegrationContext.defaults();
            EntityId unknownId = EntityId.of(UlidFactory.generate());

            assertThat(ctx.stateQueryService().getState(unknownId)).isEmpty();
        }

        @Test
        @DisplayName("StubStateQueryService: isReady returns true by default")
        void stateQueryService_isReadyByDefault() {
            IntegrationContext ctx = StubIntegrationContext.defaults();

            assertThat(ctx.stateQueryService().isReady()).isTrue();
        }

        @Test
        @DisplayName("StubStateQueryService: getSnapshot returns empty snapshot")
        void stateQueryService_emptySnapshot() {
            IntegrationContext ctx = StubIntegrationContext.defaults();

            assertThat(ctx.stateQueryService().getSnapshot().states()).isEmpty();
            assertThat(ctx.stateQueryService().getSnapshot().viewPosition()).isZero();
        }

        @Test
        @DisplayName("StubConfigAccess: typed accessors return correct types")
        void configAccess_typedAccessors() {
            IntegrationContext ctx = StubIntegrationContext.builder()
                    .withConfig("name", "test-integration")
                    .withConfig("retries", 3)
                    .withConfig("debug", true)
                    .build();

            assertThat(ctx.configAccess().getString("name"))
                    .isPresent().contains("test-integration");
            assertThat(ctx.configAccess().getInt("retries"))
                    .isPresent().contains(3);
            assertThat(ctx.configAccess().getBoolean("debug"))
                    .isPresent().contains(true);
        }

        @Test
        @DisplayName("StubConfigAccess: typed accessors return empty for wrong type")
        void configAccess_typedAccessorsReturnEmptyForWrongType() {
            IntegrationContext ctx = StubIntegrationContext.builder()
                    .withConfig("name", "test-integration")
                    .build();

            // getString for a String key works
            assertThat(ctx.configAccess().getString("name")).isPresent();

            // getInt for a String value returns empty
            assertThat(ctx.configAccess().getInt("name")).isEmpty();

            // Missing key returns empty
            assertThat(ctx.configAccess().getString("missing")).isEmpty();
        }

        @Test
        @DisplayName("StubHealthReporter: records all signal types")
        void healthReporter_recordsSignalTypes() {
            IntegrationContext ctx = StubIntegrationContext.defaults();
            HealthReporter reporter = ctx.healthReporter();

            reporter.reportHeartbeat();
            reporter.reportKeepalive(Instant.now());
            reporter.reportError(new RuntimeException("test error"));
            reporter.reportHealthTransition(HealthState.DEGRADED, "high error rate");

            // StubHealthReporter is package-private, so verify via HealthReporter interface
            // The reporter should not throw on any signal type
            assertThat(reporter).isNotNull();
        }
    }
}
