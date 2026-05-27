/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubscriberSupervisor} — the per-subscriber exception
 * handler that wraps {@link Subscriber#onEvent} calls.
 *
 * <p>Focus: the AMD-36 wiring landed in M3.5b-supervisor-wiring (this WU)
 * that replaces the legacy 6-field {@link SubscriberDlq.DlqEntry} construction
 * in the {@code RuntimeException} catch site with a full 11-field
 * {@link DeadLetter}. Also covers the unchanged exception taxonomy
 * (Error/IOException/checked → SUSPENDED) and the rolling crash-window
 * circuit breaker.</p>
 *
 * <p>The supervisor was previously exercised only indirectly through
 * {@link InProcessEventBus} integration tests; this class is the first
 * direct unit-test for its public surface.</p>
 */
@DisplayName("SubscriberSupervisor")
class SubscriberSupervisorTest {

    private static final String SUBSCRIBER_ID = "test-subscriber";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static final Ulid TEST_EVENT_ULID = new Ulid(1L, 1L);
    private static final Ulid TEST_ENTITY_ULID = new Ulid(2L, 2L);
    private static final Ulid TEST_ACTOR_ULID = new Ulid(3L, 3L);

    private static final EventId TEST_EVENT_ID = EventId.of(TEST_EVENT_ULID);
    private static final SubjectRef TEST_SUBJECT =
            SubjectRef.entity(EntityId.of(TEST_ENTITY_ULID));
    private static final CausalContext TEST_CAUSAL = CausalContext.root(TEST_EVENT_ULID);

    /** Simple DomainEvent for envelope construction. */
    private record TestPayload(String data) implements DomainEvent {}

    private static final DomainEvent TEST_PAYLOAD = new TestPayload("test");

    private SubscriberDlq dlq;
    private SubscriberSupervisor supervisor;
    private SubscriberRuntime runtime;

    @BeforeEach
    void setUp() {
        dlq = new SubscriberDlq(SUBSCRIBER_ID, PersistentDlqWriter.noop(), FIXED_CLOCK);
        supervisor = new SubscriberSupervisor(SUBSCRIBER_ID, FIXED_CLOCK, dlq);
        runtime = newRuntime(dlq, supervisor);
    }

    // ── Success path ────────────────────────────────────────────────────

    @Test
    @DisplayName("successful delivery returns SUCCESS; DLQ stays empty")
    void deliver_success_returnsSuccess() {
        Subscriber subscriber = env -> { /* no-op */ };
        EventEnvelope envelope = makeEnvelope(100L);

        SubscriberSupervisor.DeliveryResult result =
                supervisor.deliver(subscriber, envelope, runtime);

        assertThat(result).isEqualTo(SubscriberSupervisor.DeliveryResult.SUCCESS);
        assertThat(dlq.depth()).isZero();
    }

    // ── RuntimeException path: parks DeadLetter ─────────────────────────

    @Test
    @DisplayName("RuntimeException returns PARKED and parks one dead-letter")
    void deliver_runtimeException_parksDeadLetter() {
        Subscriber subscriber = throwingSubscriber(new RuntimeException("synthetic"));
        EventEnvelope envelope = makeEnvelope(100L);

        SubscriberSupervisor.DeliveryResult result =
                supervisor.deliver(subscriber, envelope, runtime);

        assertThat(result).isEqualTo(SubscriberSupervisor.DeliveryResult.PARKED);
        assertThat(dlq.depth()).isEqualTo(1);
    }

    @Test
    @DisplayName("parked DeadLetter fields mirror envelope and exception identity")
    void deliver_runtimeException_deadLetterFieldsMatchEnvelope() {
        RecordingPersistentWriter writer = new RecordingPersistentWriter();
        SubscriberDlq capturingDlq = new SubscriberDlq(SUBSCRIBER_ID, writer, FIXED_CLOCK);
        SubscriberSupervisor sup =
                new SubscriberSupervisor(SUBSCRIBER_ID, FIXED_CLOCK, capturingDlq);

        Subscriber subscriber = throwingSubscriber(new RuntimeException("boom"));
        EventEnvelope envelope = makeEnvelope(100L);

        sup.deliver(subscriber, envelope, newRuntime(capturingDlq, sup));

        DeadLetter dl = writer.lastDeadLetter();
        assertThat(dl).isNotNull();
        assertThat(dl.eventPosition()).isEqualTo(envelope.globalPosition());
        assertThat(dl.eventId()).isEqualTo(envelope.eventId().value());
        assertThat(dl.subscriberId()).isEqualTo(SUBSCRIBER_ID);
        assertThat(dl.causeClass()).isEqualTo("java.lang.RuntimeException");
        assertThat(dl.causeMessage()).isEqualTo("boom");
        assertThat(dl.firstSeenAt()).isEqualTo(FIXED_INSTANT);
        assertThat(dl.lastAttemptAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("sequenceKey is envelope.subjectRef().toString() — type-prefixed format")
    void deliver_runtimeException_sequenceKeyUsesSubjectRefToString() {
        RecordingPersistentWriter writer = new RecordingPersistentWriter();
        SubscriberDlq capturingDlq = new SubscriberDlq(SUBSCRIBER_ID, writer, FIXED_CLOCK);
        SubscriberSupervisor sup =
                new SubscriberSupervisor(SUBSCRIBER_ID, FIXED_CLOCK, capturingDlq);

        EventEnvelope envelope = makeEnvelope(100L);
        sup.deliver(throwingSubscriber(new RuntimeException("boom")),
                envelope, newRuntime(capturingDlq, sup));

        DeadLetter dl = writer.lastDeadLetter();
        assertThat(dl.sequenceKey()).isEqualTo(envelope.subjectRef().toString());
        // Sanity-check the type-prefixed form: "entity:<ULID>".
        assertThat(dl.sequenceKey()).startsWith("entity:");
    }

    @Test
    @DisplayName("attemptCount is 1 — no retry loop activated yet")
    void deliver_runtimeException_attemptCountIsOne() {
        RecordingPersistentWriter writer = new RecordingPersistentWriter();
        SubscriberDlq capturingDlq = new SubscriberDlq(SUBSCRIBER_ID, writer, FIXED_CLOCK);
        SubscriberSupervisor sup =
                new SubscriberSupervisor(SUBSCRIBER_ID, FIXED_CLOCK, capturingDlq);

        sup.deliver(throwingSubscriber(new RuntimeException("boom")),
                makeEnvelope(100L), newRuntime(capturingDlq, sup));

        assertThat(writer.lastDeadLetter().attemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("null exception message is replaced by empty string for V002 NOT NULL column")
    void deliver_runtimeException_nullMessage_usesEmptyString() {
        RecordingPersistentWriter writer = new RecordingPersistentWriter();
        SubscriberDlq capturingDlq = new SubscriberDlq(SUBSCRIBER_ID, writer, FIXED_CLOCK);
        SubscriberSupervisor sup =
                new SubscriberSupervisor(SUBSCRIBER_ID, FIXED_CLOCK, capturingDlq);

        // Bare NPE — getMessage() returns null.
        sup.deliver(throwingSubscriber(new NullPointerException()),
                makeEnvelope(100L), newRuntime(capturingDlq, sup));

        DeadLetter dl = writer.lastDeadLetter();
        assertThat(dl.causeClass()).isEqualTo("java.lang.NullPointerException");
        assertThat(dl.causeMessage())
                .as("null getMessage() must be coerced to empty string")
                .isEqualTo("");
    }

    @Test
    @DisplayName("diagnostics is null — stack trace serialization is a future enhancement")
    void deliver_runtimeException_diagnosticsIsNull() {
        RecordingPersistentWriter writer = new RecordingPersistentWriter();
        SubscriberDlq capturingDlq = new SubscriberDlq(SUBSCRIBER_ID, writer, FIXED_CLOCK);
        SubscriberSupervisor sup =
                new SubscriberSupervisor(SUBSCRIBER_ID, FIXED_CLOCK, capturingDlq);

        sup.deliver(throwingSubscriber(new RuntimeException("boom")),
                makeEnvelope(100L), newRuntime(capturingDlq, sup));

        assertThat(writer.lastDeadLetter().diagnostics()).isNull();
    }

    @Test
    @DisplayName("dlqId is UNASSIGNED_DLQ_ID (0) — SQLite assigns the real id on persist")
    void deliver_runtimeException_dlqIdIsUnassigned() {
        RecordingPersistentWriter writer = new RecordingPersistentWriter();
        SubscriberDlq capturingDlq = new SubscriberDlq(SUBSCRIBER_ID, writer, FIXED_CLOCK);
        SubscriberSupervisor sup =
                new SubscriberSupervisor(SUBSCRIBER_ID, FIXED_CLOCK, capturingDlq);

        sup.deliver(throwingSubscriber(new RuntimeException("boom")),
                makeEnvelope(100L), newRuntime(capturingDlq, sup));

        assertThat(writer.lastDeadLetter().dlqId())
                .isEqualTo(DeadLetter.UNASSIGNED_DLQ_ID)
                .isZero();
    }

    @Test
    @DisplayName("RuntimeException records a crash in the rolling window")
    void deliver_runtimeException_recordsCrash() {
        assertThat(supervisor.crashCount()).isZero();

        supervisor.deliver(throwingSubscriber(new RuntimeException("boom")),
                makeEnvelope(100L), runtime);

        assertThat(supervisor.crashCount()).isEqualTo(1);
    }

    // ── Circuit breaker ─────────────────────────────────────────────────

    @Test
    @DisplayName("fifth RuntimeException in the rolling window trips the circuit breaker")
    void deliver_fiveCrashesInWindow_tripsCircuitBreaker() {
        Subscriber subscriber = throwingSubscriber(new RuntimeException("boom"));

        SubscriberSupervisor.DeliveryResult last = null;
        for (int i = 0; i < 5; i++) {
            last = supervisor.deliver(subscriber, makeEnvelope(100L + i), runtime);
        }

        assertThat(last)
                .as("fifth delivery within the 10-minute window trips the breaker")
                .isEqualTo(SubscriberSupervisor.DeliveryResult.CIRCUIT_BREAKER_TRIPPED);
        assertThat(runtime.mode())
                .as("breaker transitions the subscriber to SUSPENDED")
                .isEqualTo(SubscriberMode.SUSPENDED);
        assertThat(dlq.depth())
                .as("every crash also parks a dead-letter")
                .isEqualTo(5);
    }

    // ── Infrastructure exceptions: SUSPENDED, no DLQ ────────────────────

    @Test
    @DisplayName("Error returns INFRASTRUCTURE_FAILURE, does NOT park, transitions to SUSPENDED")
    void deliver_error_returnsInfrastructureFailure_noDeadLetter() {
        Subscriber subscriber = env -> { throw new StackOverflowError("infra"); };

        SubscriberSupervisor.DeliveryResult result =
                supervisor.deliver(subscriber, makeEnvelope(100L), runtime);

        assertThat(result)
                .isEqualTo(SubscriberSupervisor.DeliveryResult.INFRASTRUCTURE_FAILURE);
        assertThat(dlq.depth())
                .as("Error path bypasses the DLQ entirely")
                .isZero();
        assertThat(runtime.mode()).isEqualTo(SubscriberMode.SUSPENDED);
    }

    @Test
    @DisplayName("checked Exception returns INFRASTRUCTURE_FAILURE, does NOT park")
    void deliver_checkedException_returnsInfrastructureFailure_noDeadLetter() {
        // Wrap a checked exception in a Subscriber that smuggles it through.
        Subscriber subscriber = env -> sneakyThrow(new IOException("infra"));

        SubscriberSupervisor.DeliveryResult result =
                supervisor.deliver(subscriber, makeEnvelope(100L), runtime);

        assertThat(result)
                .isEqualTo(SubscriberSupervisor.DeliveryResult.INFRASTRUCTURE_FAILURE);
        assertThat(dlq.depth()).isZero();
        assertThat(runtime.mode()).isEqualTo(SubscriberMode.SUSPENDED);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Builds an EventEnvelope at the given globalPosition with deterministic fields. */
    private static EventEnvelope makeEnvelope(long globalPosition) {
        return new EventEnvelope(
                TEST_EVENT_ID,
                "test.event",
                1,
                FIXED_INSTANT,
                null,
                TEST_SUBJECT,
                1L,
                globalPosition,
                EventPriority.NORMAL,
                EventOrigin.PHYSICAL,
                List.of(EventCategory.DEVICE_STATE),
                TEST_CAUSAL,
                TEST_ACTOR_ULID,
                TEST_PAYLOAD
        );
    }

    /** Constructs a minimal SubscriberRuntime suitable for breaker / mode assertions. */
    private SubscriberRuntime newRuntime(SubscriberDlq d, SubscriberSupervisor sup) {
        SubscriberInfo info = new SubscriberInfo(
                SUBSCRIBER_ID, SubscriptionFilter.all(), false);
        Subscriber noop = env -> { };
        return new SubscriberRuntime(
                info, noop, new NoopReadExecutor(), sup, d, new ReplayWindowQueue());
    }

    /** Returns a {@link Subscriber} whose {@code onEvent} throws the given runtime exception. */
    private static Subscriber throwingSubscriber(RuntimeException toThrow) {
        return env -> { throw toThrow; };
    }

    /**
     * Smuggles a checked exception through a lambda whose signature does not
     * declare it. Java's generic type-erasure allows the {@code throws T}
     * substitution at the call site to bind {@code T} to {@code RuntimeException}
     * even when the actual thrown instance is a checked exception — but the
     * exception identity (and {@code instanceof IOException}) survives, which
     * is what {@link SubscriberSupervisor#deliver}'s {@code catch (Exception e)}
     * branch routes on.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * {@link PersistentDlqWriter} that captures the most recently parked
     * {@link DeadLetter} for field inspection.
     */
    private static final class RecordingPersistentWriter implements PersistentDlqWriter {

        private final Deque<DeadLetter> parked = new ArrayDeque<>();

        RecordingPersistentWriter() {
            // Explicit no-arg constructor for -Xlint:all -Werror.
        }

        @Override
        public void park(DeadLetter deadLetter) {
            parked.addLast(deadLetter);
        }

        DeadLetter lastDeadLetter() {
            return parked.peekLast();
        }
    }

    /**
     * {@link SubscriberReadExecutor} that never executes anything. The supervisor
     * never touches the read executor on any code path, so a no-op suffices for
     * runtime construction.
     */
    private static final class NoopReadExecutor implements SubscriberReadExecutor {

        NoopReadExecutor() {
            // Explicit no-arg constructor for -Xlint:all -Werror.
        }

        @Override
        public <T> T executeRead(Callable<T> task) {
            throw new UnsupportedOperationException(
                    "NoopReadExecutor: supervisor tests must not call executeRead");
        }

        @Override
        public void close() {
            // No resources to release.
        }
    }
}
