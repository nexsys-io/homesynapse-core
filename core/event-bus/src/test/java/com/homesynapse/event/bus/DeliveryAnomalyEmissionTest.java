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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FAILCHAN-FIX-1a — every silent drop point on the bus's delivery paths emits a
 * {@link DeliveryAnomaly} through the constructor-injected emitter BEFORE the
 * path returns or continues, and the emitter can never become a failure
 * channel of its own.
 *
 * <p>The four drop points at {@code dc3328b}: {@code notifyEvent}'s empty page
 * ({@code NOTIFY_NOT_VISIBLE}), {@code liveLoop}'s empty page
 * ({@code LIVE_READ_EMPTY}), {@code liveLoop}'s read exception
 * ({@code LIVE_READ_FAILED}) and {@code drainAndPromote}'s empty page
 * ({@code TRANSITION_READ_EMPTY}). The first three are driven here; the
 * TRANSITION arm shares the emitter contract and is reached only through a
 * REPLAY/TRANSITION race the unit harness cannot schedule deterministically.</p>
 *
 * <p><strong>The read stub.</strong> {@link ScriptedReadStore} wraps the real
 * {@link InMemoryEventStore} and scripts ONLY the subscriber-thread reads —
 * single-position reads ({@code maxCount == 1}) issued on a virtual thread,
 * i.e. the LIVE loop's {@code readExecutor().executeRead(...)} through the
 * synchronous {@link RecordingReadConnectionFactory}. {@code notifyEvent}'s own
 * read of the same position runs on the publisher's platform thread and passes
 * through, so the position IS offered to the subscriber and the LIVE loop's
 * read is the one that misses — the S4 shape of the grounding audit.</p>
 *
 * <p>The assertions are chosen to hold under FIX-1a (a drop is skipped) AND
 * under FIX-1b (a drop is retried, then an honest SUSPEND): the anomaly is
 * emitted with the subscriber id and the position, the checkpoint never
 * advances to the undelivered position, and the loop survives a throwing
 * emitter. FIX-1b's retry/suspend semantics get their own pins (T5–T7).</p>
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
    @DisplayName("T1 — a LIVE read that returns an empty page emits LIVE_READ_EMPTY(subscriber, P) "
            + "and the checkpoint never advances to P")
    void liveReadEmpty_emits() throws Exception {
        subscribeAndAwaitLive(bus);
        long p1 = publishAndNotify(bus);
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        long p = p1 + 1;
        scriptedStore.emptyForever(p);
        assertThat(publishAndNotify(bus)).isEqualTo(p);

        DeliveryAnomaly anomaly = awaitAnomaly(DeliveryAnomaly.Kind.LIVE_READ_EMPTY, p);
        assertThat(anomaly.subscriberId()).isEqualTo(SUBSCRIBER_ID);
        assertThat(anomaly.timestamp()).isEqualTo(CLOCK.instant());
        assertThat(anomaly.detail()).isNotBlank();

        settle();
        assertThat(checkpointStore.readCheckpoint(SUBSCRIBER_ID))
                .as("the checkpoint never advances to an undelivered position")
                .isEqualTo(p1);
        assertThat(delivered).as("P was never delivered").doesNotContain(p);
    }

    // ── T2 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T2 — a LIVE read that throws emits LIVE_READ_FAILED with 'RuntimeException: boom'")
    void liveReadFailed_emits() throws Exception {
        subscribeAndAwaitLive(bus);
        long p1 = publishAndNotify(bus);
        awaitTrue(() -> checkpointStore.readCheckpoint(SUBSCRIBER_ID) == p1,
                "the baseline delivery checkpointing " + p1);

        long p = p1 + 1;
        scriptedStore.throwForever(p, "boom");
        assertThat(publishAndNotify(bus)).isEqualTo(p);

        DeliveryAnomaly anomaly = awaitAnomaly(DeliveryAnomaly.Kind.LIVE_READ_FAILED, p);
        assertThat(anomaly.subscriberId()).isEqualTo(SUBSCRIBER_ID);
        assertThat(anomaly.detail()).isEqualTo("RuntimeException: boom");
        assertThat(anomaly.timestamp()).isEqualTo(CLOCK.instant());

        settle();
        assertThat(checkpointStore.readCheckpoint(SUBSCRIBER_ID))
                .as("the checkpoint never advances past a position whose read failed")
                .isEqualTo(p1);
        assertThat(delivered).doesNotContain(p);
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
            scriptedStore.emptyOnce(p);
            assertThat(publishAndNotify(throwingBus)).isEqualTo(p);
            awaitTrue(() -> emitterCalls.get() >= 2, "the LIVE loop's anomaly reaching the emitter");

            long next = publishAndNotify(throwingBus);
            awaitTrue(() -> delivered.contains(next),
                    "the subscriber's virtual thread surviving the throwing emitter");
        } finally {
            throwingBus.reset();
        }
    }

    // ── Harness ─────────────────────────────────────────────────────────────

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

    /** A short fixed settle for the LIVE loop to act on the anomaly before a negative assert. */
    private static void settle() throws InterruptedException {
        Thread.sleep(200L);
    }

    // ── The scripted read store ─────────────────────────────────────────────

    /**
     * Delegating {@link EventStore} whose single-position reads on a VIRTUAL
     * thread (the subscriber's LIVE loop through the synchronous read
     * executor) follow a per-position script; every other read passes through
     * to the real store. Guarded by a {@link ReentrantLock} (LTD-11).
     */
    private static final class ScriptedReadStore implements EventStore {

        private enum Response { EMPTY, THROW, REAL }

        private final EventStore delegate;
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<Long, Deque<Response>> queued = new HashMap<>();
        private final Map<Long, Response> steady = new HashMap<>();
        private final Map<Long, String> throwMessages = new HashMap<>();

        ScriptedReadStore(EventStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /** Every subscriber-thread read of {@code position} returns an empty page. */
        void emptyForever(long position) {
            lock.lock();
            try {
                steady.put(position, Response.EMPTY);
            } finally {
                lock.unlock();
            }
        }

        /** Every subscriber-thread read of {@code position} throws {@code RuntimeException(message)}. */
        void throwForever(long position, String message) {
            lock.lock();
            try {
                steady.put(position, Response.THROW);
                throwMessages.put(position, message);
            } finally {
                lock.unlock();
            }
        }

        /** The FIRST subscriber-thread read of {@code position} is empty; later reads are real. */
        void emptyOnce(long position) {
            lock.lock();
            try {
                queued.computeIfAbsent(position, ignored -> new ArrayDeque<>()).add(Response.EMPTY);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            if (maxCount == 1 && Thread.currentThread().isVirtual()) {
                long position = afterPosition + 1;
                Response response = nextResponse(position);
                switch (response) {
                    case EMPTY -> {
                        return new EventPage(List.of(), afterPosition, false);
                    }
                    case THROW -> throw new RuntimeException(throwMessageFor(position));
                    case REAL -> {
                        // fall through to the delegate
                    }
                }
            }
            return delegate.readFrom(afterPosition, maxCount);
        }

        private Response nextResponse(long position) {
            lock.lock();
            try {
                Deque<Response> script = queued.get(position);
                if (script != null && !script.isEmpty()) {
                    return script.poll();
                }
                return steady.getOrDefault(position, Response.REAL);
            } finally {
                lock.unlock();
            }
        }

        private String throwMessageFor(long position) {
            lock.lock();
            try {
                return throwMessages.getOrDefault(position, "scripted read failure");
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
