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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FAILCHAN-FIX-1a — every silent drop point on the bus's delivery paths emits a
 * {@link DeliveryAnomaly} through the constructor-injected emitter BEFORE the
 * path returns or continues, and the emitter can never become a failure
 * channel of its own.
 *
 * <p><strong>BUS-ORDER-1 (2026-09-12, AMD-101 §2).</strong> The LIVE loop and
 * the TRANSITION drain no longer read the positions they are handed one at a
 * time: they page the store forward from the subscriber's in-memory cursor. A
 * page that comes back empty means "caught up" — the next wake or the bounded
 * idle tick reads again — so the FIX-1b per-position retry-and-suspend is
 * retired on both paths and its kinds ({@code LIVE_READ_EMPTY},
 * {@code LIVE_READ_EXHAUSTED}, {@code TRANSITION_READ_EMPTY},
 * {@code TRANSITION_READ_EXHAUSTED}) stay defined but are never emitted. What
 * remains on the LIVE path is {@code LIVE_READ_FAILED}: one anomaly per page
 * read that THREW, at {@code cursor + 1} (the first position the page would
 * have shown), and the next tick retries. {@code notifyEvent}'s own arms
 * ({@code NOTIFY_NOT_VISIBLE}, the unfiltered offer) are unchanged.</p>
 *
 * <p><strong>The read stub.</strong> {@link ScriptedReadStore} wraps the real
 * {@link InMemoryEventStore} and scripts the subscriber-thread PAGE reads —
 * {@code readFrom(afterPosition, batch)} issued on a virtual thread through the
 * synchronous {@link RecordingReadConnectionFactory}, i.e. the read-forward of
 * the LIVE loop and the TRANSITION drain — by {@code afterPosition} (the cursor
 * the read starts from). It also counts them, so a test can assert that the
 * bus paged from the cursor at all. {@code notifyEvent}'s own single-position
 * read runs on the publisher's platform thread and is scripted separately
 * ({@link ScriptedReadStore#emptyOnceForPublisher}).</p>
 *
 * <p>Time is the injected fixed {@link Clock} (LTD-09 / NO_DIRECT_TIME_ACCESS);
 * waiting is fixed-interval {@link Thread#sleep(long)} polling, never a clock
 * read.</p>
 */
@DisplayName("FIX-1a — every silent delivery drop emits a DeliveryAnomaly")
final class DeliveryAnomalyEmissionTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
    private static final String SUBSCRIBER_ID = "anomaly-sub";
    private static final long UNSEEN_POSITION = 999L;

    private InMemoryEventStore realStore;
    private ScriptedReadStore scriptedStore;
    private InMemoryCheckpointStore checkpointStore;
    private List<DeliveryAnomaly> anomalies;
    private List<Long> delivered;
    private InProcessEventBus bus;

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    DeliveryAnomalyEmissionTest() {
    }

    @BeforeEach
    void setUp() {
        realStore = new InMemoryEventStore(CLOCK);
        scriptedStore = new ScriptedReadStore(realStore);
        checkpointStore = new InMemoryCheckpointStore();
        anomalies = new CopyOnWriteArrayList<>();
        delivered = new CopyOnWriteArrayList<>();
        bus = newBus(anomalies::add);
    }

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.reset();
        }
    }

    // ── T1 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T1 — a LIVE page read that comes back empty (a visibility miss) emits nothing: "
            + "the next tick re-reads from the cursor and delivers P; the checkpoint reaches P")
    void liveVisibilityMiss_deliversOnNextTickWithoutAnomaly() throws Exception {
        subscribeAndAwaitLive(bus);
        long p1 = publishAndNotify(bus);
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        long p = p1 + 1;
        scriptedStore.emptyPageOnce(p1); // the read-forward from the cursor misses once
        assertThat(publishAndNotify(bus)).isEqualTo(p);

        awaitTrue(() -> delivered.contains(p), "P delivered by the re-read from the cursor");
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p, "the checkpoint reaching " + p);
        awaitTrue(() -> scriptedStore.pageReadsFrom(p1) >= 2,
                "the LIVE loop paging from the cursor (the empty page, then the real one)");
        assertThat(anomalies).extracting(DeliveryAnomaly::kind)
                .as("an empty page is caught-up, not a drop")
                .doesNotContain(DeliveryAnomaly.Kind.LIVE_READ_EMPTY,
                        DeliveryAnomaly.Kind.LIVE_READ_EXHAUSTED);
        assertThat(delivered.stream().filter(pos -> pos == p).count())
                .as("delivered exactly once").isEqualTo(1L);
    }

    // ── T2 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T2 — a LIVE page read that throws emits LIVE_READ_FAILED once, at cursor+1, with "
            + "'RuntimeException: boom'; the next tick retries and delivers P")
    void liveReadFailed_emitsOncePerFailedPage() throws Exception {
        subscribeAndAwaitLive(bus);
        long p1 = publishAndNotify(bus);
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        long p = p1 + 1;
        scriptedStore.throwPageOnce(p1, "boom"); // the read-forward from the cursor throws once
        assertThat(publishAndNotify(bus)).isEqualTo(p);

        DeliveryAnomaly anomaly = awaitAnomaly(DeliveryAnomaly.Kind.LIVE_READ_FAILED, p);
        assertThat(anomaly.subscriberId()).isEqualTo(SUBSCRIBER_ID);
        assertThat(anomaly.detail()).isEqualTo("RuntimeException: boom");
        assertThat(anomaly.timestamp()).isEqualTo(CLOCK.instant());

        awaitTrue(() -> delivered.contains(p), "P delivered by the next tick's read");
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p, "the checkpoint reaching " + p);
        assertThat(countAnomalies(DeliveryAnomaly.Kind.LIVE_READ_FAILED, p))
                .as("one anomaly per failed page, no per-position retry").isEqualTo(1L);
        assertThat(anomalies).extracting(DeliveryAnomaly::kind)
                .doesNotContain(DeliveryAnomaly.Kind.LIVE_READ_EXHAUSTED);
        assertThat(bus.subscriberInfo(SUBSCRIBER_ID).mode()).isEqualTo(SubscriberMode.LIVE);
    }

    // ── T3 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T3 — notifyEvent(P) on a position the store cannot see emits NOTIFY_NOT_VISIBLE(\"*\", P)")
    void notifyNotVisible_emits() {
        bus.notifyEvent(UNSEEN_POSITION);

        assertThat(anomalies).hasSize(1);
        DeliveryAnomaly anomaly = anomalies.get(0);
        assertThat(anomaly.kind()).isEqualTo(DeliveryAnomaly.Kind.NOTIFY_NOT_VISIBLE);
        assertThat(anomaly.subscriberId()).isEqualTo("*");
        assertThat(anomaly.globalPosition()).isEqualTo(UNSEEN_POSITION);
        assertThat(anomaly.detail()).isEqualTo("notifyEvent: no envelope at position");
        assertThat(anomaly.timestamp()).isEqualTo(CLOCK.instant());
    }

    // ── T4 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T4 — a throwing emitter never propagates out of notifyEvent or the LIVE loop")
    void emitterNeverThrows() throws Exception {
        AtomicInteger emitterCalls = new AtomicInteger();
        InProcessEventBus throwingBus = newBus(anomaly -> {
            emitterCalls.incrementAndGet();
            throw new IllegalStateException("emitter failure must be swallowed");
        });
        try {
            // (a) the publisher's path: the emitter throws inside notifyEvent.
            assertThatCode(() -> throwingBus.notifyEvent(UNSEEN_POSITION))
                    .doesNotThrowAnyException();
            assertThat(emitterCalls.get()).as("the emitter was invoked").isEqualTo(1);

            // (b) the subscriber's path: the emitter throws inside the LIVE loop and
            // the virtual thread survives — a later position is still delivered.
            subscribeAndAwaitLive(throwingBus);
            long p1 = publishAndNotify(throwingBus);
            awaitTrue(() -> delivered.contains(p1), "the baseline delivery of " + p1);

            long p = p1 + 1;
            scriptedStore.throwPageOnce(p1, "page read failure"); // the LIVE loop's read-forward throws once
            assertThat(publishAndNotify(throwingBus)).isEqualTo(p);
            awaitTrue(() -> emitterCalls.get() >= 2, "the LIVE loop's anomaly reaching the emitter");

            long next = publishAndNotify(throwingBus);
            awaitTrue(() -> delivered.contains(next),
                    "the subscriber's virtual thread surviving the throwing emitter");
        } finally {
            throwingBus.reset();
        }
    }

    // ── BUS-ORDER-1 — the read-forward retires the FIX-1b per-position retry-and-suspend ──

    @Test
    @DisplayName("T5 — two empty page reads then a real one deliver P exactly once and checkpoint it; "
            + "no anomaly of any kind; the subscriber stays LIVE")
    void liveVisibilityMissTwice_deliversOnceWithoutAnomaly() throws Exception {
        subscribeAndAwaitLive(bus);
        long p1 = publishAndNotify(bus);
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        long p = p1 + 1;
        scriptedStore.emptyPageTimes(p1, 2);
        assertThat(publishAndNotify(bus)).isEqualTo(p);

        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p,
                "the re-read delivering and checkpointing " + p);
        awaitTrue(() -> scriptedStore.pageReadsFrom(p1) >= 3,
                "the LIVE loop paging from the cursor (two empty pages, then the real one)");
        assertThat(delivered.stream().filter(pos -> pos == p).count())
                .as("delivered exactly once").isEqualTo(1L);
        assertThat(anomalies).as("an empty page is never an anomaly").isEmpty();
        assertThat(bus.subscriberInfo(SUBSCRIBER_ID).mode()).isEqualTo(SubscriberMode.LIVE);
    }

    @Test
    @DisplayName("T6 — page reads that stay empty never SUSPEND: no LIVE_READ_EXHAUSTED, mode LIVE, "
            + "the checkpoint never past P, P not delivered while the store shows nothing")
    void liveEmptyPages_neverSuspend() throws Exception {
        subscribeAndAwaitLive(bus);
        long p1 = publishAndNotify(bus);
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        long p = p1 + 1;
        scriptedStore.emptyPageForever(p1);
        assertThat(publishAndNotify(bus)).isEqualTo(p);

        awaitTrue(() -> scriptedStore.pageReadsFrom(p1) >= 3,
                "the idle tick re-reading from the cursor while the store shows nothing");
        assertThat(anomalies).as("emptiness is never an anomaly and never a SUSPEND").isEmpty();
        assertThat(bus.subscriberInfo(SUBSCRIBER_ID).mode()).isEqualTo(SubscriberMode.LIVE);
        assertThat(checkpointStore.readCheckpoint(SUBSCRIBER_ID)).isEqualTo(p1);
        assertThat(delivered).doesNotContain(p);
    }

    @Test
    @DisplayName("T7 — notifyEvent(P) on a page the publisher cannot see still offers P to the LIVE "
            + "subscriber; when the store shows P it is delivered and checkpointed")
    void notifyNotVisible_stillOffersToLive() throws Exception {
        subscribeAndAwaitLive(bus);
        long p1 = publishAndNotify(bus);
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        long p = p1 + 1;
        scriptedStore.emptyOnceForPublisher(p);   // notifyEvent's own read misses …
        scriptedStore.emptyPageOnce(p1);          // … and so does the LIVE loop's first read-forward
        assertThat(publishAndNotify(bus)).isEqualTo(p);

        DeliveryAnomaly notVisible = findAnomaly(DeliveryAnomaly.Kind.NOTIFY_NOT_VISIBLE, p)
                .orElseThrow(() -> new AssertionError("NOTIFY_NOT_VISIBLE not emitted for " + p));
        assertThat(notVisible.subscriberId()).isEqualTo("*");
        awaitTrue(() -> delivered.contains(p), "P delivered through the unfiltered offer");
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p, "the checkpoint reaching " + p);
    }

    // ── FIX-1b — the ReplayTransitionIT mechanism: TRANSITION deliveries checkpoint ──

    @Test
    @DisplayName("T-R1 — positions delivered by the TRANSITION drain advance the checkpoint (the "
            + "ReplayTransitionIT phase-1 stall: a burst that ends inside TRANSITION left the checkpoint "
            + "at the REPLAY tail while every event had been delivered)")
    void transitionDeliveries_advanceCheckpoint() throws Exception {
        long first = realStore.publishRoot(TestEventFactory.draft()).globalPosition();
        int burst = 40;
        // The REPLAY driver's tail read (afterPosition == first) runs the publisher's whole
        // burst and then reports the tail as it stood when the read began: every burst
        // position is notified while the subscriber is in REPLAY and lands in the replay
        // window queue, to be delivered by the TRANSITION drain — one thread, real order.
        scriptedStore.onPageReadRunThenEmpty(first, () -> {
            for (int i = 0; i < burst; i++) {
                try {
                    publishAndNotify(bus);
                } catch (SequenceConflictException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        subscribeAndAwaitLive(bus);

        long last = first + burst;
        awaitTrue(() -> delivered.size() == burst + 1,
                "every position delivered (REPLAY " + first + ", TRANSITION " + (first + 1) + ".." + last + ")");
        assertThat(delivered).containsExactlyElementsOf(
                LongStream.rangeClosed(first, last).boxed().toList());
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == last,
                "the checkpoint reaching the last TRANSITION-delivered position " + last);
        assertThat(anomalies).isEmpty();
    }

    @Test
    @DisplayName("T-R2 — a page read that throws during the TRANSITION drain SUSPENDs the subscriber "
            + "honestly; the checkpoint stays at the last delivery; no TRANSITION_READ_* kind is emitted")
    void transitionPageReadFailure_suspendsHonestly() throws Exception {
        long first = realStore.publishRoot(TestEventFactory.draft()).globalPosition();
        scriptedStore.onPageReadRunThenEmpty(first, () -> {
            try {
                publishAndNotify(bus);
                publishAndNotify(bus);
            } catch (SequenceConflictException e) {
                throw new IllegalStateException(e);
            }
        });
        // The drain's read-forward from the cursor (afterPosition == first) throws.
        scriptedStore.throwPageOnce(first, "drain read failure");

        bus.subscribeRuntime(
                new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                event -> delivered.add(event.globalPosition()));

        awaitTrue(() -> bus.subscriberInfo(SUBSCRIBER_ID).mode() == SubscriberMode.SUSPENDED,
                "the honest SUSPEND out of TRANSITION");
        assertThat(delivered).as("REPLAY delivered the first position; the drain delivered nothing")
                .containsExactly(first);
        assertThat(checkpointStore.readCheckpoint(SUBSCRIBER_ID))
                .as("the checkpoint rests at REPLAY's tail write")
                .isEqualTo(first);
        assertThat(anomalies).extracting(DeliveryAnomaly::kind)
                .as("the retired per-position kinds are never emitted")
                .doesNotContain(DeliveryAnomaly.Kind.TRANSITION_READ_EMPTY,
                        DeliveryAnomaly.Kind.TRANSITION_READ_EXHAUSTED);
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private long countAnomalies(DeliveryAnomaly.Kind kind, long position) {
        return anomalies.stream()
                .filter(anomaly -> anomaly.kind() == kind && anomaly.globalPosition() == position)
                .count();
    }

    private InProcessEventBus newBus(Consumer<DeliveryAnomaly> emitter) {
        return new InProcessEventBus(scriptedStore, checkpointStore, CLOCK,
                new RecordingReadConnectionFactory(), BusMetrics.noop(), () -> 0,
                EventBusConfig.HOME_DEFAULT, emitter);
    }

    private void subscribeAndAwaitLive(InProcessEventBus target) throws InterruptedException {
        target.subscribeRuntime(
                new SubscriberInfo(SUBSCRIBER_ID, SubscriptionFilter.all(), false),
                event -> delivered.add(event.globalPosition()));
        awaitTrue(() -> target.subscriberInfo(SUBSCRIBER_ID).mode() == SubscriberMode.LIVE,
                "subscriber '" + SUBSCRIBER_ID + "' reaching LIVE");
    }

    /** Persists one event into the real store and notifies the bus — the production flow. */
    private long publishAndNotify(InProcessEventBus target)
            throws SequenceConflictException {
        EventEnvelope envelope = realStore.publishRoot(TestEventFactory.draft());
        target.notifyEvent(envelope.globalPosition());
        return envelope.globalPosition();
    }

    private DeliveryAnomaly awaitAnomaly(DeliveryAnomaly.Kind kind, long position)
            throws InterruptedException {
        awaitTrue(() -> findAnomaly(kind, position).isPresent(),
                kind + " at position " + position);
        return findAnomaly(kind, position).orElseThrow();
    }

    private Optional<DeliveryAnomaly> findAnomaly(DeliveryAnomaly.Kind kind, long position) {
        return anomalies.stream()
                .filter(anomaly -> anomaly.kind() == kind
                        && anomaly.globalPosition() == position)
                .findFirst();
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

    // ── The scripted read store ─────────────────────────────────────────────

    /**
     * Delegating {@link EventStore} whose subscriber-thread PAGE reads (the
     * read-forward from the cursor, issued on a VIRTUAL thread through the
     * synchronous read executor) follow a per-{@code afterPosition} script and
     * are counted; {@code notifyEvent}'s single-position read on the publisher's
     * platform thread follows its own script; every other read passes through
     * to the real store. Guarded by a {@link ReentrantLock} (LTD-11).
     */
    private static final class ScriptedReadStore implements EventStore {

        private enum Response { EMPTY, THROW, REAL }

        private final EventStore delegate;
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<Long, Deque<Response>> publisherQueued = new HashMap<>();
        private final Map<Long, Deque<Response>> pageQueued = new HashMap<>();
        private final Map<Long, Response> pageSteady = new HashMap<>();
        private final Map<Long, String> pageThrowMessages = new HashMap<>();
        private final Map<Long, Long> pageReads = new HashMap<>();
        private final Map<Long, Runnable> pageHooks = new HashMap<>();

        ScriptedReadStore(EventStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /** The FIRST subscriber-thread page read from {@code afterPosition} is empty; later reads are real. */
        void emptyPageOnce(long afterPosition) {
            emptyPageTimes(afterPosition, 1);
        }

        /** The first {@code n} subscriber-thread page reads from {@code afterPosition} are empty; later reads are real. */
        void emptyPageTimes(long afterPosition, int n) {
            lock.lock();
            try {
                Deque<Response> script = pageQueued.computeIfAbsent(afterPosition, ignored -> new ArrayDeque<>());
                for (int i = 0; i < n; i++) {
                    script.add(Response.EMPTY);
                }
            } finally {
                lock.unlock();
            }
        }

        /** Every subscriber-thread page read from {@code afterPosition} returns an empty page. */
        void emptyPageForever(long afterPosition) {
            lock.lock();
            try {
                pageSteady.put(afterPosition, Response.EMPTY);
            } finally {
                lock.unlock();
            }
        }

        /** The FIRST subscriber-thread page read from {@code afterPosition} throws {@code RuntimeException(message)}. */
        void throwPageOnce(long afterPosition, String message) {
            lock.lock();
            try {
                pageQueued.computeIfAbsent(afterPosition, ignored -> new ArrayDeque<>()).add(Response.THROW);
                pageThrowMessages.put(afterPosition, message);
            } finally {
                lock.unlock();
            }
        }

        /** How many subscriber-thread page reads started from {@code afterPosition} (scripted or real). */
        long pageReadsFrom(long afterPosition) {
            lock.lock();
            try {
                return pageReads.getOrDefault(afterPosition, 0L);
            } finally {
                lock.unlock();
            }
        }

        /**
         * The FIRST PUBLISHER-thread single-position read of {@code position} is empty
         * — {@code notifyEvent}'s own read misses while the store already holds the
         * envelope (the subscriber's read-forward is scripted separately).
         */
        void emptyOnceForPublisher(long position) {
            lock.lock();
            try {
                publisherQueued.computeIfAbsent(position, ignored -> new ArrayDeque<>())
                        .add(Response.EMPTY);
            } finally {
                lock.unlock();
            }
        }

        /**
         * The next subscriber-thread PAGE read from {@code afterPosition} (the
         * REPLAY driver's {@code readFrom(afterPosition, 500)}) runs {@code hook}
         * and then returns an EMPTY page — the tail as it stood when the read
         * began. The hook is the concurrent publisher, executed inside the read on
         * the one thread, so the interleaving is real and deterministic (the
         * FAILCHAN §10-O pattern). A hook outranks a page script at the same
         * position for that one read.
         */
        void onPageReadRunThenEmpty(long afterPosition, Runnable hook) {
            lock.lock();
            try {
                pageHooks.put(afterPosition, hook);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            boolean virtual = Thread.currentThread().isVirtual();
            if (maxCount == 1) {
                if (!virtual && nextPublisherResponse(afterPosition + 1) == Response.EMPTY) {
                    return new EventPage(List.of(), afterPosition, false);
                }
                return delegate.readFrom(afterPosition, maxCount);
            }
            if (virtual) {
                countPageRead(afterPosition);
                Runnable hook = takePageHook(afterPosition);
                if (hook != null) {
                    hook.run();
                    return new EventPage(List.of(), afterPosition, false);
                }
                switch (nextPageResponse(afterPosition)) {
                    case EMPTY -> {
                        return new EventPage(List.of(), afterPosition, false);
                    }
                    case THROW -> throw new RuntimeException(pageThrowMessageFor(afterPosition));
                    case REAL -> {
                        // fall through to the delegate
                    }
                }
            }
            return delegate.readFrom(afterPosition, maxCount);
        }

        private void countPageRead(long afterPosition) {
            lock.lock();
            try {
                pageReads.merge(afterPosition, 1L, Long::sum);
            } finally {
                lock.unlock();
            }
        }

        private Response nextPageResponse(long afterPosition) {
            lock.lock();
            try {
                Deque<Response> script = pageQueued.get(afterPosition);
                if (script != null && !script.isEmpty()) {
                    return script.poll();
                }
                return pageSteady.getOrDefault(afterPosition, Response.REAL);
            } finally {
                lock.unlock();
            }
        }

        private Response nextPublisherResponse(long position) {
            lock.lock();
            try {
                Deque<Response> script = publisherQueued.get(position);
                if (script != null && !script.isEmpty()) {
                    return script.poll();
                }
                return Response.REAL;
            } finally {
                lock.unlock();
            }
        }

        private Runnable takePageHook(long afterPosition) {
            lock.lock();
            try {
                return pageHooks.remove(afterPosition);
            } finally {
                lock.unlock();
            }
        }

        private String pageThrowMessageFor(long afterPosition) {
            lock.lock();
            try {
                return pageThrowMessages.getOrDefault(afterPosition, "scripted page read failure");
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
