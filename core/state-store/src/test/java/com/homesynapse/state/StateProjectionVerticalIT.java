/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.value.StringValue;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.event.bus.test.InMemoryCheckpointStore;
import com.homesynapse.event.bus.test.InMemoryEventBus;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.test.InMemoryViewCheckpointStore;
import com.homesynapse.test.TestClock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * End-to-end vertical slice exercising {@link StateProjection} against the
 * full event → bus → projection → derived-publish → checkpoint pipeline
 * using in-memory fixtures.
 *
 * <p>The test wires:
 * <ul>
 *   <li>{@link InMemoryEventStore} (event-model testFixtures) — also serves as
 *       the {@code EventPublisher}.</li>
 *   <li>{@link InMemoryEventBus} (event-bus testFixtures) — passive bus with
 *       {@code subscribeWithHandler} callback bridge.</li>
 *   <li>{@link InMemoryStateStore} — concurrent map-backed materialized state.</li>
 *   <li>{@link InMemoryViewCheckpointStore} — view checkpoint storage.</li>
 *   <li>{@link InMemoryProjectionAdvancer} — advancer over the event store.</li>
 *   <li>{@link StateProjection} — under test, wired via
 *       {@link StateProjection#create}.</li>
 *   <li>Spy {@link Subscriber} — records every event the bus delivers.</li>
 * </ul>
 *
 * <p>The {@link DerivationRule} produces one {@code state_changed} draft per
 * incoming {@code state_reported} whose value differs from the prior canonical
 * value. Because the fixture starts with an empty {@link StateStore}, all 10
 * seeded {@code state_reported} events trigger derivation.</p>
 */
class StateProjectionVerticalIT {

    StateProjectionVerticalIT() {
        // Inherits the no-arg constructor contract.
    }

    @Test
    void tenEntitiesDriveFullVerticalPipeline() throws SequenceConflictException {
        // ── Setup ──
        TestClock clock = TestClock.createDefault();
        InMemoryEventStore eventStore = new InMemoryEventStore(clock);
        InMemoryCheckpointStore busCheckpointStore = new InMemoryCheckpointStore();
        InMemoryEventBus bus = new InMemoryEventBus(eventStore, busCheckpointStore);
        InMemoryStateStore stateStore = new InMemoryStateStore();
        InMemoryViewCheckpointStore viewCheckpointStore =
                new InMemoryViewCheckpointStore(clock);
        InMemoryProjectionAdvancer advancer = new InMemoryProjectionAdvancer(eventStore);
        // M4.0b-1: exercise the production rule through the full vertical
        // pipeline (was a local EchoStateRule copy before the public factory).
        DerivationRule rule = DerivationRule.production();

        StateProjection projection = StateProjection.create(
                new ProjectionId("state_projection"),
                1,
                viewCheckpointStore,
                StateCheckpointSource.stub(),
                AtomicCheckpointSink.viewOnly(viewCheckpointStore),
                stateStore,
                rule,
                eventStore,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                DerivedPublishGate.unbounded());
        projection.setMode(SubscriberMode.LIVE);

        // ── Seed 10 distinct entities with one state_reported each ──
        List<EntityId> entities = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            EntityId entityId = new EntityId(UlidFactory.generate(clock));
            entities.add(entityId);
            EventDraft draft = new EventDraft(
                    EventTypes.STATE_REPORTED,
                    1,
                    null,
                    SubjectRef.entity(entityId),
                    EventPriority.DIAGNOSTIC,
                    EventOrigin.PHYSICAL,
                    new StateReportedEvent("color", "v" + i, null, null, null),
                    null,
                    null);
            eventStore.publishRoot(draft);
        }

        // ── Wire spy subscriber and projection-as-subscriber ──
        SpySubscriber spy = new SpySubscriber();
        bus.subscribeWithHandler(
                new SubscriberInfo("spy", SubscriptionFilter.all(), true),
                pos -> deliver(eventStore, pos, spy));
        bus.subscribeWithHandler(
                new SubscriberInfo("projection", SubscriptionFilter.all(), true),
                pos -> deliver(eventStore, pos, projection));

        // ── Drive the bus through the 10 seeded state_reported events ──
        // Each notification feeds an event to BOTH subscribers; the projection
        // publishes a derived state_changed inside its onEvent call which lands
        // at the next available globalPosition in the event store (11..20).
        for (long pos = 1; pos <= 10; pos++) {
            bus.notifyEvent(pos);
        }

        // ── Drive the bus through the 10 derived state_changed events ──
        // These were published by the projection but the bus was not auto-
        // notified. Now we notify so the spy sees them. The projection's
        // self-filter catches them — no second derivation.
        for (long pos = 11; pos <= 20; pos++) {
            bus.notifyEvent(pos);
        }

        // ── Fire onCaughtUp (the bus's REPLAY → LIVE transition signal) ──
        projection.onCaughtUp();

        // ── Assertions ──
        // 1. Spy sees 20 events total (10 original + 10 derived), in globalPosition order.
        assertThat(spy.events())
                .as("spy receives all 20 events")
                .hasSize(20);
        assertThat(spy.events().stream().map(EventEnvelope::globalPosition).toList())
                .as("events delivered in globalPosition order")
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L,
                        11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);

        // 2. The InMemoryStateStore has correct attribute values for all 10 entities.
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            final EntityId entityId = entities.get(idx);
            EntityState s = stateStore.get(entityId)
                    .orElseThrow(() -> new AssertionError(
                            "missing state for entity " + entityId));
            assertThat(s.attributes())
                    .as("entity %d attributes", idx)
                    .containsEntry("color", new StringValue("v" + idx));
        }

        // 3. No third-generation events — eventStore has exactly 20 events,
        //    not 30+. The projection does NOT re-derive from state_changed.
        assertThat(eventStore.latestPosition())
                .as("no third-generation events; latestPosition = 20")
                .isEqualTo(20L);
        long stateChangedCount = spy.events().stream()
                .filter(env -> env.payload() instanceof StateChangedEvent)
                .count();
        assertThat(stateChangedCount)
                .as("exactly 10 derived state_changed events seen by spy")
                .isEqualTo(10L);

        // 4. Projection's cursor advanced to 20.
        assertThat(projection.cursorPosition())
                .as("projection cursor reaches the last delivered position")
                .isEqualTo(20L);

        // 5. onCaughtUp fired exactly once.
        assertThat(projection.caughtUpFired())
                .as("onCaughtUp has fired")
                .isTrue();
    }

    /**
     * Delivers the event at {@code globalPosition} to the given subscriber by
     * reading the envelope from the event store. The bus's callback bridge
     * receives only the position; in production, subscribers pull events
     * themselves from the {@code EventStore}.
     */
    private static void deliver(InMemoryEventStore store, long globalPosition,
                                Subscriber subscriber) {
        EventPage page = store.readFrom(globalPosition - 1, 1);
        if (!page.events().isEmpty()) {
            subscriber.onEvent(page.events().get(0));
        }
    }

    /**
     * Records every event delivered via {@code onEvent} for later inspection.
     */
    private static final class SpySubscriber implements Subscriber {

        private final List<EventEnvelope> events = new ArrayList<>();

        SpySubscriber() {
            // Explicit constructor for -Xlint:all -Werror.
        }

        @Override
        public void onEvent(EventEnvelope event) {
            events.add(event);
        }

        List<EventEnvelope> events() {
            return List.copyOf(events);
        }
    }

}
