/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.device.StringValue;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.AdvanceResult;
import com.homesynapse.state.Availability;
import com.homesynapse.state.CheckpointPolicy;
import com.homesynapse.state.DerivationContext;
import com.homesynapse.state.DerivationRule;
import com.homesynapse.state.DerivedPublishGate;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.FixedCheckpointPolicy;
import com.homesynapse.state.InMemoryProjectionAdvancer;
import com.homesynapse.state.InMemoryStateStore;
import com.homesynapse.state.ProjectionAdvancer;
import com.homesynapse.state.ProjectionId;
import com.homesynapse.state.StateProjection;
import com.homesynapse.state.StateStore;
import com.homesynapse.state.ViewCheckpointStore;
import com.homesynapse.test.TestClock;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioral contract for {@link StateProjection} (AMD-41 §3.2).
 *
 * <p>Concrete subclasses (in the same JPMS package as {@link StateProjection},
 * because {@code SelfProducedFilter} is package-private) implement the
 * factory hook {@link #createProjection} and inherit the nine
 * projection-specific {@code @Test} methods plus four inherited subscriber
 * contract tests from {@link SubscriberContractTest}.</p>
 *
 * <p>This contract test wires real test fixtures (in-memory event store,
 * in-memory state store, in-memory checkpoint store, in-memory projection
 * advancer) rather than mocks. The same set of tests will also exercise the
 * future SQLite-backed implementation in M3.5b once
 * {@code SqliteStateStore}/{@code SqliteViewCheckpointStore} land.</p>
 *
 * @see StateProjection
 * @see SubscriberContractTest
 */
public abstract class StateProjectionContractTest extends SubscriberContractTest {

    /**
     * Default zero-arg constructor required by {@code -Xlint:all -Werror}.
     */
    protected StateProjectionContractTest() {
        // No initialization here; @BeforeEach sets up shared fixtures.
    }

    // ──────────────────────────────────────────────────────────────────
    // Shared fixtures (set up in @BeforeEach)
    // ──────────────────────────────────────────────────────────────────

    /** Deterministic clock; advancable via {@code clock.advance(Duration)}. */
    protected TestClock clock;

    /** Concurrent in-memory entity state store. */
    protected InMemoryStateStore stateStore;

    /** In-memory event store doubling as the EventPublisher delegate. */
    protected InMemoryEventStore eventStore;

    /** In-memory ViewCheckpointStore from the existing M1.9 testFixtures. */
    protected com.homesynapse.state.test.InMemoryViewCheckpointStore checkpointStore;

    /** Advancer over the event store — drives the batch path. */
    protected InMemoryProjectionAdvancer advancer;

    /** Recording publisher wrapping the eventStore (which implements EventPublisher). */
    protected SpyPublisher spyPublisher;

    /** Echo rule — produces state_changed when the reported value differs. */
    protected DerivationRule rule;

    /** Unbounded gate (no rate limiting) for default tests. */
    protected DerivedPublishGate publishGate;

    /** Projection under test. */
    protected StateProjection projection;

    /** Stable subject for derivation-triggering events. */
    protected SubjectRef testSubject;

    /** Entity ID matching {@link #testSubject}. */
    protected EntityId testEntityId;

    /**
     * Sets up shared fixtures and constructs the projection via
     * {@link #createProjection}. Concrete subclasses inherit and add their
     * own {@code @BeforeEach} if needed (JUnit 5 invokes parent then child).
     */
    @BeforeEach
    void setUpProjectionContract() {
        clock = TestClock.createDefault();
        stateStore = new InMemoryStateStore();
        eventStore = new InMemoryEventStore(clock);
        checkpointStore = new com.homesynapse.state.test.InMemoryViewCheckpointStore(clock);
        advancer = new InMemoryProjectionAdvancer(eventStore);
        spyPublisher = new SpyPublisher(eventStore);
        rule = new EchoStateRule();
        publishGate = DerivedPublishGate.unbounded();

        testEntityId = new EntityId(UlidFactory.generate());
        testSubject = SubjectRef.entity(testEntityId);

        projection = createProjection(
                new ProjectionId("state_projection"),
                1,
                checkpointStore,
                stateStore,
                rule,
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        projection.setMode(SubscriberMode.LIVE);
    }

    /**
     * Clears any system properties this contract test set during a single
     * test run. Runs after every test to ensure isolation.
     */
    @AfterEach
    void clearReconciliationFlag() {
        System.clearProperty(StateProjection.ALLOW_STALE_SNAPSHOTS_PROPERTY);
    }

    /**
     * Constructs a {@link StateProjection} using the supplied collaborators.
     * Implemented by the concrete subclass (in the same JPMS package as
     * {@code StateProjection}, so it can access the package-private
     * {@code SelfProducedFilter}).
     *
     * @param projectionId      identifier for the projection view
     * @param projectionVersion running code's projection version
     * @param checkpointStore   durable checkpoint storage
     * @param stateStore        port for materialized state
     * @param rule              derivation strategy
     * @param publisher         event publisher for derived events
     * @param advancer          projection advancer
     * @param checkpointPolicy  cadence policy
     * @param clock             injected clock
     * @param publishGate       rate-limit gate around derived publishes
     * @return a new projection
     */
    protected abstract StateProjection createProjection(
            ProjectionId projectionId,
            int projectionVersion,
            ViewCheckpointStore checkpointStore,
            StateStore stateStore,
            DerivationRule rule,
            EventPublisher publisher,
            ProjectionAdvancer advancer,
            CheckpointPolicy checkpointPolicy,
            Clock clock,
            DerivedPublishGate publishGate);

    // ──────────────────────────────────────────────────────────────────
    // SubscriberContractTest bridge
    // ──────────────────────────────────────────────────────────────────

    @Override
    protected Subscriber createSubscriberInLiveMode() {
        projection.setMode(SubscriberMode.LIVE);
        return projection;
    }

    @Override
    protected Subscriber createSubscriberInReplayMode() {
        projection.setMode(SubscriberMode.REPLAY);
        return projection;
    }

    @Override
    protected SpyPublisher spyPublisher() {
        return spyPublisher;
    }

    @Override
    protected SubjectRef derivingSubject() {
        return testSubject;
    }

    @Override
    protected long observedCursor(Subscriber subscriber) {
        if (subscriber instanceof StateProjection sp) {
            return sp.cursorPosition();
        }
        return Long.MIN_VALUE;
    }

    // ──────────────────────────────────────────────────────────────────
    // StateProjection-specific @Tests (9 total)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void readTxClosesBeforePublish() {
        // Seed one state_reported into the event store so the batch has work.
        seedStateReported(testSubject, "color", "blue");

        // Install the tx probe on the publisher — it samples at every publish.
        spyPublisher.installTxProbe(advancer::readTxInProgress);

        // Drive the batch through the advancer — the projection's READ phase
        // executes inside the advancer's tx; its PUBLISH phase executes after.
        AdvanceResult result = projection.processBatch(10);

        assertThat(result.eventsProcessed()).isEqualTo(1);
        assertThat(spyPublisher.publishCount()).as("derivation produced one publish")
                .isGreaterThan(0);
        assertThat(spyPublisher.txProbeRecorded()).as("probe was sampled")
                .isTrue();
        assertThat(spyPublisher.txStateAtLastPublish())
                .as("read tx must be closed before publish executes")
                .isFalse();
    }

    @Test
    void derivedEventCarriesIncrementedStateVersion() {
        int before = spyPublisher.publishCount();

        projection.onEvent(makeStateReportedEnvelope(testSubject, 1L, "color", "blue"));

        // Exactly one derived state_changed was published.
        assertThat(spyPublisher.publishCount()).isEqualTo(before + 1);
        EventEnvelope derived = lastPublished();
        assertThat(derived.payload()).isInstanceOf(StateChangedEvent.class);

        // State advances twice: once for state_reported (prior 0 → 1) and
        // once for derived state_changed (1 → 2).
        EntityState finalState = stateStore.get(testEntityId).orElseThrow();
        assertThat(finalState.stateVersion())
                .as("stateVersion advances for inbound and derived events")
                .isEqualTo(2L);
        assertThat(finalState.attributes())
                .containsEntry("color", new StringValue("blue"));
    }

    @Test
    void selfProducedFilterSuppressesReentrantDelivery() {
        // First delivery: state_reported triggers derivation + publish.
        projection.onEvent(makeStateReportedEnvelope(testSubject, 1L, "color", "blue"));
        int afterFirst = spyPublisher.publishCount();
        assertThat(afterFirst).as("first derivation publishes once").isEqualTo(1);

        // Redeliver the published derived state_changed — the bus would
        // re-route it to this projection. The self-filter must catch it.
        EventEnvelope redelivered = lastPublished();
        projection.onEvent(redelivered);

        assertThat(spyPublisher.publishCount())
                .as("filter suppresses re-derivation of self-produced event")
                .isEqualTo(afterFirst);
    }

    @Test
    void selfProducedFilterBypassedInReplay() {
        // LIVE: publish a derived event and record its ID in the filter.
        projection.onEvent(makeStateReportedEnvelope(testSubject, 1L, "color", "blue"));
        EventEnvelope derived = lastPublished();
        long versionAfterLive = stateStore.get(testEntityId).orElseThrow().stateVersion();

        // Switch to REPLAY and redeliver the same derived event. The filter
        // is bypassed in REPLAY (AMD-41 §3.2.2), so the projection applies the
        // event and stateVersion advances again.
        projection.setMode(SubscriberMode.REPLAY);
        projection.onEvent(derived);

        long versionAfterReplay = stateStore.get(testEntityId).orElseThrow().stateVersion();
        assertThat(versionAfterReplay)
                .as("REPLAY bypasses self-filter; state advances")
                .isGreaterThan(versionAfterLive);
    }

    @Test
    void stateVersionDefenceInDepthSuppressesEqualOrOlderDerivations() {
        // Build a projection with an AlwaysProducingRule so the derivation
        // unconditionally produces a state_changed draft even when no value
        // change occurred — the defence layer must catch it.
        StateProjection p = createProjection(
                new ProjectionId("defence-test"),
                1,
                checkpointStore,
                stateStore,
                new AlwaysProducingRule(),
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        // Pre-populate state so the entity already has the value the rule
        // would produce. Filter remains empty (no record() calls), so only
        // the defence-in-depth check can block the publish.
        SubjectRef defenceSubject = SubscriberContractTest.freshEntitySubject();
        EntityId defenceEntityId = new EntityId(defenceSubject.id());
        Instant now = clock.instant();
        stateStore.put(defenceEntityId, new EntityState(
                defenceEntityId,
                Map.of("color", new StringValue("blue")),
                Availability.AVAILABLE,
                5L,
                now,
                now,
                now,
                null,
                false));

        int before = spyPublisher.publishCount();

        // Deliver a state_reported with the SAME value (no real change).
        p.onEvent(makeStateReportedEnvelope(defenceSubject, 1L, "color", "blue"));

        assertThat(spyPublisher.publishCount())
                .as("defence-in-depth suppresses publish when newValue equals current")
                .isEqualTo(before);
    }

    @Test
    void derivedWriteRateLimitedAt200() {
        // FiniteBudgetGate simulates a token bucket with NO refills — once
        // the 200-token budget is exhausted, acquire() throws.
        AtomicInteger remaining = new AtomicInteger(200);
        DerivedPublishGate finiteBudget = () -> {
            if (remaining.getAndDecrement() <= 0) {
                throw new InterruptedException("budget exhausted");
            }
        };

        StateProjection p = createProjection(
                new ProjectionId("rate-test"),
                1,
                checkpointStore,
                stateStore,
                rule,
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                finiteBudget);
        p.setMode(SubscriberMode.LIVE);

        int before = spyPublisher.publishCount();
        SubjectRef rateSubject = SubscriberContractTest.freshEntitySubject();

        // Drive 1000 unique state_reported events (each with a different
        // value, so each would derive a state_changed).
        for (int i = 1; i <= 1000; i++) {
            p.onEvent(makeStateReportedEnvelope(rateSubject, i, "level", "v" + i));
        }

        int produced = spyPublisher.publishCount() - before;
        assertThat(produced)
                .as("rate limit bounds derived publishes at 200")
                .isLessThanOrEqualTo(200);

        // Clear thread interrupt flag set by the projection's bail-out path.
        Thread.interrupted();
    }

    @Test
    void checkpointAdvancesAfterDerivedPublishesReturn() {
        // Seed 5 state_reported events into the event store.
        for (int i = 1; i <= 5; i++) {
            seedStateReported(testSubject, "level", "v" + i);
        }

        // A wrapper publisher that throws on the 4th publish call,
        // simulating a crash partway through the PUBLISH phase.
        AtomicInteger publishAttempts = new AtomicInteger(0);
        EventPublisher crashingPublisher = new EventPublisher() {
            @Override
            public EventEnvelope publish(EventDraft draft, CausalContext cause)
                    throws SequenceConflictException {
                int attempt = publishAttempts.incrementAndGet();
                if (attempt == 4) {
                    throw new RuntimeException("synthetic crash on publish #4");
                }
                return spyPublisher.publish(draft, cause);
            }

            @Override
            public EventEnvelope publishRoot(EventDraft draft)
                    throws SequenceConflictException {
                return spyPublisher.publishRoot(draft);
            }
        };

        StateProjection p = createProjection(
                new ProjectionId("crash-test"),
                1,
                checkpointStore,
                new InMemoryStateStore(),
                rule,
                crashingPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        long cursorBefore = p.cursorPosition();

        assertThatThrownBy(() -> p.processBatch(10))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("synthetic crash on publish #4");

        assertThat(p.cursorPosition())
                .as("cursor must not advance when PUBLISH phase crashes (INV-PROJ-04)")
                .isEqualTo(cursorBefore);
    }

    @Test
    void reconciliationOnVersionMismatch() {
        // Pre-populate state with an entity that the reconciliation pass
        // should wipe.
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        Instant now = clock.instant();
        stateStore.put(staleEntity, new EntityState(
                staleEntity, Map.of(), Availability.UNKNOWN,
                7L, now, now, now, null, false));

        // Seed a checkpoint with projectionVersion=1 (the in-memory store
        // hardcodes 1; the seed value is independent of the projection's
        // running version).
        String viewName = "recon-test";
        checkpointStore.writeCheckpoint(viewName, 100L, new byte[]{1, 2, 3});

        // Construct projection with version=2.
        StateProjection p = createProjection(
                new ProjectionId(viewName),
                2,
                checkpointStore,
                stateStore,
                rule,
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        // Trigger lazy initialization with one event for a fresh entity.
        SubjectRef freshSubject = SubscriberContractTest.freshEntitySubject();
        EntityId freshEntityId = new EntityId(freshSubject.id());
        p.onEvent(makeStateReportedEnvelope(freshSubject, 1L, "k", "v"));

        assertThat(stateStore.get(staleEntity))
                .as("reconciliation clears the stale entity")
                .isEmpty();
        assertThat(stateStore.get(freshEntityId))
                .as("fresh entity is materialized after reconciliation")
                .isPresent();
        assertThat(p.cursorPosition())
                .as("cursor reset to 0 by reconciliation, then advanced to 1 by the inbound event")
                .isEqualTo(1L);
    }

    @Test
    void reconciliationHonorsAllowStaleSnapshotsFlag() {
        System.setProperty(StateProjection.ALLOW_STALE_SNAPSHOTS_PROPERTY, "true");

        EntityId staleEntity = new EntityId(UlidFactory.generate());
        Instant now = clock.instant();
        EntityState staleEntityState = new EntityState(
                staleEntity, Map.of(), Availability.UNKNOWN,
                7L, now, now, now, null, false);
        stateStore.put(staleEntity, staleEntityState);

        String viewName = "recon-flag-test";
        checkpointStore.writeCheckpoint(viewName, 100L, new byte[]{1, 2, 3});

        StateProjection p = createProjection(
                new ProjectionId(viewName),
                2,
                checkpointStore,
                stateStore,
                rule,
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);

        SubjectRef freshSubject = SubscriberContractTest.freshEntitySubject();
        p.onEvent(makeStateReportedEnvelope(freshSubject, 1L, "k", "v"));

        assertThat(stateStore.get(staleEntity))
                .as("stale entity preserved when escape hatch is active")
                .isPresent();
        assertThat(p.cursorPosition())
                .as("cursor restored from stale checkpoint position (≥ 100)")
                .isGreaterThanOrEqualTo(100L);
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns the most recently published envelope.
     *
     * @return last published envelope
     */
    protected EventEnvelope lastPublished() {
        List<EventEnvelope> envelopes = spyPublisher.publishedEnvelopes();
        if (envelopes.isEmpty()) {
            throw new AssertionError("no envelopes published");
        }
        return envelopes.get(envelopes.size() - 1);
    }

    /**
     * Publishes a fresh state_reported into the event store for the given
     * subject. Used by tests that drive the batch path.
     */
    protected void seedStateReported(SubjectRef subject, String attributeKey,
                                     String value) {
        EventDraft draft = new EventDraft(
                EventTypes.STATE_REPORTED,
                1,
                null,
                subject,
                EventPriority.DIAGNOSTIC,
                EventOrigin.PHYSICAL,
                new StateReportedEvent(attributeKey, value, null, null, null),
                null,
                null);
        try {
            eventStore.publishRoot(draft);
        } catch (SequenceConflictException sce) {
            throw new AssertionError("seed sequence conflict", sce);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Test derivation rules
    // ──────────────────────────────────────────────────────────────────

    /**
     * Produces a {@code state_changed} draft only when the inbound
     * {@code state_reported}'s value differs from the prior canonical value.
     */
    static final class EchoStateRule implements DerivationRule {

        EchoStateRule() {
            // Explicit constructor for -Xlint:all -Werror.
        }

        @Override
        public List<EventDraft> evaluate(DerivationContext context) {
            EventEnvelope env = context.envelope();
            if (!(env.payload() instanceof StateReportedEvent sr)) {
                return List.of();
            }
            String key = sr.attributeKey();
            String newValue = sr.value();
            String oldValue = lookupAttribute(context.priorState(), key);
            if (Objects.equals(oldValue, newValue)) {
                return List.of();
            }
            String oldNonNull = (oldValue == null) ? "" : oldValue;
            StateChangedEvent payload = new StateChangedEvent(
                    key, oldNonNull, newValue, env.eventId());
            EventDraft draft = new EventDraft(
                    EventTypes.STATE_CHANGED,
                    1,
                    env.eventTime(),
                    env.subjectRef(),
                    EventPriority.NORMAL,
                    EventOrigin.SYSTEM,
                    payload,
                    env.actorRef(),
                    null);
            return List.of(draft);
        }

        private static String lookupAttribute(EntityState prior, String key) {
            if (prior == null) {
                return null;
            }
            var v = prior.attributes().get(key);
            if (v == null) {
                return null;
            }
            return (v instanceof StringValue sv) ? sv.value() : v.rawValue().toString();
        }
    }

    /**
     * Produces a {@code state_changed} draft for every inbound
     * {@code state_reported}, regardless of whether the value differs from
     * the prior canonical value. Used by the defence-in-depth test, which
     * relies on the projection's secondary check rather than the rule's
     * first-line filter.
     */
    static final class AlwaysProducingRule implements DerivationRule {

        AlwaysProducingRule() {
            // Explicit constructor for -Xlint:all -Werror.
        }

        @Override
        public List<EventDraft> evaluate(DerivationContext context) {
            EventEnvelope env = context.envelope();
            if (!(env.payload() instanceof StateReportedEvent sr)) {
                return List.of();
            }
            StateChangedEvent payload = new StateChangedEvent(
                    sr.attributeKey(), "", sr.value(), env.eventId());
            EventDraft draft = new EventDraft(
                    EventTypes.STATE_CHANGED,
                    1,
                    env.eventTime(),
                    env.subjectRef(),
                    EventPriority.NORMAL,
                    EventOrigin.SYSTEM,
                    payload,
                    env.actorRef(),
                    null);
            return List.of(draft);
        }
    }

}
