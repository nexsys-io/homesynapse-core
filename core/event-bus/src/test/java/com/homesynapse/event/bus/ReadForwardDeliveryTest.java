/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.test.InMemoryCheckpointStore;
import com.homesynapse.event.bus.test.RecordingReadConnectionFactory;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.event.test.TestEventFactory;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUS-ORDER-1 — the AMD-101 §2 invariant: every position whose event matches a
 * LIVE subscriber's filter is delivered to that subscriber exactly once, in
 * position order, regardless of the order in which notifications arrive.
 *
 * <p>T1 pins the LIVE path (two notifications out of position order); T2 and
 * T2b pin the TRANSITION → LIVE flip (appends during REPLAY's tail read, and an
 * append between the drain's page read and the CAS to LIVE); T3 pins the
 * bounded idle tick (an append with no notification at all). The persisted
 * checkpoint is observed through a recording store: it must never regress.</p>
 *
 * <p>The store double scripts only the subscriber-thread PAGE reads it needs for
 * the flip scenarios (the FAILCHAN §10-O pattern — the concurrent publisher runs
 * INSIDE the read, on the one thread, so the interleaving is real and
 * deterministic); every other read passes through to the real
 * {@link InMemoryEventStore}. Time is the injected fixed {@link Clock}
 * (NO_DIRECT_TIME_ACCESS); waiting is fixed-interval {@link Thread#sleep(long)}
 * polling, never a clock read.</p>
 */
@DisplayName("BUS-ORDER-1 — LIVE delivery is the read-forward from the cursor; the notification is a wake hint (AMD-101 §2)")
final class ReadForwardDeliveryTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);
    private static final String SUBSCRIBER_ID = "read-forward-sub";

    /**
     * T3's idle tick — small, so a lost wake is repaired inside the test's
     * budget of {@code 2 × tick}; every other test runs on the same bus and
     * only ever observes wakes.
     */
    static final Duration TEST_IDLE_TICK = Duration.ofMillis(200);

    private InMemoryEventStore realStore;
    private ScriptedStore scriptedStore;
    private RecordingCheckpointStore checkpointStore;
    private List<DeliveryAnomaly> anomalies;
    private List<Long> delivered;
    private AtomicInteger caughtUp;
    private InProcessEventBus bus;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    ReadForwardDeliveryTest() {
    }

    @BeforeEach
    void setUp() {
        realStore = new InMemoryEventStore(CLOCK);
        scriptedStore = new ScriptedStore(realStore);
        checkpointStore = new RecordingCheckpointStore();
        anomalies = new CopyOnWriteArrayList<>();
        delivered = new CopyOnWriteArrayList<>();
        caughtUp = new AtomicInteger();
        bus = new InProcessEventBus(scriptedStore, checkpointStore, CLOCK,
                new RecordingReadConnectionFactory(), BusMetrics.noop(), () -> 0,
                new EventBusConfig(
                        EventBusConfig.HOME_DEFAULT.replayQueueCapacity(),
                        EventBusConfig.HOME_DEFAULT.publisherBlockedDepthThreshold(),
                        EventBusConfig.HOME_DEFAULT.liveReadBatch(),
                        TEST_IDLE_TICK),
                anomalies::add);
    }

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.reset();
        }
    }

    // ── T1 — the LIVE path ──────────────────────────────────────────────────

    @Test
    @DisplayName("T1 — notifications out of position order: [P, P+1] delivered in order, once; "
            + "the persisted checkpoint ends at P+1 and never regresses; NOTIFY_SKIPPED_LIVE never emitted")
    void notifiesOutOfOrder_deliverInPositionOrderOnce() throws Exception {
        subscribeAndAwaitLive();
        long p1 = publishAndNotify();
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        // Two appends whose notifications have not landed yet — two publishers.
        long p = publishOnly();
        long pNext = publishOnly();
        assertThat(pNext).isEqualTo(p + 1);

        // The LATER position's notification lands first, and the interleaving is
        // forced: the subscriber has acted on it (its checkpoint is at P+1) before
        // P's notification arrives. At 5f918c7 that makes P's notification a
        // NOTIFY_SKIPPED_LIVE and P a silent drop; under the read-forward the
        // first wake delivers P and P+1 in order and the second wake finds nothing.
        bus.notifyEvent(pNext);
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == pNext,
                "the checkpoint reaching " + pNext);
        bus.notifyEvent(p);
        settle();

        assertThat(delivered)
                .as("every matching position delivered exactly once, in position order")
                .containsExactly(p1, p, pNext);
        assertThat(checkpointStore.writes())
                .as("the persisted checkpoint is monotonic — it follows the cursor")
                .isSorted();
        assertThat(checkpointStore.readCheckpoint(SUBSCRIBER_ID)).isEqualTo(pNext);
        // T4 — green by construction once the persisted-checkpoint guard is gone:
        // a wake hint is never skipped, so the tripwire kind is never emitted.
        assertThat(anomalies).extracting(DeliveryAnomaly::kind)
                .as("a wake hint is never skipped")
                .doesNotContain(DeliveryAnomaly.Kind.NOTIFY_SKIPPED_LIVE);
    }

    // ── T2 — the TRANSITION → LIVE flip ─────────────────────────────────────

    @Test
    @DisplayName("T2 — appends during REPLAY's tail read, notified in REVERSE order, are delivered "
            + "exactly once and in position order across the flip; the checkpoint never regresses; onCaughtUp once")
    void flip_appendsDuringReplayTail_deliveredOnceInOrder() throws Exception {
        long first = publishOnly();
        // The REPLAY driver's tail read (afterPosition == first) runs the
        // concurrent publishers and then reports the tail as it stood when the
        // read began: three appends land while the subscriber is in REPLAY, and
        // their notifications arrive in REVERSE position order.
        List<Long> appended = new CopyOnWriteArrayList<>();
        scriptedStore.onPageReadRunThenEmpty(first, () -> {
            for (int i = 0; i < 3; i++) {
                appended.add(publishOnly());
            }
            for (int i = appended.size() - 1; i >= 0; i--) {
                bus.notifyEvent(appended.get(i));
            }
        });

        subscribeAndAwaitLive();
        long last = first + 3;
        awaitTrue(() -> delivered.size() >= 4, "the four positions reaching the subscriber");
        settle();

        assertThat(delivered)
                .as("REPLAY delivered %d; the flip delivered %d..%d once each, in order", first, first + 1, last)
                .containsExactly(first, first + 1, first + 2, last);
        assertThat(checkpointStore.writes())
                .as("the persisted checkpoint is monotonic across the flip")
                .isSorted();
        assertThat(checkpointStore.readCheckpoint(SUBSCRIBER_ID)).isEqualTo(last);
        assertThat(caughtUp.get()).as("onCaughtUp is single-shot (AMD-42 §3.4.3)").isEqualTo(1);
        assertThat(anomalies).isEmpty();
    }

    @Test
    @DisplayName("T2b — an append that lands after the drain's page read and before the CAS to LIVE "
            + "is delivered exactly once (the duplicate option (a) feared)")
    void flip_appendBetweenDrainReadAndCas_deliveredOnce() throws Exception {
        long first = publishOnly();
        scriptedStore.onPageReadRunThenEmpty(first, () -> {
            publishAndNotify(); // one append during REPLAY's tail read, in order
            // Arm: the NEXT subscriber-thread read (the drain's) runs the real
            // read and THEN a publisher appends + notifies, before the page is
            // handed back — the position is in the store, not in the page.
            scriptedStore.onNextSubscriberReadRunAfterReal(this::publishAndNotify);
        });

        subscribeAndAwaitLive();
        awaitTrue(() -> delivered.size() >= 3, "the three positions reaching the subscriber");
        settle();

        assertThat(delivered)
                .as("the position appended inside the flip is delivered once, by whichever phase reaches it")
                .containsExactly(first, first + 1, first + 2);
        assertThat(checkpointStore.writes()).isSorted();
        assertThat(checkpointStore.readCheckpoint(SUBSCRIBER_ID)).isEqualTo(first + 2);
        assertThat(caughtUp.get()).isEqualTo(1);
        assertThat(anomalies).isEmpty();
    }

    // ── T3 — the bounded idle tick ──────────────────────────────────────────

    @Test
    @DisplayName("T3 — an append with NO notification is delivered within 2 × liveIdleTick "
            + "(a lost wake costs one idle tick, never a stalled run)")
    void appendWithoutNotify_deliveredWithinTwoTicks() throws Exception {
        subscribeAndAwaitLive();
        long p1 = publishAndNotify();
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        long p = publishOnly(); // the wake is lost — nothing calls notifyEvent(p)

        awaitWithin(() -> delivered.contains(p), TEST_IDLE_TICK.multipliedBy(2),
                "delivery of " + p + " without a notification");
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p,
                "the checkpoint reaching " + p);
        assertThat(delivered).containsExactly(p1, p);
        assertThat(anomalies).isEmpty();
    }

    // ── T5 — the wake permit and the read executor's own park ───────────────

    @Test
    @DisplayName("T5 — a wake that lands while the loop's own page read is in flight (its unpark permit "
            + "spent by the read executor's Future.get) is honored at once, not on the idle tick")
    void wakeDuringInFlightRead_honoredBeforeTick() throws Exception {
        // Production's read executor runs the SQLite read on a platform thread and
        // the subscriber's virtual thread waits on a Future — a park of its own. On
        // JDK 21 an unpark that lands during that park is spent by it (the JDK
        // probe of 2026-09-12: parkNanos after a completed Future.get blocked its
        // full 2 s whenever an unpark had landed while parked in get()), so the
        // hint queue — not the permit — must be what keeps the loop from parking.
        // The tick is 2 s so a tick-repaired delivery is unmistakable; the wake
        // must beat it by an order of magnitude.
        FutureBackedReadFactory reads = new FutureBackedReadFactory();
        InProcessEventBus futureBus = new InProcessEventBus(scriptedStore, checkpointStore, CLOCK,
                reads, BusMetrics.noop(), () -> 0,
                new EventBusConfig(
                        EventBusConfig.HOME_DEFAULT.replayQueueCapacity(),
                        EventBusConfig.HOME_DEFAULT.publisherBlockedDepthThreshold(),
                        EventBusConfig.HOME_DEFAULT.liveReadBatch(),
                        Duration.ofSeconds(2)),
                anomalies::add);
        try {
            futureBus.subscribeRuntime(
                    new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                    event -> delivered.add(event.globalPosition()));
            awaitTrue(() -> futureBus.subscriberInfo(SUBSCRIBER_ID).mode() == SubscriberMode.LIVE,
                    "subscriber '" + SUBSCRIBER_ID + "' reaching LIVE");
            long p1 = publishOnly();
            futureBus.notifyEvent(p1);
            awaitTrue(() -> delivered.contains(p1), "the baseline delivery of " + p1);

            // Hold the NEXT read after its snapshot is taken, so the subscriber's
            // virtual thread sits in Future.get() while the append and its wake land.
            CountDownLatch readTaken = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            reads.holdNextRead(readTaken, release);
            futureBus.notifyEvent(p1); // a redundant hint: the loop wakes and reads from its cursor
            assertThat(readTaken.await(5, TimeUnit.SECONDS)).as("a page read in flight").isTrue();

            long p = publishOnly();
            futureBus.notifyEvent(p); // the wake: the hint is queued; the permit lands on the future's park
            Thread.sleep(50L);        // the thread is parked again before the read completes (the probe's shape)
            release.countDown();      // the stale page (without P) is handed back

            awaitWithin(() -> delivered.contains(p), Duration.ofMillis(500),
                    "delivery of " + p + " on its wake, not on the 2 s idle tick");
            assertThat(delivered).containsExactly(p1, p);
        } finally {
            futureBus.reset();
            reads.shutdown();
        }
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private void subscribeAndAwaitLive() throws InterruptedException {
        bus.subscribeRuntime(
                new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                new Subscriber() {
                    @Override
                    public void onEvent(EventEnvelope event) {
                        delivered.add(event.globalPosition());
                    }

                    @Override
                    public void onCaughtUp() {
                        caughtUp.incrementAndGet();
                    }
                });
        awaitTrue(() -> bus.subscriberInfo(SUBSCRIBER_ID).mode() == SubscriberMode.LIVE,
                "subscriber '" + SUBSCRIBER_ID + "' reaching LIVE");
    }

    /** Appends one event to the real store WITHOUT notifying the bus. */
    private long publishOnly() {
        try {
            return realStore.publishRoot(TestEventFactory.draft()).globalPosition();
        } catch (SequenceConflictException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Appends one event and notifies the bus — the production flow. */
    private long publishAndNotify() {
        long position = publishOnly();
        bus.notifyEvent(position);
        return position;
    }

    /** Fixed 50 ms polling, 5 s budget — no direct time source (NO_DIRECT_TIME_ACCESS). */
    private static void awaitTrue(BooleanSupplier condition, String what)
            throws InterruptedException {
        for (int poll = 0; poll < 100; poll++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("timed out awaiting " + what);
    }

    /** Fixed 10 ms polling inside the given budget — the T3 bound, no clock read. */
    private static void awaitWithin(BooleanSupplier condition, Duration budget, String what)
            throws InterruptedException {
        long polls = Math.max(1L, budget.toMillis() / 10L);
        for (long poll = 0; poll < polls; poll++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("timed out awaiting " + what + " within " + budget.toMillis() + " ms");
    }

    /** A short fixed settle so a late (wrong) delivery would be visible to a negative assert. */
    private static void settle() throws InterruptedException {
        Thread.sleep(300L);
    }

    // ── The recording checkpoint store ──────────────────────────────────────

    /**
     * {@link InMemoryCheckpointStore} plus the sequence of every write, so a
     * regression of the persisted checkpoint is an assertion, not a reading.
     */
    private static final class RecordingCheckpointStore implements CheckpointStore {

        private final InMemoryCheckpointStore delegate = new InMemoryCheckpointStore();
        private final List<Long> writes = new CopyOnWriteArrayList<>();

        RecordingCheckpointStore() {
        }

        @Override
        public long readCheckpoint(String subscriberId) {
            return delegate.readCheckpoint(subscriberId);
        }

        @Override
        public void writeCheckpoint(String subscriberId, long globalPosition) {
            writes.add(globalPosition);
            delegate.writeCheckpoint(subscriberId, globalPosition);
        }

        List<Long> writes() {
            return List.copyOf(writes);
        }
    }

    // ── The future-backed read executor ─────────────────────────────────────

    /**
     * A {@link SubscriberReadConnectionFactory} shaped like production's
     * ({@code SqliteSubscriberReadExecutor}): every read runs on ONE platform
     * thread and the calling virtual thread waits on a {@link Future} — so an
     * unpark aimed at the subscriber's loop while a read is in flight is spent by
     * the future's own park (AMD-26/27's executor shape). {@link #holdNextRead}
     * parks the next read AFTER its result is taken, until released.
     */
    private static final class FutureBackedReadFactory implements SubscriberReadConnectionFactory {

        private final ExecutorService platform = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "test-read-platform");
            thread.setDaemon(true);
            return thread;
        });
        private final ReentrantLock lock = new ReentrantLock();
        private CountDownLatch readTaken;
        private CountDownLatch release;

        FutureBackedReadFactory() {
        }

        /** The next read signals {@code readTaken} once its result is in hand, then waits for {@code release}. */
        void holdNextRead(CountDownLatch readTaken, CountDownLatch release) {
            lock.lock();
            try {
                this.readTaken = readTaken;
                this.release = release;
            } finally {
                lock.unlock();
            }
        }

        private CountDownLatch[] takeHold() {
            lock.lock();
            try {
                if (readTaken == null) {
                    return null;
                }
                CountDownLatch[] hold = {readTaken, release};
                readTaken = null;
                release = null;
                return hold;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public SubscriberReadExecutor create(String subscriberId) {
            return new SubscriberReadExecutor() {
                @Override
                public <T> T executeRead(Callable<T> task) throws Exception {
                    CountDownLatch[] hold = takeHold();
                    Future<T> future = platform.submit(() -> {
                        T result = task.call();
                        if (hold != null) {
                            hold[0].countDown();
                            hold[1].await();
                        }
                        return result;
                    });
                    try {
                        return future.get();
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof Exception cause) {
                            throw cause;
                        }
                        throw e;
                    }
                }

                @Override
                public void close() {
                    // the platform thread is shared; shutdown() releases it
                }
            };
        }

        void shutdown() {
            platform.shutdownNow();
        }
    }

    // ── The scripted store ──────────────────────────────────────────────────

    /**
     * Delegating {@link EventStore} that scripts subscriber-thread PAGE reads
     * (issued on a virtual thread through the synchronous
     * {@link RecordingReadConnectionFactory}); every other read passes through.
     * Guarded by a {@link ReentrantLock} (LTD-11).
     */
    private static final class ScriptedStore implements EventStore {

        private final EventStore delegate;
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<Long, Runnable> tailHooks = new HashMap<>();
        private Runnable armedAfterReal;

        ScriptedStore(EventStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /**
         * The next subscriber-thread PAGE read at {@code afterPosition} (the
         * REPLAY driver's tail read) runs {@code hook} and then returns an EMPTY
         * page — the tail as it stood when the read began.
         */
        void onPageReadRunThenEmpty(long afterPosition, Runnable hook) {
            lock.lock();
            try {
                tailHooks.put(afterPosition, hook);
            } finally {
                lock.unlock();
            }
        }

        /**
         * The next subscriber-thread read of ANY shape runs the real read, then
         * {@code hook}, then returns the real read's result — a publisher acting
         * after the page was taken and before it is handed back. Consumed once;
         * reads the hook itself issues see no hook.
         */
        void onNextSubscriberReadRunAfterReal(Runnable hook) {
            lock.lock();
            try {
                armedAfterReal = hook;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            if (Thread.currentThread().isVirtual()) {
                Runnable armed = takeArmed();
                if (armed != null) {
                    EventPage page = delegate.readFrom(afterPosition, maxCount);
                    armed.run();
                    return page;
                }
                if (maxCount != 1) {
                    Runnable hook = takeTailHook(afterPosition);
                    if (hook != null) {
                        hook.run();
                        return new EventPage(List.of(), afterPosition, false);
                    }
                }
            }
            return delegate.readFrom(afterPosition, maxCount);
        }

        private Runnable takeArmed() {
            lock.lock();
            try {
                Runnable armed = armedAfterReal;
                armedAfterReal = null;
                return armed;
            } finally {
                lock.unlock();
            }
        }

        private Runnable takeTailHook(long afterPosition) {
            lock.lock();
            try {
                return tailHooks.remove(afterPosition);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public EventPage readBySubject(SubjectRef subject, long afterSequence, int maxCount) {
            return delegate.readBySubject(subject, afterSequence, maxCount);
        }

        @Override
        public List<EventEnvelope> readByCorrelation(Ulid correlationId) {
            return delegate.readByCorrelation(correlationId);
        }

        @Override
        public EventPage readByType(String eventType, long afterPosition, int maxCount) {
            return delegate.readByType(eventType, afterPosition, maxCount);
        }

        @Override
        public EventPage readByTimeRange(Instant from, Instant to, long afterPosition,
                                         int maxCount) {
            return delegate.readByTimeRange(from, to, afterPosition, maxCount);
        }

        @Override
        public long latestPosition() {
            return delegate.latestPosition();
        }
    }
}
