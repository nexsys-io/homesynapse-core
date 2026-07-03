/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandDispatchedEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.integration.CommandEnvelope;
import com.homesynapse.integration.CommandHandler;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.integration.test.StubCommandHandler;
import com.homesynapse.integration.test.StubIntegrationContext;
import com.homesynapse.integration.test.TestAdapter;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.test.TestClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * T9–T16 — the {@link CommandRoutingSubscriber} unit slice: the DP-2 join,
 * the DP-4 cache bounds + evict-on-join, the DP-5 invocation/failure boundary,
 * and the INV-ES-09 LIVE-only guard.
 *
 * <p>Unit-tested by direct {@code setMode}/{@code onEvent} invocation (W3 —
 * {@code SynchronousEventBus} cannot register runtime subscribers; only the
 * lifecycle E2E gates exercise the real {@code InProcessEventBus} path).
 * Envelopes are minted through {@link InMemoryEventStore} so the causal
 * context the router joins on is the real published shape.</p>
 */
@DisplayName("CommandRoutingSubscriber -- join-cache + LIVE-only routing (M9.1)")
final class CommandRoutingSubscriberTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String INTEGRATION_TYPE = "fake";
    private static final String COMMAND = "turn_on";

    private final EntityId targetEntity = EntityId.parse("01J" + "B".repeat(23));

    private TestClock clock;
    private InMemoryEventStore store;
    private StandardIntegrationSupervisor supervisor;
    private IntegrationId fakeIntegrationId;
    private StubCommandHandler handler;
    private List<String> handlerThreadNames;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    CommandRoutingSubscriberTest() {
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.at(T0);
        store = new InMemoryEventStore(clock);
        IntegrationContext stubContext = StubIntegrationContext.defaults();
        supervisor = new StandardIntegrationSupervisor(
                store,
                stubContext.entityRegistry(),
                stubContext.stateQueryService(),
                type -> stubContext.configAccess(),
                clock,
                Duration.ofMillis(200));
        handler = StubCommandHandler.accepting();
        handlerThreadNames = new CopyOnWriteArrayList<>();
        fakeIntegrationId = IntegrationIds.deriveStable(INTEGRATION_TYPE);
    }

    @AfterEach
    void tearDown() {
        supervisor.stop();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("T9: command_issued is cached in ANY mode; a command_dispatched delivered in "
            + "REPLAY never reaches handle() (INV-ES-09), and the replay-cached issued joins a "
            + "LIVE dispatched across the flip")
    void replayDispatched_neverInvokesHandle_butReplayCachedIssuedJoinsLive() throws Exception {
        CommandRoutingSubscriber router = startFakeAndRouter(recordingHandler());
        router.setMode(SubscriberMode.REPLAY);

        EventEnvelope issued = publishIssued("{}");
        EventEnvelope dispatchedInReplay = publishDispatched(issued, fakeIntegrationId);
        router.onEvent(issued);
        router.onEvent(dispatchedInReplay);

        sleepBriefly();
        assertThat(handler.commandCount()).isZero();
        assertThat(eventsOfType(EventTypes.COMMAND_RESULT)).isEmpty();

        // The DP-4 cross-flip join: the issued was cached DURING replay; a LIVE
        // dispatched (a second delivery of the same causation) joins it.
        router.setMode(SubscriberMode.LIVE);
        EventEnvelope dispatchedLive = publishDispatched(issued, fakeIntegrationId);
        router.onEvent(dispatchedLive);

        awaitCondition(() -> handler.commandCount() == 1);
        assertThat(handler.lastCommand().commandEventId()).isEqualTo(issued.eventId().value());
    }

    @Test
    @DisplayName("T10: a LIVE dispatched joins and invokes handle() ON the per-adapter command "
            + "executor thread with the envelope built exactly per DP-2")
    void liveDispatched_invokesHandleOnPerAdapterExecutorWithExactEnvelope() throws Exception {
        CommandRoutingSubscriber router = startFakeAndRouter(recordingHandler());
        router.setMode(SubscriberMode.LIVE);

        EventEnvelope issued = publishIssued("{\"level\":42}");
        EventEnvelope dispatched = publishDispatched(issued, fakeIntegrationId);
        router.onEvent(issued);
        router.onEvent(dispatched);

        awaitCondition(() -> handler.commandCount() == 1);
        CommandEnvelope command = handler.lastCommand();
        assertThat(command.entityRef()).isEqualTo(targetEntity);
        assertThat(command.commandName()).isEqualTo(COMMAND);
        assertThat(command.parameters()).isEqualTo(Map.of("raw", "{\"level\":42}"));
        assertThat(command.commandEventId()).isEqualTo(issued.eventId().value());
        assertThat(command.correlationId()).isEqualTo(issued.causalContext().correlationId());
        assertThat(command.integrationId()).isEqualTo(fakeIntegrationId);
        // DP-5/DP-11: the handler runs on the single-threaded per-adapter command
        // executor (virtual thread named integration-cmd-<type>), never the bus thread.
        assertThat(handlerThreadNames).hasSize(1);
        assertThat(handlerThreadNames.get(0)).startsWith("integration-cmd-" + INTEGRATION_TYPE);
    }

    @Test
    @DisplayName("T11: a join miss (unknown causation) skips — no handle(), no published events")
    void joinMiss_skipsWithoutDispatchOrResult() throws Exception {
        CommandRoutingSubscriber router = startFakeAndRouter(recordingHandler());
        router.setMode(SubscriberMode.LIVE);

        EventEnvelope issued = publishIssued("{}");
        // Dispatched chained to a causation the cache has never seen.
        EventEnvelope orphanDispatched = store.publish(
                dispatchedDraft(fakeIntegrationId),
                CausalContext.chain(issued.causalContext().correlationId(),
                        Ulid.parse("01J" + "Z".repeat(23))));
        long positionBeforeRoute = store.latestPosition();

        router.onEvent(orphanDispatched);

        sleepBriefly();
        assertThat(handler.commandCount()).isZero();
        assertThat(store.latestPosition()).isEqualTo(positionBeforeRoute);
        assertThat(eventsOfType(EventTypes.COMMAND_RESULT)).isEmpty();
    }

    @Test
    @DisplayName("T12: an unknown/not-running integrationId publishes command_result "
            + "'integration_unavailable' at CRITICAL, causation-chained from the dispatched "
            + "envelope")
    void unknownIntegration_publishesIntegrationUnavailable() throws Exception {
        CommandRoutingSubscriber router = startFakeAndRouter(recordingHandler());
        router.setMode(SubscriberMode.LIVE);

        IntegrationId ghost = IntegrationIds.deriveStable("ghost");
        EventEnvelope issued = publishIssued("{}");
        EventEnvelope dispatched = publishDispatched(issued, ghost);
        router.onEvent(issued);
        router.onEvent(dispatched);

        List<EventEnvelope> results = eventsOfType(EventTypes.COMMAND_RESULT);
        assertThat(results).hasSize(1);
        EventEnvelope result = results.get(0);
        assertThat(result.priority()).isEqualTo(EventPriority.CRITICAL);
        assertThat(result.origin()).isEqualTo(EventOrigin.SYSTEM);
        assertThat(result.causalContext().causationId()).isEqualTo(dispatched.eventId().value());
        assertThat(result.causalContext().correlationId())
                .isEqualTo(dispatched.causalContext().correlationId());
        CommandResultEvent payload = (CommandResultEvent) result.payload();
        assertThat(payload.outcome()).isEqualTo("integration_unavailable");
        assertThat(payload.commandType()).isEqualTo(COMMAND);
        assertThat(handler.commandCount()).isZero();
    }

    @Test
    @DisplayName("T13: null commandHandler -> 'unsupported'; handler throwing -> "
            + "'handler_error' + error window incremented; a NORMAL handle() return publishes "
            + "NOTHING (the adapter owns the eventual command_result)")
    void dp5FailureBoundary() throws Exception {
        // (a) read-only adapter (null commandHandler) -> "unsupported".
        CommandRoutingSubscriber readOnlyRouter = startRouterFor("read-only", null);
        readOnlyRouter.setMode(SubscriberMode.LIVE);
        IntegrationId readOnlyId = IntegrationIds.deriveStable("read-only");
        EventEnvelope issuedA = publishIssued("{}");
        readOnlyRouter.onEvent(issuedA);
        readOnlyRouter.onEvent(publishDispatched(issuedA, readOnlyId));
        List<EventEnvelope> unsupported = eventsOfType(EventTypes.COMMAND_RESULT);
        assertThat(unsupported).hasSize(1);
        assertThat(((CommandResultEvent) unsupported.get(0).payload()).outcome())
                .isEqualTo("unsupported");
        assertThat(unsupported.get(0).priority()).isEqualTo(EventPriority.CRITICAL);
        supervisor.stop();

        // (b) handler throws -> "handler_error" + the error window increments.
        setUp();
        CommandHandler throwing = StubCommandHandler.rejecting(
                new IllegalStateException("device offline"));
        CommandRoutingSubscriber router = startFakeAndRouter(throwing);
        router.setMode(SubscriberMode.LIVE);
        EventEnvelope issuedB = publishIssued("{}");
        router.onEvent(issuedB);
        router.onEvent(publishDispatched(issuedB, fakeIntegrationId));
        awaitCondition(() -> !eventsOfType(EventTypes.COMMAND_RESULT).isEmpty());
        List<EventEnvelope> handlerError = eventsOfType(EventTypes.COMMAND_RESULT);
        assertThat(handlerError).hasSize(1);
        assertThat(((CommandResultEvent) handlerError.get(0).payload()).outcome())
                .isEqualTo("handler_error");
        assertThat(supervisor.health(fakeIntegrationId).orElseThrow().errorWindow().count())
                .isEqualTo(1);
        supervisor.stop();

        // (c) normal return -> the router publishes NOTHING (DP-5: never fabricate success).
        setUp();
        CommandRoutingSubscriber okRouter = startFakeAndRouter(recordingHandler());
        okRouter.setMode(SubscriberMode.LIVE);
        EventEnvelope issuedC = publishIssued("{}");
        okRouter.onEvent(issuedC);
        okRouter.onEvent(publishDispatched(issuedC, fakeIntegrationId));
        awaitCondition(() -> handler.commandCount() == 1);
        sleepBriefly();
        assertThat(eventsOfType(EventTypes.COMMAND_RESULT)).isEmpty();
    }

    @Test
    @DisplayName("T14: the same dispatched delivered twice (bus at-least-once) invokes "
            + "handle() exactly ONCE — the join evicts on success, the redelivery is a miss")
    void duplicateDispatched_invokesHandleExactlyOnce() throws Exception {
        CommandRoutingSubscriber router = startFakeAndRouter(recordingHandler());
        router.setMode(SubscriberMode.LIVE);

        EventEnvelope issued = publishIssued("{}");
        EventEnvelope dispatched = publishDispatched(issued, fakeIntegrationId);
        router.onEvent(issued);
        router.onEvent(dispatched);
        router.onEvent(dispatched);        // at-least-once redelivery

        awaitCondition(() -> handler.commandCount() == 1);
        sleepBriefly();
        assertThat(handler.commandCount()).isEqualTo(1);
        assertThat(eventsOfType(EventTypes.COMMAND_RESULT)).isEmpty();
    }

    @Test
    @DisplayName("T15: the join cache is bounded at 1024 — the 1025th issued evicts the "
            + "oldest (its dispatched then join-misses; a younger one still joins)")
    void cacheBound_dropsOldestAtCapacity() throws Exception {
        CommandRoutingSubscriber router = startFakeAndRouter(recordingHandler());
        router.setMode(SubscriberMode.LIVE);

        EventEnvelope oldest = publishIssued("{}");
        router.onEvent(oldest);
        EventEnvelope second = publishIssued("{}");
        router.onEvent(second);
        for (int i = 0; i < 1023; i++) {
            router.onEvent(publishIssued("{}"));
        }
        // Cache capacity 1024: 1025 puts total -> the oldest was dropped.
        router.onEvent(publishDispatched(oldest, fakeIntegrationId));
        sleepBriefly();
        assertThat(handler.commandCount()).isZero();

        // The second-oldest survived the eviction and still joins.
        router.onEvent(publishDispatched(second, fakeIntegrationId));
        awaitCondition(() -> handler.commandCount() == 1);
        assertThat(handler.lastCommand().commandEventId()).isEqualTo(second.eventId().value());
    }

    @Test
    @DisplayName("T16: a parameter-decode failure never blocks routing — handle() is still "
            + "invoked with Map.of()")
    void decodeFailure_invokesHandleWithEmptyParameters() throws Exception {
        CommandRoutingSubscriber router = routerWithDecoder(startFake(recordingHandler()),
                raw -> {
                    throw new IllegalStateException("bad json");
                });
        router.setMode(SubscriberMode.LIVE);

        EventEnvelope issued = publishIssued("{\"level\":42}");
        router.onEvent(issued);
        router.onEvent(publishDispatched(issued, fakeIntegrationId));

        awaitCondition(() -> handler.commandCount() == 1);
        assertThat(handler.lastCommand().parameters()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Harness
    // ════════════════════════════════════════════════════════════════════════

    /** A handler that records into the shared {@link StubCommandHandler} AND the invoking thread name. */
    private CommandHandler recordingHandler() {
        return command -> {
            handlerThreadNames.add(Thread.currentThread().getName());
            handler.handle(command);
        };
    }

    /** Registers the {@code fake} integration hosting {@code commandHandler} and builds a LIVE-ready router. */
    private CommandRoutingSubscriber startFakeAndRouter(CommandHandler commandHandler)
            throws Exception {
        return routerWithDecoder(startFake(commandHandler), testDecoder());
    }

    private StandardIntegrationSupervisor startFake(CommandHandler commandHandler)
            throws Exception {
        return startIntegration(INTEGRATION_TYPE, commandHandler);
    }

    private CommandRoutingSubscriber startRouterFor(String integrationType,
                                                    CommandHandler commandHandler)
            throws Exception {
        return routerWithDecoder(startIntegration(integrationType, commandHandler), testDecoder());
    }

    private StandardIntegrationSupervisor startIntegration(String integrationType,
                                                           CommandHandler commandHandler)
            throws Exception {
        TestAdapter.Builder adapter = TestAdapter.builder()
                .onRun(CommandRoutingSubscriberTest::blockUntilInterrupted);
        if (commandHandler != null) {
            adapter.commandHandler(commandHandler);
        }
        TestAdapter built = adapter.build();
        StandardIntegrationSupervisorTest.RecordingFactory factory =
                StandardIntegrationSupervisorTest.RecordingFactory.custom(
                        integrationType, () -> built);
        supervisor.start(List.of(factory)).get(5, TimeUnit.SECONDS);
        return supervisor;
    }

    private CommandRoutingSubscriber routerWithDecoder(
            StandardIntegrationSupervisor target, Function<String, Map<String, Object>> decoder) {
        return new CommandRoutingSubscriber(target, decoder, store, clock);
    }

    /** Mirrors the composition-root decoder contract: empty/"{}"/null -> Map.of(). */
    private static Function<String, Map<String, Object>> testDecoder() {
        return raw -> (raw == null || raw.isBlank() || raw.equals("{}"))
                ? Map.of()
                : Map.of("raw", raw);
    }

    private EventEnvelope publishIssued(String parameters) throws Exception {
        return store.publishRoot(new EventDraft(
                EventTypes.COMMAND_ISSUED, 1, null, SubjectRef.entity(targetEntity),
                EventPriority.NORMAL, EventOrigin.AUTOMATION,
                new CommandIssuedEvent(targetEntity.value(), COMMAND, parameters, 30_000,
                        CommandIdempotency.NOT_IDEMPOTENT),
                null, null));
    }

    private EventEnvelope publishDispatched(EventEnvelope issued, IntegrationId integrationId)
            throws Exception {
        return store.publish(
                dispatchedDraft(integrationId),
                CausalContext.chain(issued.causalContext().correlationId(),
                        issued.eventId().value()));
    }

    private EventDraft dispatchedDraft(IntegrationId integrationId) {
        return new EventDraft(
                EventTypes.COMMAND_DISPATCHED, 1, null, SubjectRef.entity(targetEntity),
                EventPriority.DIAGNOSTIC, EventOrigin.AUTOMATION,
                new CommandDispatchedEvent(targetEntity.value(), integrationId.value(), "{}"),
                null, null);
    }

    private List<EventEnvelope> eventsOfType(String eventType) {
        return store.readByType(eventType, 0L, 5000).events();
    }

    private static void awaitCondition(BooleanSupplier condition) {
        for (int poll = 0; poll < 250; poll++) {       // ~5s at 20ms
            if (condition.getAsBoolean()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("condition not reached within ~5s");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(20L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting the router", ex);
        }
    }

    private static void blockUntilInterrupted() {
        CountDownLatch never = new CountDownLatch(1);
        try {
            never.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
