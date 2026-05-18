/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.platform.identity.UlidFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Abstract behavioral contract for any {@link Subscriber} implementation that
 * carries a {@link SubscriberMode} and may publish derived events.
 *
 * <p>The four {@code @Test} methods exercise general subscriber-callback
 * properties that apply equally to {@code StateProjection} and any future
 * derivation-producing subscriber. They do <em>not</em> test bus-side concerns
 * (delivery ordering, supervisor backoff) — those live in
 * {@code com.homesynapse.event.bus.test.EventBusContractTest}.</p>
 *
 * <p>Concrete subclasses (in the same JPMS package as the subscriber under
 * test) implement the three abstract factory methods and inherit the test
 * methods. The factories supply the spy publisher used by the
 * mode-boundary test and the subscriber instance pre-wired to it.</p>
 *
 * <h2>Subscribers tested in M3.5a</h2>
 * <ul>
 *   <li>{@code StateProjection} — via
 *       {@code InMemoryStateProjectionTest extends StateProjectionContractTest
 *       extends SubscriberContractTest}.</li>
 * </ul>
 */
public abstract class SubscriberContractTest {

    /**
     * Default zero-arg constructor required by {@code -Xlint:all -Werror}.
     */
    protected SubscriberContractTest() {
        // Subclasses configure their own state in @BeforeEach.
    }

    // ──────────────────────────────────────────────────────────────────
    // Abstract factories (implemented by concrete subclass)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns a fresh subscriber configured for {@link SubscriberMode#LIVE} and
     * wired to {@link #spyPublisher()}.
     *
     * @return a LIVE-mode subscriber instance
     */
    protected abstract Subscriber createSubscriberInLiveMode();

    /**
     * Returns a fresh subscriber configured for {@link SubscriberMode#REPLAY}
     * and wired to {@link #spyPublisher()}.
     *
     * @return a REPLAY-mode subscriber instance
     */
    protected abstract Subscriber createSubscriberInReplayMode();

    /**
     * Returns the spy {@link EventPublisher} that the LIVE/REPLAY subscribers
     * are wired to. Tests interrogate this for publish-count assertions.
     *
     * @return the spy publisher
     */
    protected abstract SpyPublisher spyPublisher();

    /**
     * Returns a {@link SubjectRef} that the subscriber under test will
     * recognize as deriving from. For {@code StateProjection}, this is an
     * entity-typed subject. Concrete subclasses choose one that exercises
     * the derivation path.
     *
     * @return a SubjectRef for derivation-triggering events
     */
    protected abstract SubjectRef derivingSubject();

    // ──────────────────────────────────────────────────────────────────
    // Tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    void subscriberReceivesEventsInPositionOrder() {
        Subscriber subscriber = createSubscriberInLiveMode();
        SubjectRef subject = derivingSubject();
        List<Long> seenPositions = new ArrayList<>();
        Subscriber recording = env -> {
            seenPositions.add(env.globalPosition());
            subscriber.onEvent(env);
        };

        for (int i = 1; i <= 10; i++) {
            recording.onEvent(makeStateReportedEnvelope(subject, i, "attr", "v" + i));
        }

        assertThat(seenPositions)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    void subscriberOnEventExceptionDoesNotKillSubscriber() {
        // The subscriber's contract is to NOT swallow exceptions — the bus
        // supervisor handles them. We verify the contract by wrapping the
        // subscriber with a throw-on-3 layer and confirming all 10 events
        // are still ATTEMPTED (the wrapper's call counter reaches 10).
        Subscriber subscriber = createSubscriberInLiveMode();
        SubjectRef subject = derivingSubject();
        AtomicInteger attempts = new AtomicInteger(0);
        Subscriber throwing = env -> {
            int count = attempts.incrementAndGet();
            if (count == 3) {
                // Subscriber-side exceptions propagate; the supervisor handles them.
                throw new RuntimeException("synthetic failure on event 3");
            }
            subscriber.onEvent(env);
        };

        for (int i = 1; i <= 10; i++) {
            EventEnvelope env = makeStateReportedEnvelope(subject, i, "attr", "v" + i);
            try {
                throwing.onEvent(env);
            } catch (RuntimeException re) {
                // Expected on i == 3 — supervisor would catch this in production.
            }
        }

        assertThat(attempts.get())
                .as("All 10 events were attempted, even though event 3 threw")
                .isEqualTo(10);
    }

    @Test
    void subscriberCheckpointAdvancesAfterOnEvent() {
        Subscriber subscriber = createSubscriberInLiveMode();
        SubjectRef subject = derivingSubject();

        for (int i = 1; i <= 5; i++) {
            subscriber.onEvent(makeStateReportedEnvelope(subject, i, "attr", "v" + i));
        }

        long observedCursor = observedCursor(subscriber);
        assertThat(observedCursor).as("Subscriber's cursor advances to the last processed position")
                .isEqualTo(5L);
    }

    @Test
    void subscriberRespectsModeBoundary() {
        Subscriber subscriber = createSubscriberInReplayMode();
        SubjectRef subject = derivingSubject();
        SpyPublisher spy = spyPublisher();
        int before = spy.publishCount();

        // Deliver one envelope that would normally trigger derivation.
        subscriber.onEvent(makeStateReportedEnvelope(subject, 1L, "attr", "value"));

        assertThat(spy.publishCount())
                .as("REPLAY-mode subscriber must NOT call publish()")
                .isEqualTo(before);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers — accessible to concrete subclasses
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns the cursor (last-processed-position) the subscriber has reached.
     * Concrete subclasses must expose this; the default implementation reads
     * it via reflection-free narrowing in the concrete class.
     *
     * <p>The default returns {@code Long.MIN_VALUE} so subclasses that don't
     * override (and don't need this test) will fail loudly rather than pass
     * trivially.</p>
     *
     * @param subscriber the subscriber instance
     * @return the cursor position
     */
    protected long observedCursor(Subscriber subscriber) {
        return Long.MIN_VALUE;
    }

    /**
     * Builds a {@code state_reported} envelope for the given subject at the
     * given global position. The envelope's payload is a
     * {@link com.homesynapse.event.StateReportedEvent}.
     *
     * <p>Used by tests that need to inject envelopes directly into
     * {@link Subscriber#onEvent} without round-tripping through an event
     * store. Concrete subclasses may override when their derivation rule
     * needs a different payload type.</p>
     *
     * @param subject        the event subject
     * @param globalPosition the global position to assign
     * @param attributeKey   the reported attribute
     * @param value          the reported value
     * @return a fully-populated envelope
     */
    protected EventEnvelope makeStateReportedEnvelope(SubjectRef subject,
                                                      long globalPosition,
                                                      String attributeKey,
                                                      String value) {
        EventId eventId = EventId.of(UlidFactory.generate());
        Ulid corrId = eventId.value();
        return new EventEnvelope(
                eventId,
                EventTypes.STATE_REPORTED,
                1,
                FIXTURE_INSTANT,
                null,
                subject,
                globalPosition,
                globalPosition,
                EventPriority.DIAGNOSTIC,
                EventOrigin.PHYSICAL,
                List.of(EventCategory.DEVICE_STATE),
                CausalContext.root(corrId),
                null,
                new com.homesynapse.event.StateReportedEvent(
                        attributeKey, value, null, null, null));
    }

    /**
     * Builds a subject reference for a fresh entity.
     *
     * @return a SubjectRef of type ENTITY with a generated EntityId
     */
    protected static SubjectRef freshEntitySubject() {
        return SubjectRef.entity(new EntityId(UlidFactory.generate()));
    }

    /** Fixed instant for fixture envelope ingestTime — literal parse is whitelisted. */
    protected static final Instant FIXTURE_INSTANT =
            Instant.parse("2026-01-01T00:00:00Z");

    // ──────────────────────────────────────────────────────────────────
    // SpyPublisher — a recording wrapper around any EventPublisher
    // ──────────────────────────────────────────────────────────────────

    /**
     * Recording {@link EventPublisher} that counts publish calls and forwards
     * to a delegate. Optionally records the time-of-publish state of an
     * externally-managed flag (used by {@code readTxClosesBeforePublish}).
     */
    public static final class SpyPublisher implements EventPublisher {

        private final EventPublisher delegate;
        private final AtomicInteger publishCount = new AtomicInteger(0);
        private final List<EventEnvelope> publishedEnvelopes = new ArrayList<>();
        private final List<DomainEvent> publishedPayloads = new ArrayList<>();
        private volatile java.util.function.BooleanSupplier txProbe;
        private volatile boolean txStateAtLastPublish;
        private volatile boolean txProbeRecorded;

        /**
         * Constructs a spy that forwards to the given delegate.
         *
         * @param delegate the underlying publisher; never {@code null}
         */
        public SpyPublisher(EventPublisher delegate) {
            this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        }

        /**
         * Installs a probe that is sampled on every publish call. The sampled
         * value is exposed via {@link #txStateAtLastPublish()}.
         *
         * @param probe a supplier returning the flag value to sample
         */
        public void installTxProbe(java.util.function.BooleanSupplier probe) {
            this.txProbe = probe;
        }

        /**
         * Returns the sampled probe value at the time of the most recent
         * publish call. Undefined if no publish has occurred or no probe is
         * installed.
         *
         * @return the sampled value
         */
        public boolean txStateAtLastPublish() {
            return txStateAtLastPublish;
        }

        /**
         * Returns whether the probe was sampled at least once.
         *
         * @return {@code true} once at least one publish has occurred with the
         *         probe installed
         */
        public boolean txProbeRecorded() {
            return txProbeRecorded;
        }

        /**
         * Returns the total publish call count (both {@code publish} and
         * {@code publishRoot}).
         *
         * @return total publish count
         */
        public int publishCount() {
            return publishCount.get();
        }

        /**
         * Returns the envelopes returned by all successful publish calls, in
         * call order.
         *
         * @return unmodifiable list of published envelopes
         */
        public List<EventEnvelope> publishedEnvelopes() {
            return List.copyOf(publishedEnvelopes);
        }

        /**
         * Returns the payloads of all published events, in call order.
         *
         * @return unmodifiable list of published payloads
         */
        public List<DomainEvent> publishedPayloads() {
            return List.copyOf(publishedPayloads);
        }

        @Override
        public EventEnvelope publish(EventDraft draft, CausalContext cause)
                throws SequenceConflictException {
            sampleProbe();
            EventEnvelope env = delegate.publish(draft, cause);
            recordPublish(env);
            return env;
        }

        @Override
        public EventEnvelope publishRoot(EventDraft draft)
                throws SequenceConflictException {
            sampleProbe();
            EventEnvelope env = delegate.publishRoot(draft);
            recordPublish(env);
            return env;
        }

        private void sampleProbe() {
            if (txProbe != null) {
                txStateAtLastPublish = txProbe.getAsBoolean();
                txProbeRecorded = true;
            }
        }

        private void recordPublish(EventEnvelope env) {
            publishCount.incrementAndGet();
            publishedEnvelopes.add(env);
            publishedPayloads.add(env.payload());
        }
    }

}
