/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.SystemStartedEvent;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.AdvanceResult;
import com.homesynapse.state.ProjectionAdvancer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Abstract contract test for {@link ProjectionAdvancer} implementations.
 *
 * <p>Defines the behavioral contract that every {@code ProjectionAdvancer}
 * implementation MUST satisfy, including AMD-41 §3.2.1 (the processor runs
 * inside the read transaction and the transaction closes before {@code advance}
 * returns) and AMD-38 (bounded-window discipline: at most
 * {@link ProjectionAdvancer#DEFAULT_MAX_ROWS} = 500 events per call).
 *
 * <p>Concrete implementations subclass this class and provide the two abstract
 * factory methods ({@link #newAdvancer(List)} and {@link #readTxInProgress()}).
 * The eleven {@code @Test} methods are inherited and run against the subclass's
 * advancer.
 *
 * <p>The fixture event log is constructed via the private {@code makeLog(int)}
 * helper and consists of minimal-valid {@link EventEnvelope} instances with
 * {@code globalPosition} values 1..size. All envelopes in a log share the
 * same subject and payload — the contract tests assert only on
 * {@code globalPosition}, never on payload or subject identity. Fixture
 * timestamps use {@link Instant#parse(CharSequence)} of a literal string
 * (the standard pattern across the codebase; see ArchUnit rule
 * {@code NO_DIRECT_TIME_ACCESS}).
 */
public abstract class ProjectionAdvancerContractTest {

    /**
     * Default zero-arg constructor. Required because the project enforces
     * {@code -Xlint:all -Werror} and the codebase convention mandates an
     * explicit constructor on every non-record public/protected class.
     * Subclasses inherit this constructor implicitly.
     */
    protected ProjectionAdvancerContractTest() {
        // No initialization needed; concrete subclasses configure their own
        // state in @BeforeEach hooks if any.
    }

    /**
     * Builds a {@link ProjectionAdvancer} backed by the supplied fixture log.
     *
     * <p>Subclasses decide how to wire the log: in-memory subclasses use a
     * list-backed advancer; the SQLite subclass loads the log into an
     * in-memory SQLite database. The {@code globalPosition} values in the
     * supplied log are assigned sequentially starting at 1, and the advancer
     * MUST honor those exact positions.
     *
     * @param log the fixture event log; positions are 1..log.size()
     * @return an advancer over the supplied log
     */
    protected abstract ProjectionAdvancer newAdvancer(List<EventEnvelope> log);

    /**
     * Reports whether the advancer's read transaction is currently open.
     *
     * <p>The in-memory subclass tracks this via a fixture flag set
     * {@code true} on {@code advance} entry and {@code false} on
     * {@code advance} exit. The SQLite subclass introspects the read
     * connection's auto-commit state ({@code false} means a transaction is
     * open).
     *
     * @return {@code true} if the advancer currently holds a read transaction
     */
    protected abstract boolean readTxInProgress();

    // ----- Fixture helpers -----

    /**
     * Fixed instant used for {@code ingestTime} on every fixture envelope.
     * Literal parse — does NOT trigger {@code NO_DIRECT_TIME_ACCESS}.
     */
    private static final Instant FIXTURE_INSTANT =
            Instant.parse("2026-01-01T00:00:00Z");

    /**
     * Fixed {@link EventId} shared by every fixture envelope. The contract
     * tests assert only on {@code globalPosition}; eventId uniqueness is not
     * exercised, so a single parsed ULID is sufficient.
     */
    private static final EventId FIXTURE_EVENT_ID =
            EventId.parse("01H0000000000000000000000A");

    /** Fixed {@link Ulid} derived from {@link #FIXTURE_EVENT_ID}. */
    private static final Ulid FIXTURE_ULID = FIXTURE_EVENT_ID.value();

    /**
     * Fixed {@link SubjectRef} shared by every fixture envelope. Subject
     * identity is not exercised by the contract tests; the
     * {@code (subjectRef, subjectSequence)} pair is kept unique within a log
     * by setting {@code subjectSequence == globalPosition}.
     */
    private static final SubjectRef FIXTURE_SUBJECT =
            new SubjectRef(FIXTURE_ULID, SubjectType.SYSTEM);

    /**
     * Fixed root {@link CausalContext} shared by every fixture envelope.
     * Causality is not exercised by the contract tests.
     */
    private static final CausalContext FIXTURE_CONTEXT =
            CausalContext.root(FIXTURE_ULID);

    /**
     * Minimal-valid {@link DomainEvent} payload shared by every fixture
     * envelope. {@link SystemStartedEvent} is chosen for its trivially-valid
     * field set (non-blank version string, non-negative startup duration).
     */
    private static final DomainEvent FIXTURE_PAYLOAD =
            new SystemStartedEvent("test", 0L);

    /**
     * Builds a fixture event log of the requested size. Each envelope has
     * {@code globalPosition == i} and {@code subjectSequence == i} for
     * {@code i} in {@code 1..size}.
     *
     * @param size the number of envelopes to produce; must be {@code >= 0}
     * @return a list of {@code size} fixture envelopes, in ascending
     *         {@code globalPosition} order
     */
    private static List<EventEnvelope> makeLog(int size) {
        List<EventEnvelope> log = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            log.add(makeEvent(i));
        }
        return log;
    }

    /**
     * Builds a single minimal-valid {@link EventEnvelope} at the supplied
     * global position. All non-positional fields are fixed across the log.
     */
    private static EventEnvelope makeEvent(long globalPosition) {
        return new EventEnvelope(
                FIXTURE_EVENT_ID,
                EventTypes.SYSTEM_STARTED,
                1,
                FIXTURE_INSTANT,
                null,
                FIXTURE_SUBJECT,
                globalPosition,
                globalPosition,
                EventPriority.CRITICAL,
                EventOrigin.SYSTEM,
                List.of(EventCategory.SYSTEM),
                FIXTURE_CONTEXT,
                null,
                FIXTURE_PAYLOAD);
    }

    // ----- Tests -----

    @Test
    void advanceDeliversInPositionOrder() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(10));
        List<Long> seen = new ArrayList<>();

        advancer.advance(0L, 20, env -> seen.add(env.globalPosition()));

        assertThat(seen)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    void advanceRespectsFromPositionExclusive() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(10));
        List<Long> seen = new ArrayList<>();

        advancer.advance(5L, 10, env -> seen.add(env.globalPosition()));

        assertThat(seen).doesNotContain(5L);
        assertThat(seen).first().isEqualTo(6L);
    }

    @Test
    void advanceRespectsMaxRows() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(100));
        int[] invocations = {0};

        AdvanceResult result =
                advancer.advance(0L, 7, env -> invocations[0]++);

        assertThat(invocations[0]).isLessThanOrEqualTo(7);
        assertThat(result.eventsProcessed()).isEqualTo(invocations[0]);
    }

    @Test
    void advanceCapsAtDefaultMaxRows() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(1000));
        int[] invocations = {0};

        AdvanceResult result = advancer.advance(
                0L, Integer.MAX_VALUE, env -> invocations[0]++);

        assertThat(invocations[0]).isLessThanOrEqualTo(500);
        assertThat(result.eventsProcessed()).isLessThanOrEqualTo(500);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void advanceHasMoreWhenLogExceedsPage() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(600));

        AdvanceResult result = advancer.advance(0L, 500, env -> { });

        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void advanceReachesTail() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(5));

        AdvanceResult drained = advancer.advance(0L, 10, env -> { });
        assertThat(drained.hasMore()).isFalse();
        assertThat(drained.eventsProcessed()).isEqualTo(5);

        AdvanceResult atTail = advancer.advance(5L, 10, env -> { });
        assertThat(atTail.hasMore()).isFalse();
        assertThat(atTail.eventsProcessed()).isEqualTo(0);
    }

    @Test
    void advanceProcessorInvokedInsideReadTx() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(3));
        AtomicBoolean processorSawTxOpen = new AtomicBoolean(false);

        advancer.advance(0L, 10, env -> {
            if (readTxInProgress()) {
                processorSawTxOpen.set(true);
            }
        });

        assertThat(processorSawTxOpen.get()).isTrue();
        assertThat(readTxInProgress()).isFalse();
    }

    @Test
    void advanceProcessorExceptionPropagates() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(5));
        int[] invocations = {0};

        assertThatThrownBy(() -> advancer.advance(0L, 5, env -> {
            invocations[0]++;
            if (invocations[0] == 3) {
                throw new RuntimeException("boom");
            }
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void advanceProcessorExceptionClosesReadTx() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(5));
        int[] invocations = {0};

        assertThatThrownBy(() -> advancer.advance(0L, 5, env -> {
            invocations[0]++;
            if (invocations[0] == 3) {
                throw new RuntimeException("boom");
            }
        }))
                .isInstanceOf(RuntimeException.class);

        assertThat(readTxInProgress()).isFalse();
    }

    @Test
    void advanceRejectsInvalidArgs() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(5));
        Consumer<EventEnvelope> noop = env -> { };

        assertThatThrownBy(() -> advancer.advance(0L, 0, noop))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> advancer.advance(0L, -1, noop))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> advancer.advance(-1L, 10, noop))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> advancer.advance(0L, 10, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void advanceFromZeroDrainsLogInBoundedWindows() {
        ProjectionAdvancer advancer = newAdvancer(makeLog(1500));
        Consumer<EventEnvelope> noop = env -> { };

        AdvanceResult call1 = advancer.advance(0L, 500, noop);
        assertThat(call1.hasMore()).isTrue();
        assertThat(call1.eventsProcessed()).isEqualTo(500);
        assertThat(readTxInProgress()).isFalse();

        AdvanceResult call2 =
                advancer.advance(call1.lastProcessedPosition(), 500, noop);
        assertThat(call2.hasMore()).isTrue();
        assertThat(call2.eventsProcessed()).isEqualTo(500);
        assertThat(readTxInProgress()).isFalse();

        AdvanceResult call3 =
                advancer.advance(call2.lastProcessedPosition(), 500, noop);
        assertThat(call3.hasMore()).isFalse();
        assertThat(call3.eventsProcessed()).isEqualTo(500);
        assertThat(readTxInProgress()).isFalse();

        AdvanceResult call4 =
                advancer.advance(call3.lastProcessedPosition(), 500, noop);
        assertThat(call4.hasMore()).isFalse();
        assertThat(call4.eventsProcessed()).isEqualTo(0);
    }
}
