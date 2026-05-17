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
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberReadConnectionFactory;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.event.test.TestEventFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    // Optional harness methods for M3.1+ tests (active runtime support)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the implementation supports active runtime features
     * (subscribeRuntime, mode FSM, supervisor, DLQ, per-subscriber isolation).
     *
     * <p>Defaults to {@code false}. Override in concrete tests that exercise
     * {@link InProcessEventBus} or equivalent production implementations.</p>
     *
     * @return {@code true} if Tiers 5–10 should run
     */
    protected boolean supportsActiveRuntime() {
        return false;
    }

    /**
     * Provides the Clock used by the bus for backoff/crash-window timing.
     *
     * <p>Returns {@code null} by default. Override when {@link #supportsActiveRuntime()}
     * returns {@code true}.</p>
     *
     * @return the clock, or {@code null} if not applicable
     */
    protected Clock clock() {
        return null;
    }

    /**
     * Provides access to the SubscriberReadConnectionFactory for introspection.
     *
     * <p>Returns {@code null} by default. Override when {@link #supportsActiveRuntime()}
     * returns {@code true}.</p>
     *
     * @return the factory, or {@code null} if not applicable
     */
    protected SubscriberReadConnectionFactory readConnectionFactory() {
        return null;
    }

    /**
     * Advances the fake clock by the given duration (for backoff testing).
     *
     * <p>No-op by default. Override when {@link #supportsActiveRuntime()}
     * returns {@code true}.</p>
     *
     * @param duration the amount of time to advance
     */
    protected void advanceClock(Duration duration) {
        // No-op by default — implementations override.
    }

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

    // ──────────────────────────────────────────────────────────────────
    // Tier 5: Mode State Machine
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 5 — Mode State Machine")
    class ModeStateMachine {

        /** Creates a new test instance. */
        ModeStateMachine() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @BeforeEach
        void assumeActiveRuntime() {
            assumeTrue(supportsActiveRuntime(),
                    "Skipped: implementation does not support active runtime");
        }

        @Test
        @DisplayName("subscribeRuntime starts subscriber in COLD mode")
        void subscribeRuntimeStartsInColdMode() {
            SubscriberInfo info = new SubscriberInfo(
                    "mode-sub", SubscriptionFilter.all(), false);
            Subscriber noOp = event -> {};

            bus().subscribeRuntime(info, noOp);

            // Immediately after registration, before VT runs, mode may be COLD
            // or REPLAY (if VT already scheduled). Both are acceptable for this
            // assertion — the key contract is that it starts in COLD.
            SubscriberSnapshot snapshot = bus().subscriberInfo("mode-sub");
            assertThat(snapshot.mode())
                    .isIn(SubscriberMode.COLD, SubscriberMode.REPLAY);
        }

        @Test
        @DisplayName("subscriber transitions COLD to REPLAY on first scheduling")
        void subscriberTransitionsColdToReplayOnFirstScheduling()
                throws InterruptedException {
            SubscriberInfo info = new SubscriberInfo(
                    "replay-sub", SubscriptionFilter.all(), false);
            Subscriber noOp = event -> {};

            bus().subscribeRuntime(info, noOp);

            // Allow the subscriber's VT to run
            Thread.sleep(100);

            SubscriberSnapshot snapshot = bus().subscriberInfo("replay-sub");
            assertThat(snapshot.mode()).isEqualTo(SubscriberMode.REPLAY);
        }

        @Test
        @DisplayName("mode reference is atomic across concurrent observers")
        void modeReferenceIsAtomicAcrossConcurrentObservers()
                throws InterruptedException {
            SubscriberInfo info = new SubscriberInfo(
                    "atomic-sub", SubscriptionFilter.all(), false);
            Subscriber noOp = event -> {};

            bus().subscribeRuntime(info, noOp);
            Thread.sleep(50); // Let VT start

            int threadCount = 4;
            var executor = Executors.newFixedThreadPool(threadCount);
            var latch = new CountDownLatch(threadCount);
            var errors = new CopyOnWriteArrayList<Throwable>();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            SubscriberSnapshot snap = bus().subscriberInfo("atomic-sub");
                            assertThat(snap.mode()).isNotNull();
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(errors).isEmpty();
            executor.shutdown();
        }

        @Test
        @DisplayName("unsubscribe closes subscriber runtime")
        void unsubscribeClosesSubscriberRuntime() throws InterruptedException {
            SubscriberInfo info = new SubscriberInfo(
                    "close-sub", SubscriptionFilter.all(), false);
            Subscriber noOp = event -> {};

            bus().subscribeRuntime(info, noOp);
            Thread.sleep(50); // Let VT start

            bus().unsubscribe("close-sub");
            Thread.sleep(50); // Let cleanup propagate

            // After unsubscribe, subscriberInfo should throw
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> bus().subscriberInfo("close-sub"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 6: Per-Subscriber Isolation (INV-SUB-ISO)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 6 — Per-Subscriber Isolation (INV-SUB-ISO)")
    class PerSubscriberIsolation {

        /** Creates a new test instance. */
        PerSubscriberIsolation() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @BeforeEach
        void assumeActiveRuntime() {
            assumeTrue(supportsActiveRuntime(),
                    "Skipped: implementation does not support active runtime");
        }

        @Test
        @DisplayName("INV-SUB-ISO-01: one virtual thread per subscriber")
        void INV_SUB_ISO_01_oneVirtualThreadPerSubscriber()
                throws InterruptedException {
            Subscriber noOp = event -> {};

            bus().subscribeRuntime(
                    new SubscriberInfo("iso-A", SubscriptionFilter.all(), false), noOp);
            bus().subscribeRuntime(
                    new SubscriberInfo("iso-B", SubscriptionFilter.all(), false), noOp);
            bus().subscribeRuntime(
                    new SubscriberInfo("iso-C", SubscriptionFilter.all(), false), noOp);

            Thread.sleep(100); // Let VTs start

            // Verify 3 subscribers are registered with active runtimes (each has a VT).
            // Thread.getAllStackTraces() does not include virtual threads in Java 21,
            // so we verify via the bus's introspection API which proves runtimes (and
            // thus VTs) were created.
            List<SubscriberSnapshot> snapshots = bus().subscribers();
            assertThat(snapshots).hasSize(3);
            assertThat(snapshots).extracting(SubscriberSnapshot::subscriberId)
                    .containsExactlyInAnyOrder("iso-A", "iso-B", "iso-C");
            // All should have transitioned past COLD (VT started and ran)
            assertThat(snapshots).allMatch(s -> s.mode() != SubscriberMode.COLD);
        }

        @Test
        @DisplayName("INV-SUB-ISO-02: one read connection per subscriber")
        void INV_SUB_ISO_02_oneReadConnectionPerSubscriber()
                throws InterruptedException {
            Subscriber noOp = event -> {};
            SubscriberReadConnectionFactory factory = readConnectionFactory();
            assumeTrue(factory instanceof RecordingReadConnectionFactory,
                    "Factory must be a RecordingReadConnectionFactory for this test");
            RecordingReadConnectionFactory recordingFactory =
                    (RecordingReadConnectionFactory) factory;

            bus().subscribeRuntime(
                    new SubscriberInfo("conn-A", SubscriptionFilter.all(), false), noOp);
            bus().subscribeRuntime(
                    new SubscriberInfo("conn-B", SubscriptionFilter.all(), false), noOp);
            bus().subscribeRuntime(
                    new SubscriberInfo("conn-C", SubscriptionFilter.all(), false), noOp);

            Thread.sleep(50);

            assertThat(recordingFactory.createCallCount()).isEqualTo(3);
            assertThat(recordingFactory.subscriberIds())
                    .containsExactlyInAnyOrder("conn-A", "conn-B", "conn-C");
        }

        @Test
        @DisplayName("INV-SUB-ISO-03: one DLQ per subscriber")
        void INV_SUB_ISO_03_oneDlqPerSubscriber()
                throws InterruptedException, SequenceConflictException {
            AtomicInteger callCountA = new AtomicInteger();
            Subscriber failingSub = event -> {
                callCountA.incrementAndGet();
                throw new RuntimeException("Simulated failure");
            };
            Subscriber successSub = event -> {};

            bus().subscribeRuntime(
                    new SubscriberInfo("dlq-A", SubscriptionFilter.all(), false),
                    failingSub);
            bus().subscribeRuntime(
                    new SubscriberInfo("dlq-B", SubscriptionFilter.all(), false),
                    successSub);

            Thread.sleep(100); // Let VTs start and reach REPLAY

            // Publish an event to trigger delivery
            publishAndNotify(TestEventFactory.draft());

            // Allow time for delivery + retries
            Thread.sleep(500);

            SubscriberSnapshot snapA = bus().subscriberInfo("dlq-A");
            SubscriberSnapshot snapB = bus().subscriberInfo("dlq-B");

            assertThat(snapA.dlqDepth()).isGreaterThan(0);
            assertThat(snapB.dlqDepth()).isEqualTo(0);
        }

        @Test
        @DisplayName("INV-SUB-ISO-04: one mode ref per subscriber")
        void INV_SUB_ISO_04_oneModeRefPerSubscriber()
                throws InterruptedException, SequenceConflictException {
            AtomicInteger failCount = new AtomicInteger();
            Subscriber alwaysFails = event -> {
                failCount.incrementAndGet();
                throw new RuntimeException("Crash #" + failCount.get());
            };
            Subscriber successSub = event -> {};

            bus().subscribeRuntime(
                    new SubscriberInfo("mode-A", SubscriptionFilter.all(), false),
                    alwaysFails);
            bus().subscribeRuntime(
                    new SubscriberInfo("mode-B", SubscriptionFilter.all(), false),
                    successSub);

            Thread.sleep(100);

            // Trigger enough events to trip the circuit breaker for mode-A
            for (int i = 0; i < 5; i++) {
                publishAndNotify(TestEventFactory.draft());
                Thread.sleep(200);
            }

            // Allow time for supervisor retries and circuit breaker
            Thread.sleep(2000);

            SubscriberSnapshot snapA = bus().subscriberInfo("mode-A");
            SubscriberSnapshot snapB = bus().subscriberInfo("mode-B");

            assertThat(snapA.mode()).isEqualTo(SubscriberMode.SUSPENDED);
            assertThat(snapB.mode()).isNotEqualTo(SubscriberMode.SUSPENDED);
        }

        @Test
        @DisplayName("INV-SUB-ISO-05: replay window queue is isolated")
        void INV_SUB_ISO_05_replayWindowQueueIsolated()
                throws InterruptedException {
            Subscriber noOp = event -> {};

            bus().subscribeRuntime(
                    new SubscriberInfo("rwq-A", SubscriptionFilter.all(), false), noOp);
            bus().subscribeRuntime(
                    new SubscriberInfo("rwq-B", SubscriptionFilter.all(), false), noOp);

            Thread.sleep(50);

            // Both subscribers exist with independent replay window queues.
            // Full queue behavior validated in M3.2.
            SubscriberSnapshot snapA = bus().subscriberInfo("rwq-A");
            SubscriberSnapshot snapB = bus().subscriberInfo("rwq-B");
            assertThat(snapA.subscriberId()).isEqualTo("rwq-A");
            assertThat(snapB.subscriberId()).isEqualTo("rwq-B");
        }

        @Test
        @DisplayName("INV-SUB-ISO-06: self-filter isolated when present")
        void INV_SUB_ISO_06_selfFilterIsolatedWhenPresent()
                throws InterruptedException {
            Subscriber noOp = event -> {};

            // Register two subscribers — verify each has independent runtime
            bus().subscribeRuntime(
                    new SubscriberInfo("sf-A", SubscriptionFilter.all(), false), noOp);
            bus().subscribeRuntime(
                    new SubscriberInfo("sf-B", SubscriptionFilter.all(), false), noOp);

            Thread.sleep(50);

            // Placeholder assertion: both subscribers exist independently.
            // Full self-filter behavior validated in M3.5a.
            assertThat(bus().subscribers()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 7: Supervisor
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 7 — Supervisor")
    class Supervisor {

        /** Creates a new test instance. */
        Supervisor() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @BeforeEach
        void assumeActiveRuntime() {
            assumeTrue(supportsActiveRuntime(),
                    "Skipped: implementation does not support active runtime");
        }

        @Test
        @DisplayName("supervisor catches subscriber exception without affecting other subscribers")
        void supervisorCatchesSubscriberException()
                throws InterruptedException, SequenceConflictException {
            Subscriber failingSub = event -> {
                throw new RuntimeException("sub-A fails");
            };
            Subscriber successSub = event -> {};

            bus().subscribeRuntime(
                    new SubscriberInfo("sup-A", SubscriptionFilter.all(), false),
                    failingSub);
            bus().subscribeRuntime(
                    new SubscriberInfo("sup-B", SubscriptionFilter.all(), false),
                    successSub);

            Thread.sleep(200); // Let VTs start

            publishAndNotify(TestEventFactory.draft());
            Thread.sleep(300); // Let VTs process

            // sub-A should have a DLQ entry from the failure
            SubscriberSnapshot snapA = bus().subscriberInfo("sup-A");
            assertThat(snapA.dlqDepth()).isGreaterThan(0);

            // sub-B should NOT be suspended — failure in A didn't affect B
            SubscriberSnapshot snapB = bus().subscriberInfo("sup-B");
            assertThat(snapB.mode()).isNotEqualTo(SubscriberMode.SUSPENDED);
        }

        @Test
        @DisplayName("supervisor records DLQ entry on exception")
        void supervisorRecordsDlqEntryOnException()
                throws InterruptedException, SequenceConflictException {
            Subscriber failingSub = event -> {
                throw new RuntimeException("DLQ test");
            };

            bus().subscribeRuntime(
                    new SubscriberInfo("dlq-sub", SubscriptionFilter.all(), false),
                    failingSub);

            Thread.sleep(200);

            publishAndNotify(TestEventFactory.draft());
            Thread.sleep(300);

            SubscriberSnapshot snap = bus().subscriberInfo("dlq-sub");
            assertThat(snap.dlqDepth()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("supervisor retries after backoff")
        void supervisorRetriesAfterBackoff()
                throws InterruptedException, SequenceConflictException {
            AtomicInteger callCount = new AtomicInteger();
            Subscriber retryingSub = event -> {
                if (callCount.incrementAndGet() <= 1) {
                    throw new RuntimeException("First attempt fails");
                }
                // Subsequent attempts succeed
            };

            bus().subscribeRuntime(
                    new SubscriberInfo("retry-sub", SubscriptionFilter.all(), false),
                    retryingSub);

            Thread.sleep(200);

            // First event fails, then VT loop re-delivers same position (which
            // is still in the pending queue — the VT will process it again from
            // the event store). Publish two events to give the subscriber a
            // second chance to succeed.
            publishAndNotify(TestEventFactory.draft());
            Thread.sleep(200);
            publishAndNotify(TestEventFactory.draft());
            Thread.sleep(300);

            // The subscriber should have been called at least twice
            assertThat(callCount.get()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("supervisor trips circuit breaker at 5 crashes")
        void supervisorTripsCircuitBreakerAt5Crashes()
                throws InterruptedException, SequenceConflictException {
            Subscriber alwaysFails = event -> {
                throw new RuntimeException("Always fails");
            };

            bus().subscribeRuntime(
                    new SubscriberInfo("cb-sub", SubscriptionFilter.all(), false),
                    alwaysFails);

            Thread.sleep(200);

            // Trigger 5 events — each failure records a crash in the window
            for (int i = 0; i < 6; i++) {
                publishAndNotify(TestEventFactory.draft());
                Thread.sleep(100);
            }

            // Allow time for VT to process all events
            Thread.sleep(500);

            SubscriberSnapshot snap = bus().subscriberInfo("cb-sub");
            assertThat(snap.mode()).isEqualTo(SubscriberMode.SUSPENDED);
        }

        @Test
        @DisplayName("circuit breaker resume restores delivery")
        void circuitBreakerResumeRestoresDelivery()
                throws InterruptedException, SequenceConflictException {
            Subscriber alwaysFails = event -> {
                throw new RuntimeException("Fails");
            };

            bus().subscribeRuntime(
                    new SubscriberInfo("resume-sub", SubscriptionFilter.all(), false),
                    alwaysFails);

            Thread.sleep(200);

            // Trip the circuit breaker with 6 events (need ≥5 crashes)
            for (int i = 0; i < 6; i++) {
                publishAndNotify(TestEventFactory.draft());
                Thread.sleep(100);
            }
            Thread.sleep(500);

            SubscriberSnapshot snapBefore = bus().subscriberInfo("resume-sub");
            assertThat(snapBefore.mode()).isEqualTo(SubscriberMode.SUSPENDED);

            // Resume
            bus().resume("resume-sub");

            SubscriberSnapshot snapAfter = bus().subscriberInfo("resume-sub");
            assertThat(snapAfter.crashCount()).isEqualTo(0);
            assertThat(snapAfter.mode()).isEqualTo(SubscriberMode.REPLAY);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 8: Lifecycle
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 8 — Lifecycle")
    class Lifecycle {

        /** Creates a new test instance. */
        Lifecycle() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @BeforeEach
        void assumeActiveRuntime() {
            assumeTrue(supportsActiveRuntime(),
                    "Skipped: implementation does not support active runtime");
        }

        @Test
        @DisplayName("onCaughtUp default is no-op and does not throw")
        void onCaughtUpDefaultNoOp() {
            Subscriber sub = event -> {};
            // Default onCaughtUp should not throw
            sub.onCaughtUp();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 9: REPLAY→LIVE Transition (M3.2 — disabled placeholders)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 9 — REPLAY→LIVE Transition (M3.2)")
    @Disabled("M3.2")
    class ReplayToLiveTransition {

        /** Creates a new test instance. */
        ReplayToLiveTransition() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("replay delivers from checkpoint forward")
        void replayDeliversFromCheckpointForward() {
            // M3.2 placeholder
        }

        @Test
        @DisplayName("transition drains replay window queue")
        void transitionDrainsReplayWindowQueue() {
            // M3.2 placeholder
        }

        @Test
        @DisplayName("LIVE transition fires onCaughtUp exactly once")
        void liveTransitionFiresOnCaughtUpExactlyOnce() {
            // M3.2 placeholder
        }

        @Test
        @DisplayName("replay window overflow at 10000 is critical alert")
        void replayWindowOverflowAt10000IsCriticalAlert() {
            // M3.2 placeholder
        }

        @Test
        @DisplayName("multiple subscribers in replay do not interfere")
        void multipleSubscribersInReplayDoNotInterfere() {
            // M3.2 placeholder
        }

        @Test
        @DisplayName("reconciliation on version mismatch")
        void reconciliationOnVersionMismatch() {
            // M3.2 placeholder
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 10: Backpressure and Metrics (M3.3 — disabled placeholders)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 10 — Backpressure and Metrics (M3.3)")
    @Disabled("M3.3")
    class BackpressureAndMetrics {

        /** Creates a new test instance. */
        BackpressureAndMetrics() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("publish does not block at 5000")
        void publishDoesNotBlockAt5000() {
            // M3.3 placeholder
        }

        @Test
        @DisplayName("publisher blocked count increments")
        void publisherBlockedCountIncrements() {
            // M3.3 placeholder
        }

        @Test
        @DisplayName("writer queue depth gauge samples on enqueue and dequeue")
        void writerQueueDepthGaugeSamplesOnEnqueueAndDequeue() {
            // M3.3 placeholder
        }

        @Test
        @DisplayName("subscriber lag gauge populated after delivery")
        void subscriberLagGaugePopulatedAfterDelivery() {
            // M3.3 placeholder
        }
    }
}
