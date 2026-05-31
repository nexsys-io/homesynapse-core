/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.StringValue;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventCategory;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
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
import com.homesynapse.state.StateCheckpointSource;
import com.homesynapse.state.StateProjection;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateStore;
import com.homesynapse.state.ViewCheckpointStore;
import com.homesynapse.test.TestClock;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Default {@link StateCheckpointSource#stub() stub} source — preserves the
     * M3.5a {@code byte[0]} write behavior and reports
     * {@code loadedProjectionVersion() == 0}. Tests that need a real source
     * construct their own and pass it to {@link #createProjection}.
     */
    protected StateCheckpointSource checkpointSource;

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
        // M4.0b-1: the production rule replaces the lifted EchoStateRule fixture
        // so every inherited contract test pins the production change-detect
        // behaviour identically to the fixture it was lifted from.
        rule = DerivationRule.production();
        publishGate = DerivedPublishGate.unbounded();
        checkpointSource = StateCheckpointSource.stub();

        testEntityId = new EntityId(UlidFactory.generate());
        testSubject = SubjectRef.entity(testEntityId);

        projection = createProjection(
                new ProjectionId("state_projection"),
                1,
                checkpointStore,
                checkpointSource,
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
     * @param checkpointSource  source of serialized checkpoint data and the
     *                          loaded projection version
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
            StateCheckpointSource checkpointSource,
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
                checkpointSource,
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
                checkpointSource,
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
                checkpointSource,
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

        // Seed a checkpoint. The seeded byte payload is opaque to the
        // contract test; reconciliation is driven by the StateCheckpointSource's
        // loadedProjectionVersion() (default stub returns 0, mismatch with the
        // projection's version 2 → reconciliation).
        String viewName = "recon-test";
        checkpointStore.writeCheckpoint(viewName, 100L, new byte[]{1, 2, 3});

        // Construct projection with version=2.
        StateProjection p = createProjection(
                new ProjectionId(viewName),
                2,
                checkpointStore,
                checkpointSource,
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
                checkpointSource,
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
    // M4.0b-1 — production DerivationRule contracts (REC-28, AMD-41 §3.2.2,
    // INV-PROJ-01). The shared `rule` is DerivationRule.production(), so the
    // nine inherited contract tests above already pin the production rule's
    // LIVE publish + materialization behaviour (e.g.
    // derivedEventCarriesIncrementedStateVersion proves the attributes map is
    // populated for a new state_reported on LIVE — Success Criterion 2). The
    // tests below add the REPLAY-no-publish proof, determinism, the
    // no-change branch, and rebuild-idempotency.
    // ──────────────────────────────────────────────────────────────────

    @Test
    void productionRuleReplayReDerivesButDoesNotPublishOrApplyDerived() {
        // REPLAY: a differing state_reported that WOULD publish on LIVE. The
        // projection still evaluates the rule (re-derives) but must NOT publish
        // the derived state_changed and must NOT apply it (AMD-41 §3.2.2). The
        // entry path is onEvent — its publish gate is `mode == LIVE`
        // (StateProjection.onEvent), the same gate processBatch uses.
        InMemoryStateStore replayStore = new InMemoryStateStore();
        StateProjection replayProjection = createProjection(
                new ProjectionId("m40b1-replay-no-publish"),
                1,
                checkpointStore,
                checkpointSource,
                replayStore,
                DerivationRule.production(),
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        replayProjection.setMode(SubscriberMode.REPLAY);

        SubjectRef replaySubject = SubscriberContractTest.freshEntitySubject();
        EntityId replayEntityId = new EntityId(replaySubject.id());
        int beforeReplay = spyPublisher.publishCount();

        replayProjection.onEvent(
                makeStateReportedEnvelope(replaySubject, 1L, "color", "blue"));

        assertThat(spyPublisher.publishCount())
                .as("REPLAY re-derives but must NOT publish the derived state_changed")
                .isEqualTo(beforeReplay);
        EntityState replayState = replayStore.get(replayEntityId).orElseThrow();
        assertThat(replayState.attributes())
                .as("the derived state_changed is NOT applied under REPLAY "
                        + "(state_reported alone does not populate attributes)")
                .doesNotContainKey("color");
        assertThat(replayState.stateVersion())
                .as("the inbound state_reported is still applied (stateVersion advances)")
                .isGreaterThan(0L);

        // LIVE: the SAME kind of differing input publishes exactly once and the
        // attribute IS populated (queried through the projection's StateStore).
        InMemoryStateStore liveStore = new InMemoryStateStore();
        StateProjection liveProjection = createProjection(
                new ProjectionId("m40b1-live-publish"),
                1,
                checkpointStore,
                checkpointSource,
                liveStore,
                DerivationRule.production(),
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        liveProjection.setMode(SubscriberMode.LIVE);

        SubjectRef liveSubject = SubscriberContractTest.freshEntitySubject();
        EntityId liveEntityId = new EntityId(liveSubject.id());
        int beforeLive = spyPublisher.publishCount();

        liveProjection.onEvent(
                makeStateReportedEnvelope(liveSubject, 2L, "color", "blue"));

        assertThat(spyPublisher.publishCount())
                .as("LIVE publishes the derived state_changed exactly once")
                .isEqualTo(beforeLive + 1);
        assertThat(lastPublished().payload())
                .as("the published derived event is a state_changed")
                .isInstanceOf(StateChangedEvent.class);
        EntityState liveState = liveStore.get(liveEntityId).orElseThrow();
        assertThat(liveState.attributes())
                .as("LIVE applies the published derived state_changed; attribute populated")
                .containsEntry("color", new StringValue("blue"));
    }

    @Test
    void productionRuleIsDeterministicAcrossRepeatedInvocations() {
        DerivationRule production = DerivationRule.production();
        // ONE envelope reused across every evaluation so the derived
        // triggeredBy (env.eventId()) is stable — the drafts must be equal.
        EventEnvelope env = makeStateReportedEnvelope(testSubject, 1L, "color", "blue");

        // AMD-50 §2.4 removed the Clock from DerivationContext, so determinism is
        // now structural: there is no clock value to branch on (AMD-50-INV-03), and
        // the rule is a pure function of (priorState, envelope). Repeated
        // invocations on the same tuple must yield identical drafts — the property
        // the reconciliation backfill relies on when it re-executes the rule during
        // a replay-from-zero rebuild. (The former two-fixed-clocks assertion is gone
        // because the context can no longer carry a clock to vary.)
        List<EventDraft> first =
                production.evaluate(new DerivationContext(null, env));
        List<EventDraft> repeated =
                production.evaluate(new DerivationContext(null, env));

        assertThat(first).hasSize(1);
        assertThat(repeated)
                .as("identical (priorState, envelope) yields identical drafts (AMD-50-INV-03)")
                .isEqualTo(first);
    }

    @Test
    void productionRuleEmitsNothingWhenValueUnchanged() {
        DerivationRule production = DerivationRule.production();
        EntityId entityId = new EntityId(UlidFactory.generate());
        Instant now = clock.instant();
        EntityState prior = new EntityState(
                entityId,
                Map.of("color", new StringValue("blue")),
                Availability.AVAILABLE,
                3L, now, now, now, null, false);
        EventEnvelope env =
                makeStateReportedEnvelope(SubjectRef.entity(entityId), 1L, "color", "blue");

        List<EventDraft> drafts =
                production.evaluate(new DerivationContext(prior, env));

        assertThat(drafts)
                .as("no derived event when the reported value equals the prior canonical value")
                .isEmpty();
    }

    @Test
    void rebuildIdempotency_replayingSameLogTwiceYieldsIdenticalMaterializedAttributes() {
        // Build a fixed inbound state_reported log of distinct entities and values.
        // Replaying it through two fresh projections must yield identical
        // materialized state per entity: rebuild(log) == rebuild(rebuild(log)).
        //
        // M4.0b-2 completes the assertions M4.0b-1 deferred: this now also rebuilds
        // via the AMD-50 one-shot backfill (a 1->2-shaped reconciliation, gate
        // active) and asserts idempotency on attribute VALUES + stateVersion +
        // lastChanged, plus the no-double-increment property (INV-01). Full
        // EntityState equality is deliberately NOT asserted: lastUpdated/lastReported
        // are wall-clock-stamped by applyToState (all branches, pre-existing) and so
        // are not rebuild-deterministic in general — under a fixed test clock they
        // would coincidentally match and mask exactly the non-determinism this WU
        // must avoid claiming away. lastChanged IS asserted because the backfill
        // sources it from the causing event's (log-fixed) time (Contract 3).
        List<EventEnvelope> inbound = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            SubjectRef subj = SubscriberContractTest.freshEntitySubject();
            inbound.add(makeStateReportedEnvelope(subj, i + 1L, "color", "v" + i));
        }

        // (a) LIVE rebuild idempotency (the M4.0b-1 slice — attribute values).
        Map<EntityId, Map<String, AttributeValue>> liveFirst =
                materializeAttributes(inbound, "m40b2-live-rebuild-1");
        Map<EntityId, Map<String, AttributeValue>> liveSecond =
                materializeAttributes(inbound, "m40b2-live-rebuild-2");
        assertThat(liveSecond)
                .as("LIVE rebuild(log) == rebuild(rebuild(log)) — deterministic attributes")
                .isEqualTo(liveFirst);

        // (b) BACKFILL rebuild idempotency (AMD-50): two 1->2 reconciliations over
        // the same state_reported log reconstruct identical (attributes,
        // stateVersion, lastChanged) per entity.
        Map<EntityId, BackfillSignature> backfillFirst =
                materializeViaBackfill(inbound, "m40b2-backfill-rebuild-1");
        Map<EntityId, BackfillSignature> backfillSecond =
                materializeViaBackfill(inbound, "m40b2-backfill-rebuild-2");
        assertThat(backfillSecond)
                .as("backfill rebuild is deterministic across (attributes, stateVersion, lastChanged)")
                .isEqualTo(backfillFirst);

        // Structural + no-double-increment checks.
        assertThat(backfillFirst).as("each entity was materialized").hasSize(5);
        assertThat(backfillFirst.values()).allSatisfy(sig -> {
            assertThat(sig.attributes())
                    .as("the backfill reconstructed the historical attribute")
                    .containsKey("color");
            assertThat(sig.stateVersion())
                    .as("one state_reported per entity -> stateVersion 1; the backfill "
                            + "draft carries NO second increment (INV-01, no double-count)")
                    .isEqualTo(1L);
        });
        // Backfill attribute values equal the LIVE rebuild's values for the same
        // entity (same final canonical value) — attribute-level backfill ≡ native.
        backfillFirst.forEach((id, sig) ->
                assertThat(sig.attributes())
                        .as("backfill attributes equal the native LIVE rebuild's for " + id)
                        .isEqualTo(liveFirst.get(id)));
    }

    /**
     * Materializes the given inbound log through a fresh LIVE projection (using
     * the production rule) and returns a snapshot of each entity's attribute
     * map. Used by the rebuild-idempotency test.
     *
     * @param inbound  the inbound envelopes to deliver in order
     * @param viewName a distinct projection/view name so repeated rebuilds do
     *                 not share checkpoint state
     * @return an entityId -> attributes snapshot of the materialized state
     */
    private Map<EntityId, Map<String, AttributeValue>> materializeAttributes(
            List<EventEnvelope> inbound, String viewName) {
        InMemoryStateStore store = new InMemoryStateStore();
        StateProjection p = createProjection(
                new ProjectionId(viewName),
                1,
                checkpointStore,
                checkpointSource,
                store,
                DerivationRule.production(),
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        p.setMode(SubscriberMode.LIVE);
        for (EventEnvelope env : inbound) {
            p.onEvent(env);
        }
        Map<EntityId, Map<String, AttributeValue>> snapshot = new HashMap<>();
        store.getAll().forEach((id, state) -> snapshot.put(id, state.attributes()));
        return snapshot;
    }

    /**
     * Materializes the given inbound {@code state_reported} log through a fresh
     * projection driven via the AMD-50 reconciliation backfill: projectionVersion
     * 2 against the stub source's {@code loadedProjectionVersion() == 0}, with a
     * seeded checkpoint, so {@code initialize()} clears state, resets the cursor to
     * 0, and opens the backfill gate (a 1->2-shaped transition). Events are
     * delivered via {@code onEvent} in REPLAY — the active production replay path —
     * so re-derived {@code state_changed} drafts are applied to in-memory state
     * without being published. Returns a per-entity signature of the
     * rebuild-deterministic fields.
     *
     * @param inbound  the inbound envelopes to deliver in order
     * @param viewName a distinct projection/view name so repeated rebuilds do not
     *                 share checkpoint state
     * @return an entityId -> (attributes, stateVersion, lastChanged) snapshot
     */
    private Map<EntityId, BackfillSignature> materializeViaBackfill(
            List<EventEnvelope> inbound, String viewName) {
        InMemoryStateStore store = new InMemoryStateStore();
        // Seed a checkpoint so initialize() consults the source; version 2 vs the
        // stub's loadedProjectionVersion 0 forces the reconciliation backfill.
        checkpointStore.writeCheckpoint(viewName, 0L, new byte[]{1});
        StateProjection p = createProjection(
                new ProjectionId(viewName),
                2,
                checkpointStore,
                checkpointSource,        // stub -> loadedProjectionVersion() == 0
                store,
                DerivationRule.production(),
                spyPublisher,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate);
        p.setMode(SubscriberMode.REPLAY);
        for (EventEnvelope env : inbound) {
            p.onEvent(env);
        }
        Map<EntityId, BackfillSignature> snapshot = new HashMap<>();
        store.getAll().forEach((id, state) -> snapshot.put(id, new BackfillSignature(
                state.attributes(), state.stateVersion(), state.lastChanged())));
        return snapshot;
    }

    /**
     * The rebuild-deterministic subset of {@link EntityState} compared by the
     * backfill rebuild-idempotency test: attribute values, the cursor, and the
     * event-time-sourced {@code lastChanged}. Deliberately excludes
     * {@code lastUpdated}/{@code lastReported} (wall-clock-stamped, not
     * rebuild-deterministic).
     *
     * @param attributes   the materialized attribute map
     * @param stateVersion the per-entity idempotency cursor
     * @param lastChanged  the event-time-sourced last-changed instant
     */
    private record BackfillSignature(
            Map<String, AttributeValue> attributes,
            long stateVersion,
            Instant lastChanged) { }

    // ──────────────────────────────────────────────────────────────────
    // M4.0b-5 — AMD-53 event-time activity-timestamp materialization
    // (AMD-53-INV-01 / -02). §5 #1 (the gate), #3 (carve-out), #6 (no-op).
    // ──────────────────────────────────────────────────────────────────

    @Test
    void liveEqualsReplayFromZeroForAllThreeActivityTimestamps() {
        // GATE (AMD-53 §5 #1, AMD-53-INV-01): process a multi-entity state_reported
        // log LIVE, capture each entity's lastChanged/lastUpdated/lastReported; rebuild
        // the same log from position 0 (the reconciliation replay-from-zero); assert the
        // THREE timestamps are identical per entity. This is the test that makes Nick's
        // §2.4 caveat a gate: it fails if lastUpdated/lastReported are not event-time
        // post-replay, not only lastChanged.
        //
        // The corpus event-times are all in 2025-09 while the projection Clock is fixed
        // at 2026-01-01 (TestClock.createDefault) — so every captured value differs from
        // clock.instant() and a wall-clock regression cannot pass.
        SubjectRef sA = SubscriberContractTest.freshEntitySubject();
        SubjectRef sB = SubscriberContractTest.freshEntitySubject();
        EntityId idA = new EntityId(sA.id());
        EntityId idB = new EntityId(sB.id());
        Instant t1 = Instant.parse("2025-09-15T08:00:00Z");
        Instant t2 = Instant.parse("2025-09-15T08:05:00Z");
        Instant t3 = Instant.parse("2025-09-15T08:02:00Z");
        List<EventEnvelope> corpus = List.of(
                reportedEnvelopeAt(sA, 1L, "level", "10", t1, t1),
                reportedEnvelopeAt(sB, 2L, "level", "5", t3, t3),
                reportedEnvelopeAt(sA, 3L, "level", "20", t2, t2));

        Map<EntityId, ActivityStamps> live = liveActivityStamps(corpus, "amd53-gate-live");
        Map<EntityId, ActivityStamps> replay = backfillActivityStamps(corpus, "amd53-gate-replay");

        assertThat(replay)
                .as("LIVE ≡ replay-from-zero across ALL THREE activity timestamps "
                        + "(fails if lastUpdated/lastReported are not event-time post-replay)")
                .isEqualTo(live);

        // The captured values ARE the event-times (last write per entity), not the clock.
        assertThat(live.get(idA))
                .as("entity A: last report at t2 governs all three activity timestamps")
                .isEqualTo(new ActivityStamps(t2, t2, t2));
        assertThat(live.get(idB))
                .as("entity B: single report at t3 governs all three activity timestamps")
                .isEqualTo(new ActivityStamps(t3, t3, t3));
        assertThat(t2).as("the gate clock differs from the corpus event-times")
                .isNotEqualTo(clock.instant());
        assertThat(t3).as("the gate clock differs from the corpus event-times")
                .isNotEqualTo(clock.instant());
    }

    @Test
    void noOpReportAdvancesLastUpdatedAndLastReportedButKeepsLastChanged() {
        // AMD-53 §5 #6 + Doc 03 §3.2 LIVE contract: a state_reported whose value matches
        // canonical state advances lastUpdated/lastReported (to the report's event-time)
        // and stateVersion, but leaves lastChanged unchanged.
        SubjectRef subject = SubscriberContractTest.freshEntitySubject();
        EntityId entityId = new EntityId(subject.id());
        Instant t1 = Instant.parse("2025-09-15T10:00:00Z");
        Instant t2 = Instant.parse("2025-09-15T10:30:00Z");

        InMemoryStateStore store = new InMemoryStateStore();
        StateProjection p = createProjection(
                new ProjectionId("amd53-noop"), 1, checkpointStore, checkpointSource,
                store, DerivationRule.production(), spyPublisher, advancer,
                FixedCheckpointPolicy.HOME_DEFAULT, clock, publishGate);
        p.setMode(SubscriberMode.LIVE);

        // First report establishes "blue" and lastChanged = t1 (via the derived
        // state_changed, event-time-sourced).
        p.onEvent(reportedEnvelopeAt(subject, 1L, "color", "blue", t1, t1));
        EntityState afterFirst = store.get(entityId).orElseThrow();
        assertThat(afterFirst.lastChanged()).isEqualTo(t1);
        assertThat(afterFirst.attributes()).containsEntry("color", new StringValue("blue"));
        long versionAfterFirst = afterFirst.stateVersion();

        // Second report, SAME value at t2: the production rule derives nothing, so
        // lastChanged is untouched; the state_reported still advances
        // lastUpdated/lastReported (to t2) and stateVersion.
        int publishesBefore = spyPublisher.publishCount();
        p.onEvent(reportedEnvelopeAt(subject, 2L, "color", "blue", t2, t2));

        assertThat(spyPublisher.publishCount())
                .as("a no-op report derives no state_changed")
                .isEqualTo(publishesBefore);
        EntityState afterSecond = store.get(entityId).orElseThrow();
        assertThat(afterSecond.lastChanged())
                .as("lastChanged unchanged across a no-op report (Doc 03 §3.2)")
                .isEqualTo(t1);
        assertThat(afterSecond.lastUpdated())
                .as("lastUpdated advances to the no-op report's event-time")
                .isEqualTo(t2);
        assertThat(afterSecond.lastReported())
                .as("lastReported advances to the no-op report's event-time")
                .isEqualTo(t2);
        assertThat(afterSecond.stateVersion())
                .as("stateVersion advances on every processed event, including the no-op report")
                .isGreaterThan(versionAfterFirst);
    }

    @Test
    void staleAfterAndStaleStayRealTimeIndependentOfEventTimeActivityTimestamps() {
        // AMD-53 §5 #3 / AMD-53-INV-02 (carve-out): staleAfter/stale are the ONLY
        // real-time-clock-dependent fields on EntityState. `stale` is derived at read time
        // from the injected clock vs staleAfter and flips accordingly — independent of the
        // event-time activity timestamps, which the read does not touch. (This proves the
        // activity-timestamp change did not bleed into the staleness machinery.)
        EntityId entityId = new EntityId(UlidFactory.generate());
        Instant activity = Instant.parse("2025-09-15T08:00:00Z"); // event-time activity stamps
        Instant threshold = Instant.parse("2026-03-01T00:00:00Z"); // staleAfter target
        InMemoryStateStore store = new InMemoryStateStore();
        store.put(entityId, new EntityState(
                entityId, Map.of("level", new StringValue("7")), Availability.AVAILABLE,
                4L, activity, activity, activity, threshold, false));

        // Read BEFORE the threshold -> not stale; activity timestamps untouched.
        StateQueryService beforeView = StateQueryService.materialized(
                store, () -> SubscriberMode.LIVE, () -> 0L,
                TestClock.at(Instant.parse("2026-02-01T00:00:00Z")));
        EntityState before = beforeView.getState(entityId).orElseThrow();
        assertThat(before.stale()).as("clock before staleAfter -> not stale").isFalse();
        assertThat(before.lastChanged()).isEqualTo(activity);
        assertThat(before.lastUpdated()).isEqualTo(activity);
        assertThat(before.lastReported()).isEqualTo(activity);

        // Read AFTER the threshold -> stale flips true; activity timestamps STILL event-time.
        StateQueryService afterView = StateQueryService.materialized(
                store, () -> SubscriberMode.LIVE, () -> 0L,
                TestClock.at(Instant.parse("2026-04-01T00:00:00Z")));
        EntityState after = afterView.getState(entityId).orElseThrow();
        assertThat(after.stale()).as("clock after staleAfter -> stale").isTrue();
        assertThat(after.lastChanged())
                .as("the event-time activity timestamp is independent of the real-time stale flip")
                .isEqualTo(activity);
        assertThat(after.staleAfter())
                .as("staleAfter is the real-time target, not event-timed by the projection")
                .isEqualTo(threshold);
    }

    /**
     * The three activity timestamps captured for a rebuild-equivalence assertion.
     *
     * @param lastChanged  the event-time-sourced last-changed instant
     * @param lastUpdated  the event-time-sourced last-updated instant
     * @param lastReported the event-time-sourced last-reported instant
     */
    private record ActivityStamps(Instant lastChanged, Instant lastUpdated,
                                  Instant lastReported) { }

    /**
     * Materializes the inbound log through a fresh LIVE projection (production rule)
     * and snapshots each entity's three activity timestamps.
     */
    private Map<EntityId, ActivityStamps> liveActivityStamps(
            List<EventEnvelope> corpus, String viewName) {
        InMemoryStateStore store = new InMemoryStateStore();
        StateProjection p = createProjection(
                new ProjectionId(viewName), 1, checkpointStore, checkpointSource,
                store, DerivationRule.production(), spyPublisher, advancer,
                FixedCheckpointPolicy.HOME_DEFAULT, clock, publishGate);
        p.setMode(SubscriberMode.LIVE);
        for (EventEnvelope env : corpus) {
            p.onEvent(env);
        }
        return activityStampSnapshot(store);
    }

    /**
     * Materializes the inbound log through a fresh projection driven via the AMD-50
     * reconciliation backfill (projectionVersion 2 vs the stub source's
     * loadedProjectionVersion 0 + a seeded checkpoint, so {@code initialize()} clears
     * state, resets the cursor to 0, and opens the backfill gate — a from-zero replay),
     * delivered via {@code onEvent} in REPLAY. Snapshots each entity's three activity
     * timestamps. Per AMD-53 the timestamps come out identical to {@link #liveActivityStamps}.
     */
    private Map<EntityId, ActivityStamps> backfillActivityStamps(
            List<EventEnvelope> corpus, String viewName) {
        InMemoryStateStore store = new InMemoryStateStore();
        checkpointStore.writeCheckpoint(viewName, 0L, new byte[]{1});
        StateProjection p = createProjection(
                new ProjectionId(viewName), 2, checkpointStore, checkpointSource,
                store, DerivationRule.production(), spyPublisher, advancer,
                FixedCheckpointPolicy.HOME_DEFAULT, clock, publishGate);
        p.setMode(SubscriberMode.REPLAY);
        for (EventEnvelope env : corpus) {
            p.onEvent(env);
        }
        return activityStampSnapshot(store);
    }

    private static Map<EntityId, ActivityStamps> activityStampSnapshot(StateStore store) {
        Map<EntityId, ActivityStamps> snapshot = new HashMap<>();
        store.getAll().forEach((id, s) -> snapshot.put(id,
                new ActivityStamps(s.lastChanged(), s.lastUpdated(), s.lastReported())));
        return snapshot;
    }

    /**
     * Builds a {@code state_reported} envelope with an explicit {@code eventTime} AND
     * {@code ingestTime}, both distinct from the projection clock — so a wall-clock
     * regression in {@code applyToState} cannot pass the AMD-53 gate. Delivered directly
     * to {@code onEvent} (not round-tripped through the event store).
     */
    private EventEnvelope reportedEnvelopeAt(SubjectRef subject, long position,
                                             String key, String value,
                                             Instant eventTime, Instant ingestTime) {
        EventId eventId = EventId.of(UlidFactory.generate());
        return new EventEnvelope(
                eventId, EventTypes.STATE_REPORTED, 1, ingestTime, eventTime, subject,
                position, position, EventPriority.DIAGNOSTIC, EventOrigin.PHYSICAL,
                List.of(EventCategory.DEVICE_STATE), CausalContext.root(eventId.value()),
                null, new StateReportedEvent(key, value, null, null, null));
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
    //
    // The M3.5a EchoStateRule fixture was lifted into production as
    // com.homesynapse.state.ProductionDerivationRule (M4.0b-1, reached via
    // DerivationRule.production()) and is wired as the shared `rule` above, so
    // the change-detect logic is exercised by the inherited contract tests.
    // Only AlwaysProducingRule remains here — it deliberately violates the
    // change-detect contract to drive the projection's defence-in-depth check.

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
            // AMD-52: typed payload (oldValue null = first report; newValue StringValue —
            // this stand-in rule resolves no schema).
            StateChangedEvent payload = new StateChangedEvent(
                    sr.attributeKey(), null, new StringValue(sr.value()), env.eventId());
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
