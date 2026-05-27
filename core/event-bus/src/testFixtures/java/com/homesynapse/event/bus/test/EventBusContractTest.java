/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus.test;

import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.bus.BusMetrics;
import com.homesynapse.event.bus.CheckpointStore;
import com.homesynapse.event.bus.EventBus;
import com.homesynapse.event.bus.EventBusConfig;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberInfo;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.bus.SubscriberReadConnectionFactory;
import com.homesynapse.event.bus.SubscriberSnapshot;
import com.homesynapse.event.bus.SubscriptionFilter;
import com.homesynapse.event.test.TestEventFactory;

import org.junit.jupiter.api.BeforeEach;
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

    /**
     * Provides the recording metrics view for Tier 10 assertions (M3.3).
     *
     * <p>Returns {@code null} by default. Active-runtime implementations
     * override to expose the {@link BusMetricsRecorder} wired into the bus
     * under test, allowing per-test inspection of the seven canonical
     * metric emissions (AMD-43 §3.6.2).</p>
     *
     * @return the recorder, or {@code null} if not applicable
     */
    protected BusMetricsRecorder metrics() {
        return null;
    }

    /**
     * Provides the controllable queue-depth source the bus reads on every
     * publish notification (DEC-M3-14, M3.3). Tests mutate this to drive the
     * publisher-blocked counter and the writer-queue-depth gauge.
     *
     * <p>Returns {@code null} by default. Active-runtime implementations
     * override to expose the {@link AtomicInteger} wired into the bus's
     * {@link java.util.function.IntSupplier IntSupplier}.</p>
     *
     * @return the mutable depth source, or {@code null} if not applicable
     */
    protected AtomicInteger queueDepth() {
        return null;
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
     * Polls the bus's introspection API until the given subscriber reaches the
     * specified mode, or fails with an {@link AssertionError} after the default
     * 5-second budget elapses.
     *
     * <p>Polling cadence is fixed at 50&nbsp;ms increments — this method does
     * not consult any direct time source (NO_DIRECT_TIME_ACCESS). Time
     * granularity is therefore approximate to within one polling interval.</p>
     *
     * @param subscriberId the subscriber to observe
     * @param targetMode   the desired mode
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    protected void awaitMode(String subscriberId, SubscriberMode targetMode)
            throws InterruptedException {
        awaitMode(subscriberId, targetMode, 5_000L);
    }

    /**
     * Polls the bus's introspection API until the given subscriber reaches the
     * specified mode, or fails with an {@link AssertionError} after the given
     * millisecond budget elapses.
     *
     * @param subscriberId   the subscriber to observe
     * @param targetMode     the desired mode
     * @param maxWaitMillis  total wait budget in milliseconds (rounded down to the
     *                       nearest 50&nbsp;ms polling interval)
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    protected void awaitMode(String subscriberId, SubscriberMode targetMode,
                             long maxWaitMillis) throws InterruptedException {
        long intervals = Math.max(1L, maxWaitMillis / 50L);
        for (long i = 0; i < intervals; i++) {
            try {
                if (bus().subscriberInfo(subscriberId).mode() == targetMode) {
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Subscriber not yet registered — keep polling.
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Subscriber '" + subscriberId
                + "' did not reach mode " + targetMode + " within "
                + maxWaitMillis + " ms");
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

            // Immediately after registration the VT may not have run (COLD), or it
            // may have raced through the full COLD→REPLAY→TRANSITION→LIVE
            // sequence (M3.2 — empty store completes catch-up in microseconds).
            // The contract is that registration does NOT leave the subscriber in
            // SUSPENDED; any forward-progress mode is acceptable.
            SubscriberSnapshot snapshot = bus().subscriberInfo("mode-sub");
            assertThat(snapshot.mode())
                    .isIn(SubscriberMode.COLD, SubscriberMode.REPLAY,
                            SubscriberMode.TRANSITION, SubscriberMode.LIVE);
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

            // The COLD → REPLAY transition has fired. With M3.2's full algorithm
            // the subscriber may also have progressed through TRANSITION to LIVE
            // (empty store ⇒ tail reached immediately). The contract this test
            // guards is that COLD has been exited on first scheduling.
            SubscriberSnapshot snapshot = bus().subscriberInfo("replay-sub");
            assertThat(snapshot.mode())
                    .isIn(SubscriberMode.REPLAY, SubscriberMode.TRANSITION,
                            SubscriberMode.LIVE);
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
    // Tier 9: REPLAY→LIVE Transition (M3.2)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 9 — REPLAY→LIVE Transition (M3.2)")
    class ReplayToLiveTransition {

        /** Creates a new test instance. */
        ReplayToLiveTransition() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @BeforeEach
        void assumeActiveRuntime() {
            assumeTrue(supportsActiveRuntime(),
                    "Skipped: implementation does not support active runtime");
        }

        @Test
        @DisplayName("replay delivers from checkpoint forward")
        void replayDeliversFromCheckpointForward()
                throws InterruptedException, SequenceConflictException {
            // Pre-populate the log with 5 events (positions 1..5).
            EventEnvelope[] events = new EventEnvelope[5];
            for (int i = 0; i < 5; i++) {
                events[i] = publisher().publishRoot(TestEventFactory.draft());
            }

            // Persist a checkpoint at position 2 so subscribe should resume from 3.
            checkpointStore().writeCheckpoint("rcf-sub", 2L);

            List<Long> received = new CopyOnWriteArrayList<>();
            Subscriber recorder = env -> received.add(env.globalPosition());

            bus().subscribeRuntime(
                    new SubscriberInfo("rcf-sub", SubscriptionFilter.all(), false),
                    recorder);

            // Wait for the subscriber to catch up to LIVE — at which point all
            // post-checkpoint events should have been delivered.
            awaitMode("rcf-sub", SubscriberMode.LIVE);

            // Subscriber must have seen events 3, 4, 5 — and only those.
            assertThat(received).containsExactly(
                    events[2].globalPosition(),
                    events[3].globalPosition(),
                    events[4].globalPosition());
            assertThat(received).doesNotContain(
                    events[0].globalPosition(),
                    events[1].globalPosition());
        }

        @Test
        @DisplayName("transition drains replay window queue")
        void transitionDrainsReplayWindowQueue()
                throws InterruptedException, SequenceConflictException {
            // Subscriber blocks on its first delivery to keep mode = REPLAY long
            // enough for additional publishes to land in the replay window queue.
            CountDownLatch firstDelivery = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Long> received = new CopyOnWriteArrayList<>();
            Subscriber blocking = env -> {
                received.add(env.globalPosition());
                if (received.size() == 1) {
                    firstDelivery.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            // Pre-publish event 1 so the driver has something to deliver during REPLAY.
            publishAndNotify(TestEventFactory.draft());

            bus().subscribeRuntime(
                    new SubscriberInfo("drain-sub", SubscriptionFilter.all(), false),
                    blocking);

            // Wait until the subscriber has begun (and is blocked on) its first delivery.
            assertThat(firstDelivery.await(2, TimeUnit.SECONDS))
                    .as("Subscriber should begin first delivery")
                    .isTrue();

            // While blocked in REPLAY, publish three more events. They will route
            // into the replay window queue (mode = REPLAY).
            publishAndNotify(TestEventFactory.draft());
            publishAndNotify(TestEventFactory.draft());
            publishAndNotify(TestEventFactory.draft());

            // Unblock the subscriber. Driver continues paging, picks up events 2..4
            // from the store, delivers them, then TRANSITION drains the queue and
            // gap detection skips entries that were already delivered.
            release.countDown();

            awaitMode("drain-sub", SubscriberMode.LIVE);

            // Each event must be delivered exactly once — no duplicates from the
            // REPLAY/queue overlap (INV-BUS-01).
            assertThat(received).containsExactly(1L, 2L, 3L, 4L);
        }

        @Test
        @DisplayName("LIVE transition fires onCaughtUp exactly once")
        void liveTransitionFiresOnCaughtUpExactlyOnce()
                throws InterruptedException {
            AtomicInteger caughtUpA = new AtomicInteger();
            AtomicInteger caughtUpB = new AtomicInteger();

            Subscriber subA = new Subscriber() {
                @Override
                public void onEvent(EventEnvelope event) {
                }

                @Override
                public void onCaughtUp() {
                    caughtUpA.incrementAndGet();
                }
            };
            Subscriber subB = new Subscriber() {
                @Override
                public void onEvent(EventEnvelope event) {
                }

                @Override
                public void onCaughtUp() {
                    caughtUpB.incrementAndGet();
                }
            };

            bus().subscribeRuntime(
                    new SubscriberInfo("cu-A", SubscriptionFilter.all(), false), subA);
            awaitMode("cu-A", SubscriberMode.LIVE);
            assertThat(caughtUpA.get())
                    .as("onCaughtUp fires exactly once for subscriber A")
                    .isEqualTo(1);

            // Register a second subscriber — its own onCaughtUp must fire exactly
            // once, and A's must not fire again.
            bus().subscribeRuntime(
                    new SubscriberInfo("cu-B", SubscriptionFilter.all(), false), subB);
            awaitMode("cu-B", SubscriberMode.LIVE);
            assertThat(caughtUpB.get())
                    .as("onCaughtUp fires exactly once for subscriber B")
                    .isEqualTo(1);
            assertThat(caughtUpA.get())
                    .as("onCaughtUp on A must not fire a second time")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("replay window overflow at configured capacity is critical alert")
        void replayWindowOverflowAtConfiguredCapacityIsCriticalAlert()
                throws InterruptedException, SequenceConflictException {
            // M3.6b: thresholds derive from EventBusConfig.HOME_DEFAULT (which
            // the harness wires by default) so the assertion stays in lock-step
            // with the bus's configured replay window capacity.
            final int capacity = EventBusConfig.HOME_DEFAULT.replayQueueCapacity();
            final int overflowPublishCount = capacity + 1;
            final long totalExpectedUnique = (long) capacity + 2L;

            // Block the subscriber on its first delivery so the driver stays in
            // REPLAY while we flood the replay window queue past its capacity bound.
            CountDownLatch firstDelivery = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Long> received = new CopyOnWriteArrayList<>();
            Subscriber blocking = env -> {
                received.add(env.globalPosition());
                if (received.size() == 1) {
                    firstDelivery.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            // One event in the store so the subscriber lands in onEvent and blocks.
            publishAndNotify(TestEventFactory.draft());

            bus().subscribeRuntime(
                    new SubscriberInfo("overflow-sub", SubscriptionFilter.all(), false),
                    blocking);

            assertThat(firstDelivery.await(5, TimeUnit.SECONDS))
                    .as("Subscriber should begin first delivery")
                    .isTrue();

            // Publish capacity+1 more events while the subscriber is blocked in
            // REPLAY. The first `capacity` will fit in the queue; the
            // capacity+1th will trigger overflow.
            for (int i = 0; i < overflowPublishCount; i++) {
                publishAndNotify(TestEventFactory.draft());
            }

            // Release the subscriber. The driver observes the overflow flag, resets
            // its cursor to the persisted checkpoint, clears the queue, and re-pages
            // through the entire log — eventually reaching LIVE.
            release.countDown();

            awaitMode("overflow-sub", SubscriberMode.LIVE, 30_000);

            // Every published event was delivered at least once (no data loss).
            long uniqueDelivered = received.stream().distinct().count();
            assertThat(uniqueDelivered)
                    .as("Every event must be delivered at least once (capacity+2 unique positions)")
                    .isEqualTo(totalExpectedUnique);

            // Re-pagination after overflow means at least one event was redelivered.
            assertThat(received.size())
                    .as("Overflow restart re-pages through the log, producing redeliveries")
                    .isGreaterThan((int) totalExpectedUnique);
        }

        @Test
        @DisplayName("multiple subscribers in replay do not interfere")
        void multipleSubscribersInReplayDoNotInterfere()
                throws InterruptedException, SequenceConflictException {
            // Two filter-disjoint subscribers, each blocked on its first delivery
            // so both stay in REPLAY while we publish more events of each type.
            CountDownLatch firstA = new CountDownLatch(1);
            CountDownLatch firstB = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Long> receivedA = new CopyOnWriteArrayList<>();
            List<Long> receivedB = new CopyOnWriteArrayList<>();

            Subscriber blockingA = env -> {
                receivedA.add(env.globalPosition());
                if (receivedA.size() == 1) {
                    firstA.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            Subscriber blockingB = env -> {
                receivedB.add(env.globalPosition());
                if (receivedB.size() == 1) {
                    firstB.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            // Seed one event of each type so each subscriber lands in onEvent.
            publishAndNotify(TestEventFactory.draftBuilder().eventType("alpha").build());
            publishAndNotify(TestEventFactory.draftBuilder().eventType("beta").build());

            bus().subscribeRuntime(
                    new SubscriberInfo("iso-A",
                            SubscriptionFilter.forTypes("alpha"), false),
                    blockingA);
            bus().subscribeRuntime(
                    new SubscriberInfo("iso-B",
                            SubscriptionFilter.forTypes("beta"), false),
                    blockingB);

            assertThat(firstA.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(firstB.await(2, TimeUnit.SECONDS)).isTrue();

            // Both subscribers are blocked in REPLAY. Publish more events that route
            // into each subscriber's own replay window queue.
            for (int i = 0; i < 3; i++) {
                publishAndNotify(
                        TestEventFactory.draftBuilder().eventType("alpha").build());
                publishAndNotify(
                        TestEventFactory.draftBuilder().eventType("beta").build());
            }

            release.countDown();

            awaitMode("iso-A", SubscriberMode.LIVE);
            awaitMode("iso-B", SubscriberMode.LIVE);

            // INV-SUB-ISO-05: A's queue is isolated from B's — A never sees beta
            // positions and B never sees alpha positions.
            for (Long pos : receivedA) {
                EventPage page = store().readFrom(pos - 1, 1);
                assertThat(page.events().get(0).eventType()).isEqualTo("alpha");
            }
            for (Long pos : receivedB) {
                EventPage page = store().readFrom(pos - 1, 1);
                assertThat(page.events().get(0).eventType()).isEqualTo("beta");
            }
            assertThat(receivedA.stream().distinct().count())
                    .as("subscriber A receives all 4 alpha events")
                    .isEqualTo(4L);
            assertThat(receivedB.stream().distinct().count())
                    .as("subscriber B receives all 4 beta events")
                    .isEqualTo(4L);
        }

        @Test
        @DisplayName("reconciliation on version mismatch — externally reset checkpoint re-replays")
        void reconciliationOnVersionMismatch()
                throws InterruptedException, SequenceConflictException {
            // M3.6d-a: This test exercises the BUS-side observable consequence
            // of reconciliation — namely that a subscriber's checkpoint, if
            // externally reset back to position 0, re-pages through the full
            // event log on its next subscription. The reconciliation pass
            // itself (version-mismatch detection in StateProjection.initialize)
            // is tested in core/state-store's ReconciliationTest; here we
            // verify the bus honours the reset by re-replaying every event.
            //
            // Sequence:
            //   1. Publish N=10 events while no subscribers exist.
            //   2. Subscribe sub1; wait for LIVE; verify all 10 received.
            //   3. Unsubscribe sub1 (closes its VT).
            //   4. Externally reset checkpoint to 0 (the post-reconciliation
            //      state in production — StateProjection's cursor reset is
            //      flushed to the bus's CheckpointStore on the next checkpoint
            //      cadence; here we simulate that flush directly).
            //   5. Re-subscribe with the same subscriberId. The new VT reads
            //      the (now-zero) checkpoint via ReplayDriver and re-pages
            //      through all 10 events from the beginning.
            //   6. Assert sub2 received all 10 events.
            //
            // This is NOT testing bus.resume() (which has the known M4
            // VT re-spawn limitation per coder-handoff.md). The subscriber
            // is fully torn down and re-created, exercising the standard
            // subscribeRuntime → COLD → REPLAY → LIVE path.

            final int eventCount = 10;
            final String subscriberId = "reconcile-sub";

            // Step 1: publish all events before any subscription exists so
            // they're already in the store and the subscriber paginates them
            // during REPLAY.
            for (int i = 0; i < eventCount; i++) {
                publishAndNotify(TestEventFactory.draft());
            }

            // Step 2: first subscription receives all 10 events during REPLAY.
            List<Long> firstRun = new CopyOnWriteArrayList<>();
            CountDownLatch firstRunSeen = new CountDownLatch(eventCount);
            Subscriber sub1 = env -> {
                firstRun.add(env.globalPosition());
                firstRunSeen.countDown();
            };

            bus().subscribeRuntime(
                    new SubscriberInfo(subscriberId, SubscriptionFilter.all(), false),
                    sub1);
            awaitMode(subscriberId, SubscriberMode.LIVE);
            assertThat(firstRunSeen.await(5, TimeUnit.SECONDS))
                    .as("first subscription should observe all %d events".formatted(eventCount))
                    .isTrue();
            assertThat(firstRun.stream().distinct().count())
                    .as("first subscription processed every unique position")
                    .isEqualTo((long) eventCount);

            // Step 3: tear down the first subscriber's VT.
            bus().unsubscribe(subscriberId);

            // Step 4: simulate the post-reconciliation checkpoint flush by
            // resetting the bus's persisted position for this subscriber to
            // zero. In production, StateProjection's reconciliation pass
            // resets its internal cursor to 0 and the next checkpoint cadence
            // writes that 0 through to the bus's CheckpointStore.
            checkpointStore().writeCheckpoint(subscriberId, 0L);
            assertThat(checkpointStore().readCheckpoint(subscriberId))
                    .as("checkpoint was externally reset to 0")
                    .isZero();

            // Step 5: re-subscribe with the same subscriberId. The new VT
            // reads the now-zero checkpoint and re-pages through every event.
            List<Long> secondRun = new CopyOnWriteArrayList<>();
            CountDownLatch secondRunSeen = new CountDownLatch(eventCount);
            Subscriber sub2 = env -> {
                secondRun.add(env.globalPosition());
                secondRunSeen.countDown();
            };

            bus().subscribeRuntime(
                    new SubscriberInfo(subscriberId, SubscriptionFilter.all(), false),
                    sub2);
            awaitMode(subscriberId, SubscriberMode.LIVE);
            assertThat(secondRunSeen.await(5, TimeUnit.SECONDS))
                    .as("re-subscription should re-replay all %d events from position 0"
                            .formatted(eventCount))
                    .isTrue();

            // Step 6: both runs cover the same set of positions — every
            // event was redelivered. This is the at-least-once delivery
            // guarantee (INV-ES-05) operating in the reconciliation scenario.
            assertThat(secondRun.stream().distinct().count())
                    .as("re-subscription re-processed every unique position")
                    .isEqualTo((long) eventCount);
            assertThat(secondRun.stream().distinct().sorted().toList())
                    .as("re-subscription covers exactly the same positions as the first run")
                    .containsExactlyElementsOf(firstRun.stream().distinct().sorted().toList());
        }

        @Test
        @DisplayName("bus invokes Subscriber.setMode at each successful runtime.mode CAS")
        void busInvokesSetModeAfterEachSuccessfulCas() throws InterruptedException {
            // M3.7 fix round 4: the bus is responsible for keeping a subscriber's
            // self-tracked mode in sync with the runtime's authoritative FSM.
            // Empty store → COLD → REPLAY → TRANSITION → LIVE completes without
            // any onEvent firing; this test asserts that setMode was nevertheless
            // called with at least one non-COLD mode (i.e., the bus made it past
            // the COLD→REPLAY CAS into the actual lifecycle).
            List<SubscriberMode> observed = new CopyOnWriteArrayList<>();
            Subscriber recorder = new Subscriber() {
                @Override
                public void onEvent(EventEnvelope event) {
                }

                @Override
                public void setMode(SubscriberMode mode) {
                    observed.add(mode);
                }
            };

            bus().subscribeRuntime(
                    new SubscriberInfo("setmode-sub", SubscriptionFilter.all(), false),
                    recorder);
            awaitMode("setmode-sub", SubscriberMode.LIVE);

            assertThat(observed)
                    .as("bus must invoke setMode on at least one non-COLD transition")
                    .anyMatch(m -> m != SubscriberMode.COLD);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Tier 10: Backpressure and Metrics (M3.3 — AMD-43 §3.6.2)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Tier 10 — Backpressure and Metrics (M3.3)")
    class BackpressureAndMetrics {

        /** Creates a new test instance. */
        BackpressureAndMetrics() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @BeforeEach
        void assumeActiveRuntime() {
            assumeTrue(supportsActiveRuntime(),
                    "Skipped: implementation does not support active runtime");
            assumeTrue(metrics() != null,
                    "Skipped: implementation does not expose BusMetricsRecorder");
            assumeTrue(queueDepth() != null,
                    "Skipped: implementation does not expose queue depth control");
        }

        @Test
        @DisplayName("busMetrics record publish latency")
        void busMetricsRecordPublishLatency() throws SequenceConflictException {
            subscribeAndTrack("lat-sub", SubscriptionFilter.all(), false);

            publishAndNotify(TestEventFactory.draft());

            assertThat(metrics().publishLatencyRecorded())
                    .as("Publish-latency metric recorded at least once")
                    .isTrue();
            // Duration is non-negative by definition (Duration.between of clock instants).
            assertThat(metrics().lastPublishLatency())
                    .isNotNull()
                    .satisfies(d -> assertThat(d.isNegative()).isFalse());
        }

        @Test
        @DisplayName("publisher blocked count increments when queue depth above 5000")
        void publisherBlockedCountIncrementsAbove5000() throws SequenceConflictException {
            subscribeAndTrack("block-sub", SubscriptionFilter.all(), false);
            queueDepth().set(6000);

            publishAndNotify(TestEventFactory.draft());

            assertThat(metrics().publisherBlockedCount())
                    .as("publisherBlocked increments when queue depth > 5000")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("publisher blocked count NOT incremented when queue depth below 5000")
        void publisherBlockedCountNotIncrementedBelow5000() throws SequenceConflictException {
            subscribeAndTrack("nonblock-sub", SubscriptionFilter.all(), false);
            queueDepth().set(4000);

            publishAndNotify(TestEventFactory.draft());

            assertThat(metrics().publisherBlockedCount())
                    .as("publisherBlocked must NOT increment at depth 4000")
                    .isZero();
        }

        @Test
        @DisplayName("writer queue depth gauge sampled on notify")
        void writerQueueDepthGaugeSampledOnNotify() throws SequenceConflictException {
            subscribeAndTrack("depth-sub", SubscriptionFilter.all(), false);
            queueDepth().set(123);

            publishAndNotify(TestEventFactory.draft());

            assertThat(metrics().recordedDepths())
                    .as("Depth gauge sampled with the supplier's current value")
                    .contains(123);
        }

        @Test
        @DisplayName("publish does not block at depth 5000 (INV-BUS-02)")
        void publishDoesNotBlockAt5000() throws SequenceConflictException {
            subscribeAndTrack("nonblock-sub", SubscriptionFilter.all(), false);

            // Steady-state publish baseline at depth 0.
            queueDepth().set(0);
            publishAndNotify(TestEventFactory.draft());
            // High-pressure publish at depth above blocked threshold.
            queueDepth().set(8000);
            publishAndNotify(TestEventFactory.draft());

            // INV-BUS-02 contract: depth above threshold does NOT cause publish
            // to throw, block indefinitely, or alter its return semantics. Both
            // publishes completed and returned envelopes without error.
            assertThat(metrics().publishLatencyRecorded())
                    .as("Both publishes completed without blocking")
                    .isTrue();
            assertThat(metrics().publisherBlockedCount())
                    .as("Second publish observed depth above 5000 and recorded the counter")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("subscriber lag populated after delivery")
        void subscriberLagPopulatedAfterDelivery()
                throws SequenceConflictException, InterruptedException {
            CopyOnWriteArrayList<Long> delivered = new CopyOnWriteArrayList<>();
            Subscriber recorder = env -> delivered.add(env.globalPosition());

            bus().subscribeRuntime(
                    new SubscriberInfo("lag-sub", SubscriptionFilter.all(), false),
                    recorder);
            awaitMode("lag-sub", SubscriberMode.LIVE);

            EventEnvelope env = publishAndNotify(TestEventFactory.draft());

            // Wait briefly for the LIVE delivery loop to process.
            for (int i = 0; i < 50 && delivered.isEmpty(); i++) {
                Thread.sleep(40);
            }
            assertThat(delivered).contains(env.globalPosition());

            // Wait briefly for the lag metric to surface after delivery.
            for (int i = 0; i < 50 && metrics().lagRecordsFor("lag-sub").isEmpty(); i++) {
                Thread.sleep(40);
            }
            assertThat(metrics().lagRecordsFor("lag-sub"))
                    .as("Lag metric recorded after successful delivery")
                    .isNotEmpty();
            BusMetricsRecorder.LagRecord rec = metrics().lagRecordsFor("lag-sub").get(0);
            assertThat(rec.lagEvents()).isGreaterThanOrEqualTo(0L);
            assertThat(rec.lagMillis().isNegative()).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // BusMetricsRecorder — recording test fixture for AMD-43 §3.6.2 metrics
    // ──────────────────────────────────────────────────────────────────

    /**
     * Recording {@link BusMetrics} implementation for contract-test assertions.
     *
     * <p>Captures the seven canonical bus metric emissions in thread-safe
     * collections so Tier 10 tests can assert against them. Concrete contract
     * subclasses construct one in their reset hook and pass it into the bus's
     * production constructor.</p>
     */
    public static final class BusMetricsRecorder implements BusMetrics {

        private final AtomicInteger publisherBlocked = new AtomicInteger(0);
        private final java.util.List<Integer> depths =
                new CopyOnWriteArrayList<>();
        private final java.util.List<Duration> publishLatencies =
                new CopyOnWriteArrayList<>();
        private final Map<String, java.util.List<LagRecord>> lagBySubscriber =
                new ConcurrentHashMap<>();
        private final java.util.List<String> derivedWritesAccepted =
                new CopyOnWriteArrayList<>();
        private final java.util.List<String> derivedWritesParked =
                new CopyOnWriteArrayList<>();

        /** Public constructor — used by contract test subclasses. */
        public BusMetricsRecorder() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Override
        public void recordPublishLatency(Duration duration) {
            publishLatencies.add(duration);
        }

        @Override
        public void incrementPublisherBlocked() {
            publisherBlocked.incrementAndGet();
        }

        @Override
        public void recordWriterQueueDepth(int depth) {
            depths.add(depth);
        }

        @Override
        public void recordSubscriberLag(String subscriberId, long lagEvents,
                                        Duration lagMillis) {
            lagBySubscriber
                    .computeIfAbsent(subscriberId, k -> new CopyOnWriteArrayList<>())
                    .add(new LagRecord(lagEvents, lagMillis));
        }

        @Override
        public void recordDerivedWriteAccepted(String subscriberId) {
            derivedWritesAccepted.add(subscriberId);
        }

        @Override
        public void recordDerivedWriteParked(String subscriberId) {
            derivedWritesParked.add(subscriberId);
        }

        /** @return the count of publisherBlocked emissions. */
        public int publisherBlockedCount() {
            return publisherBlocked.get();
        }

        /** @return the recorded queue depth observations (in emission order). */
        public java.util.List<Integer> recordedDepths() {
            return java.util.List.copyOf(depths);
        }

        /** @return {@code true} if any publish-latency emission has occurred. */
        public boolean publishLatencyRecorded() {
            return !publishLatencies.isEmpty();
        }

        /** @return the most recent publish-latency emission, or {@code null}. */
        public Duration lastPublishLatency() {
            return publishLatencies.isEmpty() ? null
                    : publishLatencies.get(publishLatencies.size() - 1);
        }

        /** @param subscriberId the subscriber id
         *  @return lag records emitted for that subscriber (possibly empty). */
        public java.util.List<LagRecord> lagRecordsFor(String subscriberId) {
            return java.util.List.copyOf(
                    lagBySubscriber.getOrDefault(subscriberId, java.util.List.of()));
        }

        /** @return all derived-write accepted subscriber ids in emission order. */
        public java.util.List<String> derivedWritesAccepted() {
            return java.util.List.copyOf(derivedWritesAccepted);
        }

        /** @return all derived-write parked subscriber ids in emission order. */
        public java.util.List<String> derivedWritesParked() {
            return java.util.List.copyOf(derivedWritesParked);
        }

        /**
         * One recorded subscriber-lag emission.
         *
         * @param lagEvents the lag in events
         * @param lagMillis the lag duration
         */
        public record LagRecord(long lagEvents, Duration lagMillis) {}
    }
}
