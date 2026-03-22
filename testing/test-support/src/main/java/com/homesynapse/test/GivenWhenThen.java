/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

import com.homesynapse.event.EventEnvelope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Event-sourced assertion DSL for testing subscribers and projections.
 *
 * <p>Adapted from Axon's AggregateTestFixture pattern for HomeSynapse's
 * subscriber/projection model. Instead of testing aggregates, this tests
 * the subscriber processing pipeline: given some events already in the
 * store, when a subscriber processes them, then assert on the resulting
 * state or emitted events.
 *
 * <h3>Subscriber/Projection Fixture</h3>
 * <pre>{@code
 * GivenWhenThen.given(stateReportedEvent, stateChangedEvent)
 *     .whenProcessedBy(stateProjection::onEvent)
 *     .thenAssert(() -> {
 *         assertThat(projection.getState(entityId)).isNotNull();
 *     });
 * }</pre>
 *
 * <h3>Event Production Fixture</h3>
 * <pre>{@code
 * GivenWhenThen.given(stateReportedEvent)
 *     .whenProducing(collector -> {
 *         collector.add(derivedEvent);
 *     })
 *     .thenExpect(produced -> {
 *         assertThat(produced).hasSize(1);
 *         assertThat(produced.get(0)).hasEventType("state_changed");
 *     });
 * }</pre>
 *
 * <p>This DSL is intentionally simple for MVP. It does not manage an
 * InMemoryEventStore internally — tests compose it with their own store
 * and subscribers. The DSL provides the fluent API structure; the test
 * provides the glue.
 *
 * @see EventEnvelope
 */
public final class GivenWhenThen {

    private final List<EventEnvelope> givenEvents;

    private GivenWhenThen(List<EventEnvelope> givenEvents) {
        this.givenEvents = List.copyOf(givenEvents);
    }

    /**
     * Starts the DSL with a set of pre-existing events (the "given" phase).
     * These represent events already in the event store before the action
     * under test.
     *
     * @param events the pre-existing events
     * @return a GivenWhenThen instance for chaining
     */
    public static GivenWhenThen given(EventEnvelope... events) {
        return new GivenWhenThen(Arrays.asList(events));
    }

    /**
     * Starts the DSL with a set of pre-existing events from a list.
     *
     * @param events the pre-existing events
     * @return a GivenWhenThen instance for chaining
     */
    public static GivenWhenThen given(List<EventEnvelope> events) {
        return new GivenWhenThen(events);
    }

    /**
     * Starts the DSL with no pre-existing events.
     *
     * @return a GivenWhenThen instance for chaining
     */
    public static GivenWhenThen givenNoEvents() {
        return new GivenWhenThen(List.of());
    }

    /**
     * Returns the given events for use in test setup (e.g., populating
     * an InMemoryEventStore before the "when" phase).
     *
     * @return unmodifiable list of given events
     */
    public List<EventEnvelope> givenEvents() {
        return givenEvents;
    }

    /**
     * The "when" phase: process the given events through a subscriber.
     *
     * <p>The processor receives each given event in order. Use this to drive
     * a projection or subscriber handler.
     *
     * @param processor a consumer that processes each event
     *                  (e.g., projection::onEvent)
     * @return a ThenPhase for asserting on results
     */
    public ThenPhase whenProcessedBy(Consumer<EventEnvelope> processor) {
        Objects.requireNonNull(processor, "processor");
        for (EventEnvelope event : givenEvents) {
            processor.accept(event);
        }
        return new ThenPhase(givenEvents);
    }

    /**
     * The "when" phase: execute an action that may produce new events.
     *
     * <p>The action receives a collector where new events can be recorded.
     * Use this to test event production logic.
     *
     * @param action an action that produces events into the collector
     * @return a ProduceThenPhase for asserting on the produced events
     */
    public ProduceThenPhase whenProducing(Consumer<EventCollector> action) {
        Objects.requireNonNull(action, "action");
        EventCollector collector = new EventCollector();
        action.accept(collector);
        return new ProduceThenPhase(givenEvents, collector.collected());
    }

    /**
     * Collects events produced during the "when" phase.
     */
    public static final class EventCollector {

        private final ArrayList<EventEnvelope> events = new ArrayList<>();

        /** Creates a new empty event collector. */
        public EventCollector() {
            // explicit constructor for -Xlint compliance
        }

        /**
         * Records a produced event.
         *
         * @param event the event that was produced
         */
        public void add(EventEnvelope event) {
            events.add(Objects.requireNonNull(event, "event"));
        }

        /**
         * Returns all collected events.
         *
         * @return unmodifiable list of collected events
         */
        public List<EventEnvelope> collected() {
            return List.copyOf(events);
        }
    }

    /**
     * The "then" phase after subscriber processing.
     */
    public static final class ThenPhase {

        private final List<EventEnvelope> processedEvents;

        ThenPhase(List<EventEnvelope> processedEvents) {
            this.processedEvents = List.copyOf(processedEvents);
        }

        /**
         * Assert on the state after processing.
         *
         * <p>The assertion runnable queries the projection/subscriber state
         * directly and asserts on it.
         *
         * @param assertions assertions to run after processing
         */
        public void thenAssert(Runnable assertions) {
            assertions.run();
        }

        /**
         * Assert on the processed events.
         *
         * @param assertions consumer that receives the list of processed events
         */
        public void thenEvents(Consumer<List<EventEnvelope>> assertions) {
            assertions.accept(processedEvents);
        }

        /**
         * Verifies that the expected number of events were processed.
         *
         * @param expectedCount the expected count
         * @return this for chaining
         */
        public ThenPhase expectEventCount(int expectedCount) {
            if (processedEvents.size() != expectedCount) {
                throw new AssertionError(
                        "Expected " + expectedCount
                                + " processed events but got "
                                + processedEvents.size());
            }
            return this;
        }
    }

    /**
     * The "then" phase after event production.
     */
    public static final class ProduceThenPhase {

        private final List<EventEnvelope> givenEvents;
        private final List<EventEnvelope> producedEvents;

        ProduceThenPhase(List<EventEnvelope> givenEvents,
                         List<EventEnvelope> producedEvents) {
            this.givenEvents = List.copyOf(givenEvents);
            this.producedEvents = List.copyOf(producedEvents);
        }

        /**
         * Assert on the produced events.
         *
         * @param assertions consumer that receives the list of produced events
         */
        public void thenExpect(Consumer<List<EventEnvelope>> assertions) {
            assertions.accept(producedEvents);
        }

        /**
         * Verifies the expected number of events were produced.
         *
         * @param expectedCount the expected count
         * @return this for chaining
         */
        public ProduceThenPhase expectProducedCount(int expectedCount) {
            if (producedEvents.size() != expectedCount) {
                throw new AssertionError(
                        "Expected " + expectedCount
                                + " produced events but got "
                                + producedEvents.size());
            }
            return this;
        }

        /**
         * Verifies that no events were produced.
         */
        public void thenNoEvents() {
            if (!producedEvents.isEmpty()) {
                throw new AssertionError(
                        "Expected no produced events but got "
                                + producedEvents.size() + ": "
                                + producedEvents);
            }
        }

        /**
         * Returns the produced events for direct inspection.
         *
         * @return unmodifiable list of produced events
         */
        public List<EventEnvelope> producedEvents() {
            return producedEvents;
        }
    }
}
