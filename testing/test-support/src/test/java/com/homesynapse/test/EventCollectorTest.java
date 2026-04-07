/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.event.test.TestEventFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates that {@link EventCollector} and {@link TestSubscriber} produce
 * correct behavior for async subscriber testing infrastructure.
 *
 * @see EventCollector
 * @see TestSubscriber
 */
@DisplayName("EventCollector and TestSubscriber Validation")
class EventCollectorTest {

    /** Creates a new test instance. */
    EventCollectorTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // EventCollector tests
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("EventCollector")
    class EventCollectorTests {

        private EventCollector collector;

        /** Creates a new test instance. */
        EventCollectorTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @BeforeEach
        void setUp() {
            collector = new EventCollector();
        }

        @Test
        @DisplayName("new collector has zero count and is empty")
        void empty_hasZeroCount() {
            assertThat(collector.eventCount()).isZero();
            assertThat(collector.isEmpty()).isTrue();
            assertThat(collector.events()).isEmpty();
        }

        @Test
        @DisplayName("add increments count")
        void add_incrementsCount() {
            collector.add(TestEventFactory.envelope());

            assertThat(collector.eventCount()).isEqualTo(1);
            assertThat(collector.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("events() returns defensive copy")
        void events_returnsDefensiveCopy() {
            collector.add(TestEventFactory.envelope());
            List<EventEnvelope> snapshot = collector.events();

            // Add another event after taking the snapshot
            collector.add(TestEventFactory.envelope());

            // Snapshot must not reflect the new addition
            assertThat(snapshot).hasSize(1);
            assertThat(collector.eventCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("eventsOfType filters correctly")
        void eventsOfType_filtersCorrectly() {
            // Publish two events with different types via InMemoryEventStore
            // to get properly constructed envelopes
            EventEnvelope defaultEvent = TestEventFactory.envelope();

            EventEnvelope customEvent = TestEventFactory.envelopeBuilder()
                    .eventType("custom.type")
                    .build();

            collector.add(defaultEvent);
            collector.add(customEvent);

            assertThat(collector.eventsOfType("custom.type")).hasSize(1);
            assertThat(collector.eventsOfType("test.event")).hasSize(1);
            assertThat(collector.eventsOfType("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("awaitCount returns immediately when already met")
        void awaitCount_returnsImmediatelyWhenAlreadyMet() throws InterruptedException {
            collector.add(TestEventFactory.envelope());
            collector.add(TestEventFactory.envelope());

            boolean result = collector.awaitCount(2, Duration.ofMillis(100));

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("awaitCount blocks until met from another thread")
        void awaitCount_blocksUntilMet() throws Exception {
            // Start awaiting on a separate thread
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return collector.awaitCount(3, Duration.ofSeconds(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            });

            // Small delay to let the await start
            Thread.sleep(50);

            // Add events from the main thread
            collector.add(TestEventFactory.envelope());
            collector.add(TestEventFactory.envelope());
            collector.add(TestEventFactory.envelope());

            boolean result = future.get(5, TimeUnit.SECONDS);
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("awaitCount returns false on timeout")
        void awaitCount_returnsFalseOnTimeout() throws InterruptedException {
            // Only add 1 event, but wait for 3
            collector.add(TestEventFactory.envelope());

            boolean result = collector.awaitCount(3, Duration.ofMillis(100));

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("clear resets all state")
        void clear_resetsAllState() {
            collector.add(TestEventFactory.envelope());
            collector.add(TestEventFactory.envelope());

            collector.clear();

            assertThat(collector.eventCount()).isZero();
            assertThat(collector.isEmpty()).isTrue();
            assertThat(collector.events()).isEmpty();
        }

        @Test
        @DisplayName("lastEvent returns empty when empty")
        void lastEvent_returnsEmpty_whenEmpty() {
            assertThat(collector.lastEvent()).isEmpty();
        }

        @Test
        @DisplayName("lastEvent returns the most recently added event")
        void lastEvent_returnsMostRecent() {
            EventEnvelope first = TestEventFactory.envelope();
            EventEnvelope second = TestEventFactory.envelope();

            collector.add(first);
            collector.add(second);

            assertThat(collector.lastEvent()).hasValue(second);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // TestSubscriber tests
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TestSubscriber")
    class TestSubscriberTests {

        private static final Instant FIXED_TIME =
                Instant.parse("2026-04-07T12:00:00Z");
        private static final Clock FIXED_CLOCK =
                Clock.fixed(FIXED_TIME, ZoneOffset.UTC);

        private InMemoryEventStore store;
        private SynchronousEventBus bus;

        /** Creates a new test instance. */
        TestSubscriberTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @BeforeEach
        void setUp() {
            store = new InMemoryEventStore(FIXED_CLOCK);
            bus = new SynchronousEventBus();
        }

        @Test
        @DisplayName("pulls events from store on notification")
        void pullsEventsFromStore() throws Exception {
            TestSubscriber subscriber = TestSubscriber.create("test-sub", store);
            subscriber.registerWith(bus);

            // Publish an event via the store
            EventEnvelope published = store.publishRoot(TestEventFactory.draft());

            // Notify the bus — subscriber pulls from store
            bus.notifyEvent(published.globalPosition());

            assertThat(subscriber.collector().eventCount()).isEqualTo(1);
            assertThat(subscriber.collector().events().get(0).eventId())
                    .isEqualTo(published.eventId());
        }

        @Test
        @DisplayName("filters events by subscription filter")
        void filtersEventsBySubscriptionFilter() throws Exception {
            var subject = TestEventFactory.subject();

            TestSubscriber subscriber = TestSubscriber.create(
                    "filtered-sub", store,
                    SubscriptionFilter.forTypes("wanted.type"));
            subscriber.registerWith(bus);

            // Publish events with different types
            EventEnvelope wanted = store.publishRoot(
                    TestEventFactory.draftFor(subject, "wanted.type"));
            EventEnvelope unwanted = store.publishRoot(
                    TestEventFactory.draftFor(subject, "unwanted.type"));

            bus.notifyEvent(wanted.globalPosition());
            bus.notifyEvent(unwanted.globalPosition());

            // Only "wanted.type" should be collected
            assertThat(subscriber.collector().eventCount()).isEqualTo(1);
            assertThat(subscriber.collector().events().get(0).eventType())
                    .isEqualTo("wanted.type");
        }

        @Test
        @DisplayName("advances checkpoint after processing")
        void advancesCheckpoint() throws Exception {
            TestSubscriber subscriber = TestSubscriber.create("checkpoint-sub", store);
            subscriber.registerWith(bus);

            assertThat(subscriber.checkpoint()).isZero();

            EventEnvelope event = store.publishRoot(TestEventFactory.draft());
            bus.notifyEvent(event.globalPosition());

            assertThat(subscriber.checkpoint()).isGreaterThan(0);
        }

        @Test
        @DisplayName("failOnNthEvent throws at correct count")
        void failOnNthEvent_throwsAtCorrectCount() throws Exception {
            RuntimeException expected = new RuntimeException("injected failure");

            TestSubscriber subscriber = TestSubscriber.create("fail-sub", store)
                    .withFailOnNthEvent(2, expected);
            subscriber.registerWith(bus);

            // First event: should succeed
            EventEnvelope first = store.publishRoot(TestEventFactory.draft());
            bus.notifyEvent(first.globalPosition());
            assertThat(subscriber.collector().eventCount()).isEqualTo(1);

            // Second event: should throw
            EventEnvelope second = store.publishRoot(TestEventFactory.draft());
            assertThatThrownBy(() -> bus.notifyEvent(second.globalPosition()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("injected failure");
        }
    }
}
