/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus.test;

import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.bus.CheckpointStore;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.event.test.TestEventFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract contract test for {@link EventBus}.
 *
 * <p>Defines the 18-method behavioral contract that ALL {@code EventBus} implementations
 * must satisfy. Both {@code InMemoryEventBus} (test fixture) and the future
 * {@code InProcessEventBus} (production) extend this class and inherit the same
 * test suite.</p>
 *
 * <p>This follows the same pattern as {@code EventStoreContractTest} (27 methods)
 * and {@code CheckpointStoreContractTest} (9 methods): an abstract contract test
 * defines "correct," and each implementation provides the factory wiring via
 * abstract methods.</p>
 *
 * <p>The contract validated here covers:</p>
 * <ul>
 *   <li>Subscription lifecycle (register, replace, unsubscribe, checkpoint retention)</li>
 *   <li>Notification and filter evaluation (event type, priority, subject type)</li>
 *   <li>Checkpoint integration (position tracking, checkpoint-based skip)</li>
 *   <li>Concurrency safety (concurrent subscribe/notify/unsubscribe)</li>
 * </ul>
 *
 * <p>Subclasses must implement the six abstract methods to provide implementation
 * wiring. This abstract class calls {@link #resetAll()} in {@code @BeforeEach}
 * to ensure test isolation.</p>
 *
 * @see EventBus
 * @see SubscriberInfo
 * @see SubscriptionFilter
 * @see CheckpointStore
 */
@DisplayName("EventBus Contract")
public abstract class EventBusContractTest {

    /** Tracks notification callbacks per subscriber. */
    private final Map<String, List<Long>> notifications = new ConcurrentHashMap<>();

    /** Subclass constructor. */
    protected EventBusContractTest() {
        // Abstract — subclasses provide implementation.
    }

    // ──────────────────────────────────────────────────────────────────
    // Abstract factory methods
    // ──────────────────────────────────────────────────────────────────

    /** Returns the EventBus under test. */
    protected abstract EventBus bus();

    /** Returns the EventPublisher backing the bus (for publishing test events). */
    protected abstract EventPublisher publisher();

    /** Returns the EventStore backing the bus (for reading events in assertions). */
    protected abstract EventStore store();

    /** Returns the CheckpointStore backing the bus. */
    protected abstract CheckpointStore checkpointStore();

    /** Resets all stores and the bus to empty state. Called in {@code @BeforeEach}. */
    protected abstract void resetAll();

    /**
     * Hook for concrete tests to register a subscriber with a notification callback.
     *
     * <p>For {@code InMemoryEventBus}, this calls {@code subscribeWithHandler()}.
     * For future async implementations, this may use a different callback mechanism.</p>
     *
     * @param info    the subscriber registration metadata
     * @param handler callback invoked with the global position when a matching event
     *                is notified
     */
    protected abstract void subscribeWithCallback(SubscriberInfo info, Consumer<Long> handler);

    // ──────────────────────────────────────────────────────────────────
    // Setup and helpers
    // ──────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        resetAll();
        notifications.clear();
    }

    /**
     * Registers a subscriber via {@link #subscribeWithCallback} and tracks its
     * notifications in the {@link #notifications} map.
     *
     * @param subscriberId  the stable subscriber identifier
     * @param filter        the subscription filter for event matching
     * @param coalesceExempt whether the subscriber is exempt from coalescing
     * @return the SubscriberInfo that was registered
     */
    protected SubscriberInfo subscribeAndTrack(String subscriberId,
                                               SubscriptionFilter filter,
                                               boolean coalesceExempt) {
        SubscriberInfo info = new SubscriberInfo(subscriberId, filter, coalesceExempt);
        notifications.computeIfAbsent(subscriberId, k -> new CopyOnWriteArrayList<>());
        subscribeWithCallback(info, pos -> notifications.get(subscriberId).add(pos));
        return info;
    }

    /**
     * Returns positions notified to the given subscriber (empty list if none).
     *
     * @param subscriberId the subscriber to query
     * @return list of global positions notified to the subscriber
     */
    protected List<Long> notificationsFor(String subscriberId) {
        return notifications.getOrDefault(subscriberId, List.of());
    }

    /**
     * Publishes a test event via the publisher and notifies the bus.
     *
     * <p>This mirrors the production flow: {@code EventPublisher.publishRoot()} persists
     * the event, then the caller notifies the bus at the assigned global position.
     * The bus evaluates filters and notifies matching subscribers.</p>
     *
     * @param draft the event draft to publish
     * @return the persisted event envelope
     * @throws SequenceConflictException if the subject sequence conflicts
     */
    protected EventEnvelope publishAndNotify(EventDraft draft)
            throws SequenceConflictException {
        EventEnvelope envelope = publisher().publishRoot(draft);
        bus().notifyEvent(envelope.globalPosition());
        return envelope;
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 1: Subscription Lifecycle
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 1 — Subscription Lifecycle")
    class SubscriptionLifecycle {

        /** Creates a new test instance. */
        SubscriptionLifecycle() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("subscribe registers subscriber with initial position 0")
        void subscribe_registersSubscriber() {
            SubscriberInfo info = new SubscriberInfo(
                    "sub-A", SubscriptionFilter.all(), false);
            bus().subscribe(info);

            assertThat(bus().subscriberPosition("sub-A")).isEqualTo(0L);
            assertThat(checkpointStore().readCheckpoint("sub-A")).isEqualTo(0L);
        }

        @Test
        @DisplayName("subscribe with existing ID replaces registration filter")
        void subscribe_withExistingId_replacesRegistration()
                throws SequenceConflictException {
            // Subscribe with filter for "alpha" type
            subscribeAndTrack("sub-A", SubscriptionFilter.forTypes("alpha"), false);

            // Re-subscribe with filter for "beta" type — replaces the filter
            subscribeAndTrack("sub-A", SubscriptionFilter.forTypes("beta"), false);

            // Publish an "alpha" event — should NOT notify (filter was replaced)
            EventDraft alphaDraft = TestEventFactory.draftBuilder()
                    .eventType("alpha")
                    .build();
            publishAndNotify(alphaDraft);

            // Publish a "beta" event — should notify
            EventDraft betaDraft = TestEventFactory.draftBuilder()
                    .eventType("beta")
                    .build();
            publishAndNotify(betaDraft);

            // Only the beta event should have triggered notification
            assertThat(notificationsFor("sub-A")).hasSize(1);
        }

        @Test
        @DisplayName("unsubscribe removes subscriber from notification")
        void unsubscribe_removesSubscriber() throws SequenceConflictException {
            subscribeAndTrack("sub-A", SubscriptionFilter.all(), false);

            bus().unsubscribe("sub-A");

            publishAndNotify(TestEventFactory.draft());

            assertThat(notificationsFor("sub-A")).isEmpty();
        }

        @Test
        @DisplayName("unsubscribe with unknown ID is a no-op")
        void unsubscribe_unknownId_isNoOp() {
            // Should not throw any exception
            bus().unsubscribe("never-registered");
        }

        @Test
        @DisplayName("unsubscribe retains checkpoint for re-subscribe")
        void unsubscribe_retainsCheckpoint() {
            SubscriberInfo info = new SubscriberInfo(
                    "sub-A", SubscriptionFilter.all(), false);
            bus().subscribe(info);

            // Write checkpoint at position 10
            checkpointStore().writeCheckpoint("sub-A", 10);

            // Unsubscribe and re-subscribe
            bus().unsubscribe("sub-A");
            bus().subscribe(info);

            // Checkpoint should survive the unsubscribe/re-subscribe cycle
            assertThat(bus().subscriberPosition("sub-A")).isEqualTo(10L);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 2: Notification and Filtering
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 2 — Notification and Filtering")
    class NotificationAndFiltering {

        /** Creates a new test instance. */
        NotificationAndFiltering() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("notifyEvent with matching filter notifies subscriber")
        void notifyEvent_matchingFilter_subscriberNotified()
                throws SequenceConflictException {
            subscribeAndTrack("sub-A",
                    SubscriptionFilter.forTypes("test.event"), false);

            EventEnvelope envelope = publishAndNotify(TestEventFactory.draft());

            assertThat(notificationsFor("sub-A"))
                    .containsExactly(envelope.globalPosition());
        }

        @Test
        @DisplayName("notifyEvent with non-matching event type does not notify")
        void notifyEvent_nonMatchingEventType_subscriberNotNotified()
                throws SequenceConflictException {
            subscribeAndTrack("sub-A",
                    SubscriptionFilter.forTypes("wanted.type"), false);

            EventDraft draft = TestEventFactory.draftBuilder()
                    .eventType("unwanted.type")
                    .build();
            publishAndNotify(draft);

            assertThat(notificationsFor("sub-A")).isEmpty();
        }

        @Test
        @DisplayName("notifyEvent with non-matching priority does not notify")
        void notifyEvent_nonMatchingPriority_subscriberNotNotified()
                throws SequenceConflictException {
            // Filter accepts only CRITICAL (severity 0)
            subscribeAndTrack("sub-A",
                    SubscriptionFilter.forPriority(EventPriority.CRITICAL), false);

            // Publish a NORMAL-priority event (severity 1 — below CRITICAL)
            EventDraft draft = TestEventFactory.draftBuilder()
                    .priority(EventPriority.NORMAL)
                    .build();
            publishAndNotify(draft);

            assertThat(notificationsFor("sub-A")).isEmpty();
        }

        @Test
        @DisplayName("notifyEvent with non-matching subject type does not notify")
        void notifyEvent_nonMatchingSubjectType_subscriberNotNotified()
                throws SequenceConflictException {
            // Filter accepts only DEVICE subjects
            SubscriptionFilter filter = new SubscriptionFilter(
                    Set.of(), EventPriority.DIAGNOSTIC, SubjectType.DEVICE);
            subscribeAndTrack("sub-A", filter, false);

            // Default draft creates an ENTITY subject
            publishAndNotify(TestEventFactory.draft());

            assertThat(notificationsFor("sub-A")).isEmpty();
        }

        @Test
        @DisplayName("notifyEvent with empty event type set matches all types")
        void notifyEvent_emptyEventTypeSet_matchesAll()
                throws SequenceConflictException {
            // SubscriptionFilter.all() uses empty eventTypes = wildcard
            subscribeAndTrack("sub-A", SubscriptionFilter.all(), false);

            EventDraft draft1 = TestEventFactory.draftBuilder()
                    .eventType("type.alpha")
                    .build();
            EventDraft draft2 = TestEventFactory.draftBuilder()
                    .eventType("type.beta")
                    .build();
            publishAndNotify(draft1);
            publishAndNotify(draft2);

            assertThat(notificationsFor("sub-A")).hasSize(2);
        }

        @Test
        @DisplayName("notifyEvent notifies only matching subscribers")
        void notifyEvent_multipleSubscribers_onlyMatchingNotified()
                throws SequenceConflictException {
            subscribeAndTrack("sub-A",
                    SubscriptionFilter.forTypes("type.a"), false);
            subscribeAndTrack("sub-B",
                    SubscriptionFilter.forTypes("type.b"), false);

            EventDraft draft = TestEventFactory.draftBuilder()
                    .eventType("type.a")
                    .build();
            publishAndNotify(draft);

            assertThat(notificationsFor("sub-A")).hasSize(1);
            assertThat(notificationsFor("sub-B")).isEmpty();
        }

        @Test
        @DisplayName("coalesceExempt subscriber receives every notification")
        void notifyEvent_coalesceExempt_alwaysNotified()
                throws SequenceConflictException {
            subscribeAndTrack("exempt-sub", SubscriptionFilter.all(), true);

            publishAndNotify(TestEventFactory.draft());
            publishAndNotify(TestEventFactory.draft());
            publishAndNotify(TestEventFactory.draft());

            assertThat(notificationsFor("exempt-sub")).hasSize(3);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 3: Checkpoint Integration
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 3 — Checkpoint Integration")
    class CheckpointIntegration {

        /** Creates a new test instance. */
        CheckpointIntegration() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("subscriberPosition returns 0 for unknown subscriber")
        void subscriberPosition_noCheckpoint_returnsZero() {
            assertThat(bus().subscriberPosition("unknown-sub")).isEqualTo(0L);
        }

        @Test
        @DisplayName("subscriberPosition returns updated checkpoint value")
        void subscriberPosition_afterCheckpointWrite_returnsUpdatedPosition() {
            checkpointStore().writeCheckpoint("sub-A", 42);

            assertThat(bus().subscriberPosition("sub-A")).isEqualTo(42L);
        }

        @Test
        @DisplayName("subscribe loads checkpoint from store at registration")
        void subscribe_loadsCheckpointAtRegistration() {
            checkpointStore().writeCheckpoint("sub-A", 100);

            SubscriberInfo info = new SubscriberInfo(
                    "sub-A", SubscriptionFilter.all(), false);
            bus().subscribe(info);

            assertThat(bus().subscriberPosition("sub-A")).isEqualTo(100L);
        }

        @Test
        @DisplayName("notifyEvent skips subscriber whose checkpoint is at or past the position")
        void notifyEvent_belowSubscriberCheckpoint_subscriberNotNotified()
                throws SequenceConflictException {
            subscribeAndTrack("sub-A", SubscriptionFilter.all(), false);

            // Write checkpoint far ahead of any published event
            checkpointStore().writeCheckpoint("sub-A", 999);

            // Publish events — their positions will be 1, 2, etc. (all below 999)
            publishAndNotify(TestEventFactory.draft());
            publishAndNotify(TestEventFactory.draft());

            assertThat(notificationsFor("sub-A")).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 4: Concurrency Safety
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 4 — Concurrency Safety")
    class ConcurrencySafety {

        /** Creates a new test instance. */
        ConcurrencySafety() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("concurrent subscribe and notifyEvent produces no exceptions")
        void concurrentSubscribeAndNotify_noExceptions() throws InterruptedException {
            int threadCount = 4;
            var executor = Executors.newFixedThreadPool(threadCount);
            var latch = new CountDownLatch(threadCount);
            var errors = new CopyOnWriteArrayList<Throwable>();

            // 2 threads subscribing
            for (int t = 0; t < 2; t++) {
                int threadIdx = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            String id = "sub-" + threadIdx + "-" + i;
                            bus().subscribe(new SubscriberInfo(
                                    id, SubscriptionFilter.all(), false));
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 2 threads notifying
            for (int t = 0; t < 2; t++) {
                int threadIdx = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            bus().notifyEvent(threadIdx * 100L + i + 1);
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as("All threads should complete within 5 seconds")
                    .isTrue();
            assertThat(errors).isEmpty();
            executor.shutdown();
        }

        @Test
        @DisplayName("concurrent subscribe and unsubscribe produces no exceptions")
        void concurrentSubscribeAndUnsubscribe_noExceptions()
                throws InterruptedException {
            int threadCount = 4;
            var executor = Executors.newFixedThreadPool(threadCount);
            var latch = new CountDownLatch(threadCount);
            var errors = new CopyOnWriteArrayList<Throwable>();

            // 2 threads subscribing
            for (int t = 0; t < 2; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            String id = "concurrent-sub-" + i;
                            bus().subscribe(new SubscriberInfo(
                                    id, SubscriptionFilter.all(), false));
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // 2 threads unsubscribing (same IDs — may race with subscribe)
            for (int t = 0; t < 2; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            bus().unsubscribe("concurrent-sub-" + i);
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as("All threads should complete within 5 seconds")
                    .isTrue();
            assertThat(errors).isEmpty();
            executor.shutdown();
        }
    }
}
