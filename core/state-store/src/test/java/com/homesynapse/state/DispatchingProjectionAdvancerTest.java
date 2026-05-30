/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.SystemStartedEvent;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.test.TestClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DispatchingProjectionAdvancer} (Research 8 REC-28).
 *
 * <p>Two concerns are covered:</p>
 * <ol>
 *   <li>The advancer honours the {@link ProjectionAdvancer} contract exactly as
 *       the M3.7 {@code MinimalProjectionAdvancer} did — ordering, exclusive
 *       {@code fromPosition}, the {@code DEFAULT_MAX_ROWS} cap, {@code hasMore},
 *       the caught-up signal ({@code eventsProcessed == 0}), argument
 *       validation, and processor-exception propagation. {@code eventsProcessed}
 *       and {@code lastProcessedPosition} advance on every envelope because all
 *       types forward (no invented {@code AdvanceResult.skipped()}).</li>
 *   <li>The dispatch is genuine map-lookup-by-event-type (REC-28 mod C) over a
 *       constructor-injected handler set (mod A): an event type with a mapped
 *       handler routes to it; an unmapped type falls through to the default; and
 *       every dispatched envelope still reaches the processor.</li>
 * </ol>
 *
 * <p>Lives in {@code com.homesynapse.state} (not the {@code .test} fixtures
 * sub-package) so it can reach the package-private
 * {@code DispatchingProjectionAdvancer} and {@code EnvelopeHandler} for the
 * constructor-injection routing test.</p>
 */
class DispatchingProjectionAdvancerTest {

    private TestClock clock;
    private InMemoryEventStore eventStore;

    DispatchingProjectionAdvancerTest() {
        // Explicit no-arg constructor for -Xlint:all -Werror.
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        eventStore = new InMemoryEventStore(clock);
    }

    // ──────────────────────────────────────────────────────────────────
    // ProjectionAdvancer contract (parity with MinimalProjectionAdvancer)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void forwardsAllEventsInGlobalPositionOrder() {
        for (int i = 1; i <= 10; i++) {
            seedStateReported("attr", "v" + i);
        }
        ProjectionAdvancer advancer = ProjectionAdvancer.dispatching(eventStore);
        List<Long> seen = new ArrayList<>();

        AdvanceResult result = advancer.advance(0L, 20, env -> seen.add(env.globalPosition()));

        assertThat(seen).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(result.eventsProcessed()).isEqualTo(10);
        assertThat(result.lastProcessedPosition()).isEqualTo(10L);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void respectsFromPositionExclusive() {
        for (int i = 1; i <= 10; i++) {
            seedStateReported("attr", "v" + i);
        }
        ProjectionAdvancer advancer = ProjectionAdvancer.dispatching(eventStore);
        List<Long> seen = new ArrayList<>();

        advancer.advance(5L, 10, env -> seen.add(env.globalPosition()));

        assertThat(seen).doesNotContain(5L);
        assertThat(seen).first().isEqualTo(6L);
    }

    @Test
    void capsAtDefaultMaxRows() {
        for (int i = 0; i < 600; i++) {
            seedStateReported("attr", "v" + i);
        }
        ProjectionAdvancer advancer = ProjectionAdvancer.dispatching(eventStore);
        int[] invocations = {0};

        AdvanceResult result =
                advancer.advance(0L, Integer.MAX_VALUE, env -> invocations[0]++);

        assertThat(invocations[0])
                .as("a single advance is bounded at DEFAULT_MAX_ROWS = 500")
                .isLessThanOrEqualTo(ProjectionAdvancer.DEFAULT_MAX_ROWS);
        assertThat(result.eventsProcessed())
                .isLessThanOrEqualTo(ProjectionAdvancer.DEFAULT_MAX_ROWS);
        assertThat(result.hasMore())
                .as("600 events exceed one bounded window")
                .isTrue();
    }

    @Test
    void reachesTailWithZeroEventsProcessed() {
        for (int i = 1; i <= 3; i++) {
            seedStateReported("attr", "v" + i);
        }
        ProjectionAdvancer advancer = ProjectionAdvancer.dispatching(eventStore);

        AdvanceResult drained = advancer.advance(0L, 10, env -> { });
        assertThat(drained.eventsProcessed()).isEqualTo(3);
        assertThat(drained.hasMore()).isFalse();

        AdvanceResult atTail = advancer.advance(
                drained.lastProcessedPosition(), 10, env -> { });
        assertThat(atTail.eventsProcessed())
                .as("caught up to writer head — zero events processed")
                .isEqualTo(0);
        assertThat(atTail.hasMore()).isFalse();
    }

    @Test
    void rejectsInvalidArguments() {
        ProjectionAdvancer advancer = ProjectionAdvancer.dispatching(eventStore);
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
    void processorExceptionPropagates() {
        for (int i = 1; i <= 5; i++) {
            seedStateReported("attr", "v" + i);
        }
        ProjectionAdvancer advancer = ProjectionAdvancer.dispatching(eventStore);
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

    // ──────────────────────────────────────────────────────────────────
    // REC-28 dispatch — map-lookup-by-event-type over an injected handler set
    // ──────────────────────────────────────────────────────────────────

    @Test
    void dispatchesByEventTypeToInjectedHandlersWithDefaultFallback() {
        // Positions 1 and 3 are state_reported; position 2 is system_started
        // (an unmapped type). The injected map routes state_reported to a
        // recording handler; system_started must fall through to the default.
        seedStateReported("attr", "a");          // pos 1
        seedSystemStarted();                      // pos 2
        seedStateReported("attr", "c");           // pos 3

        List<Long> reportedSeen = new ArrayList<>();
        List<Long> defaultSeen = new ArrayList<>();
        EnvelopeHandler reportedHandler = (env, processor) -> {
            reportedSeen.add(env.globalPosition());
            processor.accept(env);
        };
        EnvelopeHandler defaultHandler = (env, processor) -> {
            defaultSeen.add(env.globalPosition());
            processor.accept(env);
        };

        DispatchingProjectionAdvancer advancer = new DispatchingProjectionAdvancer(
                eventStore,
                Map.of(EventTypes.STATE_REPORTED, reportedHandler),
                defaultHandler);

        List<Long> processed = new ArrayList<>();
        AdvanceResult result =
                advancer.advance(0L, 10, env -> processed.add(env.globalPosition()));

        assertThat(reportedSeen)
                .as("the state_reported handler receives only state_reported envelopes")
                .containsExactly(1L, 3L);
        assertThat(defaultSeen)
                .as("the unmapped system_started type falls through to the default handler")
                .containsExactly(2L);
        assertThat(processed)
                .as("every dispatched envelope still reaches the processor, in order")
                .containsExactly(1L, 2L, 3L);
        assertThat(result.eventsProcessed())
                .as("all three envelopes count toward eventsProcessed (no skip)")
                .isEqualTo(3);
        assertThat(result.lastProcessedPosition()).isEqualTo(3L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Seeding helpers
    // ──────────────────────────────────────────────────────────────────

    private void seedStateReported(String attributeKey, String value) {
        EventDraft draft = new EventDraft(
                EventTypes.STATE_REPORTED,
                1,
                null,
                SubjectRef.entity(new EntityId(UlidFactory.generate())),
                EventPriority.DIAGNOSTIC,
                EventOrigin.PHYSICAL,
                new StateReportedEvent(attributeKey, value, null, null, null),
                null,
                null);
        publish(draft);
    }

    private void seedSystemStarted() {
        EventDraft draft = new EventDraft(
                EventTypes.SYSTEM_STARTED,
                1,
                null,
                new SubjectRef(UlidFactory.generate(), SubjectType.SYSTEM),
                EventPriority.CRITICAL,
                EventOrigin.SYSTEM,
                new SystemStartedEvent("test", 0L),
                null,
                null);
        publish(draft);
    }

    private void publish(EventDraft draft) {
        try {
            eventStore.publishRoot(draft);
        } catch (SequenceConflictException sce) {
            throw new AssertionError("seed sequence conflict", sce);
        }
    }
}
