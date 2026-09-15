/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_CLOCK;
import static com.homesynapse.automation.AutomationTestSupport.FIXED_INSTANT;
import static com.homesynapse.automation.AutomationTestSupport.automationId;
import static com.homesynapse.automation.AutomationTestSupport.entityId;
import static com.homesynapse.automation.AutomationTestSupport.eventId;
import static com.homesynapse.automation.AutomationTestSupport.mutableClock;
import static com.homesynapse.automation.AutomationTestSupport.str;
import static com.homesynapse.automation.AutomationTestSupport.ulid;
import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent.EvaluatedEntityState;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EXPLAIN-114c T8 — the IR-1 measurement kept as a guard: how many store pages
 * {@code StandardExplanationService.explainRun} reads to locate the NEWEST run's
 * {@code automation_triggered} against a retained type index of {@code N} markers, for
 * {@code N} in {@code {1_000, 20_000, 50_000}}. IR-1 measured, at {@code 6bd8508}, 2 / 40 / 100
 * {@code readByType} pages (the whole index, 500 per page); the by-id read (K2) makes the hit
 * cost {@code O(events in one minute)} instead.
 *
 * <p>Three seeds per {@code N}, each the IR-1 shape (the OLDEST run's full chain, {@code N - 2}
 * bare markers, the NEWEST run's full chain), differing only in the instants:
 * <ul>
 *   <li>{@link Seed#TICKING} — a ticking test clock advances one second per marker and the
 *       newest run's id is minted at its own marker's instant (DP-1): the hint window
 *       {@code [t - 1 s, t + 60 s)} holds that run's chain and at most the last minute of
 *       markers, so the hint finds it in one or two {@code readByTimeRange} pages and the
 *       fallback type scan never runs.</li>
 *   <li>{@link Seed#DISPLACED_ID} — every event at the ONE fixed instant, and the newest run's
 *       id minted an hour later: the window holds nothing, so the hint misses and the fallback
 *       walks the type index exactly as HEAD did — the IR-1 counts, kept honest.</li>
 *   <li>{@link Seed#SAME_INSTANT} — every event AND the run id at the one fixed instant (the
 *       plain {@code Clock.fixed} seed): the window holds the whole log, so the hint walks the
 *       whole log through {@code readByTimeRange} and hits at the end — a hit at the old cost,
 *       not a fallback. A fixed-clock seed defeats the hint's purpose by construction; it does
 *       not exercise the fallback.</li>
 * </ul>
 *
 * <p>Run ids are minted directly from an instant ({@link #ulidAt}), never through
 * {@code UlidFactory}, whose static monotonic guard would carry a later test class's instant
 * into this one and move the window. No wall clock, no {@code System.nanoTime}: the page
 * and envelope counts ARE the measurement (the §0 test-clock reminder). Every reading is
 * printed before any assertion so a red run still files all nine lines.
 */
@DisplayName("explainRun page count by run id — IR-1 as a guard (EXPLAIN-114c T8)")
final class ByIdReadPageCountTest {

    /** Retained {@code automation_triggered} index sizes IR-1 measured. */
    private static final int[] INDEX_SIZES = {1_000, 20_000, 50_000};

    /** IR-1's measured {@code readByType} pages for the newest run at each index size. */
    private static final int[] IR1_PAGES = {2, 40, 100};

    /** The hint's page bound for the newest run (P2: at most two pages at every N). */
    private static final int HINT_PAGE_BOUND = 2;

    /** Mirrors {@code StandardExplanationService.SCAN_BATCH} (private there). */
    private static final int SCAN_BATCH = 500;

    /** The displaced id's lead over its marker — outside the hint window by construction. */
    private static final Duration DISPLACEMENT = Duration.ofHours(1);

    private static long ulidCounter;

    private InMemoryEventStore store;

    /** How the seed's instants and the newest run's id relate (the class javadoc). */
    private enum Seed { TICKING, DISPLACED_ID, SAME_INSTANT }

    /** One measured line: the page counts explainRun(newest) cost under one seed. */
    private record Reading(int n, Seed seed, boolean newestFound, int typePages, int timeRangePages,
                           long typeEnvelopes, int lastMaxCount, boolean oldestFound) {
    }

    ByIdReadPageCountTest() {
    }

    @Test
    @DisplayName("newest run: the hint reads at most two pages on a ticking seed; the fallback keeps IR-1's 2 / 40 / 100")
    void newestRun_hintReadsAtMostTwoPages_fallbackReadsTheIr1Count() {
        List<Reading> readings = new ArrayList<>();
        for (int n : INDEX_SIZES) {
            for (Seed seed : Seed.values()) {
                readings.add(measure(n, seed));
            }
        }
        for (Reading r : readings) {
            System.out.println(line(r));
        }

        for (Reading r : readings) {
            int expectedIr1 = IR1_PAGES[indexOf(r.n())];
            assertThat(r.newestFound()).as("newest run found at N=%d under %s", r.n(), r.seed())
                    .isTrue();
            assertThat(r.oldestFound()).as("oldest run found at N=%d under %s", r.n(), r.seed())
                    .isTrue();
            assertThat(r.lastMaxCount()).as("maxCount at N=%d under %s", r.n(), r.seed())
                    .isEqualTo(SCAN_BATCH);
            switch (r.seed()) {
                case TICKING -> {
                    assertThat(r.timeRangePages())
                            .as("hint pages at N=%d (ticking seed)", r.n())
                            .isBetween(1, HINT_PAGE_BOUND);
                    assertThat(r.typePages())
                            .as("type-index pages at N=%d (ticking seed): the fallback never runs", r.n())
                            .isZero();
                }
                case DISPLACED_ID -> {
                    assertThat(r.timeRangePages())
                            .as("hint pages at N=%d (displaced id): one empty window read", r.n())
                            .isEqualTo(1);
                    assertThat(r.typePages())
                            .as("type-index pages at N=%d (displaced id): the IR-1 count", r.n())
                            .isEqualTo(expectedIr1);
                }
                case SAME_INSTANT -> {
                    assertThat(r.typePages())
                            .as("type-index pages at N=%d (same-instant seed): a hint hit, no fallback", r.n())
                            .isZero();
                    assertThat(r.timeRangePages())
                            .as("hint pages at N=%d (same-instant seed): the window holds the whole log", r.n())
                            .isGreaterThanOrEqualTo(expectedIr1);
                }
            }
        }
    }

    private Reading measure(int n, Seed seed) {
        AutomationTestSupport.MutableClock ticking = mutableClock();
        Clock clock = seed == Seed.TICKING ? ticking : FIXED_CLOCK;
        store = new InMemoryEventStore(clock);
        AutomationTestSupport.MapAutomationRegistry registry =
                new AutomationTestSupport.MapAutomationRegistry();
        AutomationId autoId = automationId();
        EntityId target = entityId();

        // Index entry #1: the OLDEST run (full chain), at the seed's first instant.
        RunId oldest = seedRun(autoId, target, ulidAt(clock.instant()), clock.instant());
        // Entries #2 .. #(N-1): bare markers, each its own run; the ticking seed steps a second each.
        for (int i = 0; i < n - 2; i++) {
            if (seed == Seed.TICKING) {
                ticking.advance(Duration.ofSeconds(1));
            }
            seedTriggeredMarker(automationId(), clock.instant());
        }
        // Index entry #N: the NEWEST run (full chain) — the last marker appended.
        if (seed == Seed.TICKING) {
            ticking.advance(Duration.ofSeconds(1));
        }
        Instant newestAt = clock.instant();
        Instant idAt = seed == Seed.DISPLACED_ID ? newestAt.plus(DISPLACEMENT) : newestAt;
        RunId newest = seedRun(autoId, target, ulidAt(idAt), newestAt);

        CountingEventStore counting = new CountingEventStore(store);
        ExplanationService service = ExplanationService.over(counting, registry);
        // No assertion here: a miss is a reading too, asserted after every line has printed.
        Optional<RunExplanation> explanation = service.explainRun(newest);
        int typePages = counting.reads(EventTypes.AUTOMATION_TRIGGERED);
        int timeRangePages = counting.timeRangeReads();
        long typeEnvelopes = counting.envelopes(EventTypes.AUTOMATION_TRIGGERED);
        int lastMaxCount = counting.lastMaxCount();

        boolean oldestFound = ExplanationService.over(store, registry).explainRun(oldest).isPresent();
        return new Reading(n, seed, explanation.isPresent(), typePages, timeRangePages,
                typeEnvelopes, lastMaxCount, oldestFound);
    }

    private static String line(Reading r) {
        return String.format(Locale.ROOT,
                "IR1-GUARD N=%d seed=%s newestFound=%b typePages=%d timeRangePages=%d"
                        + " typeEnvelopes=%d maxCount=%d oldestFound=%b",
                r.n(), r.seed(), r.newestFound(), r.typePages(), r.timeRangePages(),
                r.typeEnvelopes(), r.lastMaxCount(), r.oldestFound());
    }

    private static int indexOf(int n) {
        for (int i = 0; i < INDEX_SIZES.length; i++) {
            if (INDEX_SIZES[i] == n) {
                return i;
            }
        }
        throw new AssertionError("unmeasured N: " + n);
    }

    /**
     * A ULID whose 48-bit timestamp is exactly {@code at} (millisecond precision) and whose
     * random component is a per-class counter — deterministic, unique, and independent of
     * {@code UlidFactory}'s static monotonic guard.
     */
    private static Ulid ulidAt(Instant at) {
        long n = ++ulidCounter;
        return new Ulid((at.toEpochMilli() << 16) | (n & 0xFFFFL), n);
    }

    // ---- seeding: the IR-1 shape (StandardExplanationServiceTest's T3 conventions) ----

    /**
     * Seeds a full CONFIRMED run chain on its own (root) correlation with the given run id,
     * every envelope's {@code eventTime} at {@code at} — the {@code seedRun(...,
     * ConfirmKind.CONFIRMED)} path of {@link StandardExplanationServiceTest} with the id and
     * the instant made explicit.
     */
    private RunId seedRun(AutomationId autoId, EntityId target, Ulid runUlid, Instant at) {
        EventEnvelope trig = publishRootAt("state_changed", SubjectRef.entity(target),
                new StateChangedEvent("motion", str("idle"), str("active"), eventId()), at);
        Ulid corr = trig.causalContext().correlationId();
        Ulid trigId = trig.eventId().value();
        RunId runId = new RunId(runUlid);
        publishDerivedAt(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(runId.value(), trig.eventId(), List.of("t1"),
                        Map.of("action:0", Set.of(target)), "hash", 0), corr, trigId, at);
        publishDerivedAt(EventTypes.AUTOMATION_CONDITION_EVALUATED, SubjectRef.automation(autoId),
                new AutomationConditionEvaluatedEvent(runId.value(), 0, "StateCondition", true,
                        List.of(new EvaluatedEntityState(target, "motion", "active",
                                FIXED_INSTANT, null))), corr, trigId, at);
        publishDerivedAt(EventTypes.AUTOMATION_ACTION_STARTED, SubjectRef.automation(autoId),
                new AutomationActionStartedEvent(runId.value(), 0, "CommandAction", List.of(target)),
                corr, trigId, at);
        EventEnvelope cmd = publishDerivedAt(EventTypes.COMMAND_ISSUED, SubjectRef.entity(target),
                new CommandIssuedEvent(target.value(), "turn_on", "{\"level\":75}", 5000,
                        CommandIdempotency.IDEMPOTENT), corr, trigId, at);
        publishDerivedAt(EventTypes.STATE_CONFIRMED, SubjectRef.entity(target),
                new StateConfirmedEvent(cmd.eventId(), eventId(), "on", "true", "true", "exact"),
                corr, cmd.eventId().value(), at);
        publishDerivedAt(EventTypes.AUTOMATION_ACTION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationActionCompletedEvent(runId.value(), 0, "success", null), corr, trigId,
                at);
        publishDerivedAt(EventTypes.AUTOMATION_COMPLETED, SubjectRef.automation(autoId),
                new AutomationCompletedEvent(runId.value(), "COMPLETED", 1234L, 1, 1, null,
                        null), corr, trigId, at);
        return runId;
    }

    /** Seeds a bare {@code automation_triggered} marker on its own root correlation at {@code at}. */
    private void seedTriggeredMarker(AutomationId autoId, Instant at) {
        publishRootAt(EventTypes.AUTOMATION_TRIGGERED, SubjectRef.automation(autoId),
                new AutomationTriggeredEvent(ulid(), eventId(), List.of("t1"),
                        Map.of("action:0", Set.of(entityId())), "hash", 0), at);
    }

    private EventEnvelope publishRootAt(String eventType, SubjectRef subject, DomainEvent payload,
                                        Instant eventTime) {
        EventDraft draft = new EventDraft(eventType, 1, eventTime, subject,
                EventPriority.NORMAL, EventOrigin.AUTOMATION, payload, null, null);
        try {
            return store.publishRoot(draft);
        } catch (SequenceConflictException e) {
            throw new AssertionError("seed publish failed", e);
        }
    }

    private EventEnvelope publishDerivedAt(String eventType, SubjectRef subject, DomainEvent payload,
                                           Ulid correlationId, Ulid causationId, Instant eventTime) {
        EventDraft draft = new EventDraft(eventType, 1, eventTime, subject,
                EventPriority.NORMAL, EventOrigin.AUTOMATION, payload, null, null);
        try {
            return store.publish(draft, CausalContext.chain(correlationId, causationId));
        } catch (SequenceConflictException e) {
            throw new AssertionError("seed publish failed", e);
        }
    }

    /**
     * The EXPLAIN-114b instrument ({@code StandardExplanationServiceTest.CountingEventStore},
     * private there), extended for the by-id read: a read-through {@link EventStore} decorator
     * counting {@code readByType} calls and envelopes per event type, {@code readByTimeRange}
     * calls, and the last {@code maxCount} asked for. Delegates every read; reads no clock.
     */
    private static final class CountingEventStore implements EventStore {
        private final EventStore delegate;
        private final Map<String, Integer> readsByType = new HashMap<>();
        private final Map<String, Long> envelopesByType = new HashMap<>();
        private int timeRangeReads;
        private int lastMaxCount = -1;

        CountingEventStore(EventStore delegate) {
            this.delegate = delegate;
        }

        int reads(String eventType) {
            return readsByType.getOrDefault(eventType, 0);
        }

        long envelopes(String eventType) {
            return envelopesByType.getOrDefault(eventType, 0L);
        }

        int timeRangeReads() {
            return timeRangeReads;
        }

        int lastMaxCount() {
            return lastMaxCount;
        }

        @Override
        public EventPage readFrom(long afterPosition, int maxCount) {
            return delegate.readFrom(afterPosition, maxCount);
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
            readsByType.merge(eventType, 1, Integer::sum);
            lastMaxCount = maxCount;
            EventPage page = delegate.readByType(eventType, afterPosition, maxCount);
            envelopesByType.merge(eventType, (long) page.events().size(), Long::sum);
            return page;
        }

        @Override
        public EventPage readByTimeRange(Instant from, Instant to, long afterPosition, int maxCount) {
            timeRangeReads++;
            lastMaxCount = maxCount;
            return delegate.readByTimeRange(from, to, afterPosition, maxCount);
        }

        @Override
        public long latestPosition() {
            return delegate.latestPosition();
        }
    }
}
