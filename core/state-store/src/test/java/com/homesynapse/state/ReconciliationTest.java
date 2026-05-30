/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.StandardCapabilities;
import com.homesynapse.device.StringValue;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.event.test.InMemoryEventStore;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.test.InMemoryViewCheckpointStore;
import com.homesynapse.test.TestClock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for {@link StateProjection}'s reconciliation pass
 * (AMD-41 §3.2.4). Complements the broader {@code InMemoryStateProjectionTest}
 * by exercising the behavioral variants the brief enumerated: discards
 * checkpoint on upgrade mismatch, honors the {@code allow_stale_snapshots}
 * escape hatch, is idempotent across repeated mismatches, discards checkpoint
 * on downgrade mismatch (symmetric to upgrade), records the version-transition
 * metadata in the checkpoint data slot, and reads the authoritative loaded
 * version rather than the {@link CheckpointRecord} sentinel.
 *
 * <p>M4.0a (OR-M3-13) un-defers {@code reconciliationRecordsMetadataInDataSlot}:
 * {@link StateProjection#writeCheckpoint} now threads the reconciliation
 * metadata through the {@link StateCheckpointSource#serializeCheckpoint(int,
 * java.time.Instant, Integer, Integer)} overload, so a version transition is
 * recorded. {@code reconciledToVersion} is a downstream dependency — M4.0b's
 * backfill gate binds to it.</p>
 *
 * <p>M4.0a (REC-82) adds {@code reconciliationReadsLoadedVersionNotRecordSentinel}:
 * a regression guard proving reconciliation reads
 * {@link StateCheckpointSource#loadedProjectionVersion()}, not the
 * {@link CheckpointRecord#projectionVersion()} sentinel (hardcoded to 1).</p>
 *
 * <h2>Test fixture pattern</h2>
 *
 * <p>Each test wires a fresh {@link StateProjection} with a custom
 * {@link StateCheckpointSource} whose {@code loadedProjectionVersion()}
 * controls whether reconciliation fires. The projection's lazy {@code initialize()}
 * is triggered by the first {@link StateProjection#onEvent(EventEnvelope)}
 * call (in LIVE mode); after that one event, the reconciliation outcome can
 * be inspected via the state store contents and the projection's
 * {@link StateProjection#cursorPosition()}.</p>
 *
 * <p>The {@code allow_stale_snapshots} system property is cleared in
 * {@code @AfterEach} to prevent cross-test pollution.</p>
 *
 * @see StateProjection
 * @see StateCheckpointSource
 */
@DisplayName("StateProjection reconciliation pass (AMD-41 §3.2.4)")
class ReconciliationTest {

    private static final String VIEW_NAME = "reconcile-test";
    private static final byte[] SEEDED_PAYLOAD = new byte[]{1, 2, 3};

    /**
     * A log-fixed event time deliberately DISTINCT from the projection clock
     * (TestClock default {@code 2026-01-01T00:00:00Z}) so the AMD-50 backfill tests
     * can prove {@code lastChanged} is sourced from the causing event's eventTime,
     * not the projection wall-clock (Contract 3). Literal parse is whitelisted by
     * NO_DIRECT_TIME_ACCESS.
     */
    private static final Instant EVENT_TIME = Instant.parse("2025-09-15T08:30:00Z");

    private TestClock clock;
    private InMemoryStateStore stateStore;
    private InMemoryEventStore eventStore;
    private InMemoryViewCheckpointStore checkpointStore;
    private InMemoryProjectionAdvancer advancer;
    private DerivationRule noopRule;
    private DerivedPublishGate publishGate;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        stateStore = new InMemoryStateStore();
        eventStore = new InMemoryEventStore(clock);
        checkpointStore = new InMemoryViewCheckpointStore(clock);
        advancer = new InMemoryProjectionAdvancer(eventStore);
        noopRule = ctx -> List.of();
        publishGate = DerivedPublishGate.unbounded();
    }

    @AfterEach
    void clearSystemProperties() {
        // Reset the reconciliation escape hatch so it does not leak between
        // tests. Some tests deliberately set the property; clearing it after
        // every test (not only after the setters) is defence in depth.
        System.clearProperty(StateProjection.ALLOW_STALE_SNAPSHOTS_PROPERTY);
    }

    // ──────────────────────────────────────────────────────────────────
    // Tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("upgrade mismatch (persisted=1, runtime=2) discards checkpoint and clears state")
    void reconciliationDiscardsCheckpointOnVersionMismatch() throws SequenceConflictException {
        // Seed a pre-existing checkpoint at position 100 — the projection's
        // initialize() will consult the source and detect the version mismatch.
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        // Pre-existing entity that reconciliation must wipe.
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        stateStore.put(staleEntity, freshEntityState(staleEntity, 42L));

        StateProjection projection = createProjection(
                2 /* runtime version */,
                fixedSource(SEEDED_PAYLOAD, 1 /* persisted version */));
        projection.setMode(SubscriberMode.LIVE);

        // Drive lazy init via a single event for a fresh entity at position 1.
        EventEnvelope inbound = publishStateReported(freshSubject(), 1L);
        projection.onEvent(inbound);

        assertThat(stateStore.get(staleEntity))
                .as("upgrade mismatch must clear all pre-existing entities")
                .isEmpty();
        assertThat(projection.cursorPosition())
                .as("cursor reset to 0 by reconciliation, then advanced to 1 by the inbound event")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("allow_stale_snapshots=true preserves checkpoint despite version mismatch")
    void reconciliationHonorsAllowStaleSnapshotsFlag() throws SequenceConflictException {
        System.setProperty(StateProjection.ALLOW_STALE_SNAPSHOTS_PROPERTY, "true");
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        EntityId preserved = new EntityId(UlidFactory.generate());
        stateStore.put(preserved, freshEntityState(preserved, 42L));

        StateProjection projection = createProjection(
                2, fixedSource(SEEDED_PAYLOAD, 1));
        projection.setMode(SubscriberMode.LIVE);

        EventEnvelope inbound = publishStateReported(freshSubject(), 1L);
        projection.onEvent(inbound);

        assertThat(stateStore.get(preserved))
                .as("escape hatch preserves pre-existing entities")
                .isPresent();
        assertThat(projection.cursorPosition())
                .as("cursor restored from the persisted checkpoint (100), "
                        + "then advanced past the inbound event")
                .isGreaterThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("reconciliation is idempotent — repeated mismatched-version init is a no-op")
    void reconciliationIsIdempotent() throws SequenceConflictException {
        // First projection instance discovers and reconciles the mismatch.
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        stateStore.put(staleEntity, freshEntityState(staleEntity, 42L));

        StateProjection first = createProjection(
                2, fixedSource(SEEDED_PAYLOAD, 1));
        first.setMode(SubscriberMode.LIVE);
        first.onEvent(publishStateReported(freshSubject(), 1L));

        assertThat(stateStore.get(staleEntity))
                .as("first reconciliation clears state")
                .isEmpty();
        long firstCursor = first.cursorPosition();

        // A second projection instance (same view name, same version mismatch)
        // observes the same persisted checkpoint and reconciles again. The
        // operation must be a clean no-op — no exceptions, idempotent state
        // mutation. We seed a fresh "stale" entity to verify the second pass
        // also clears it without surprises.
        EntityId secondStale = new EntityId(UlidFactory.generate());
        stateStore.put(secondStale, freshEntityState(secondStale, 99L));

        StateProjection second = createProjection(
                2, fixedSource(SEEDED_PAYLOAD, 1));
        second.setMode(SubscriberMode.LIVE);
        second.onEvent(publishStateReported(freshSubject(), 2L));

        assertThat(stateStore.get(secondStale))
                .as("second reconciliation pass also clears state — idempotent")
                .isEmpty();
        assertThat(second.cursorPosition())
                .as("second cursor also reset to 0 then advanced by its inbound event")
                .isEqualTo(2L);
        // First projection's cursor is unchanged — independent projection
        // instances do not interfere.
        assertThat(first.cursorPosition())
                .as("first projection's cursor is independent of the second's")
                .isEqualTo(firstCursor);
    }

    @Test
    @DisplayName("downgrade mismatch (persisted=2, runtime=1) also discards checkpoint")
    void reconciliationOnDowngradeAlsoDiscards() throws SequenceConflictException {
        // Symmetric to upgrade: persisted version 2 > runtime version 1
        // also triggers reconciliation. The check is != not <, so downgrades
        // are treated identically to upgrades.
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        stateStore.put(staleEntity, freshEntityState(staleEntity, 42L));

        StateProjection projection = createProjection(
                1 /* runtime version (downgrade) */,
                fixedSource(SEEDED_PAYLOAD, 2 /* persisted version */));
        projection.setMode(SubscriberMode.LIVE);

        EventEnvelope inbound = publishStateReported(freshSubject(), 1L);
        projection.onEvent(inbound);

        assertThat(stateStore.get(staleEntity))
                .as("downgrade mismatch must clear state just like an upgrade mismatch")
                .isEmpty();
        assertThat(projection.cursorPosition())
                .as("cursor reset to 0 by reconciliation, then advanced to 1")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("OR-M3-13: reconciliation records from/to version + timestamp in the data slot")
    void reconciliationRecordsMetadataInDataSlot() throws SequenceConflictException {
        // Seed a pre-existing checkpoint so initialize() consults the source and
        // detects the mismatch. The recording source reports persisted version 1
        // against a projection running version 2 — the 1 -> 2 transition shape
        // M4.0b's backfill triggers. A 1-event checkpoint policy guarantees the
        // single inbound event flushes a checkpoint, capturing the metadata the
        // projection threads through serializeCheckpoint(...).
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD);
        RecordingCheckpointSource source =
                new RecordingCheckpointSource(SEEDED_PAYLOAD, 1 /* persisted version */);

        StateProjection projection = new StateProjection(
                new ProjectionId(VIEW_NAME),
                2 /* runtime version */,
                checkpointStore,
                source,
                AtomicCheckpointSink.viewOnly(checkpointStore),
                stateStore,
                noopRule,
                eventStore,
                advancer,
                new FixedCheckpointPolicy(1, Duration.ofHours(1)), // flush after 1 event
                clock,
                publishGate,
                new SelfProducedFilter(clock, Duration.ofSeconds(60)));
        projection.setMode(SubscriberMode.LIVE);

        projection.onEvent(publishStateReported(freshSubject(), 1L));

        assertThat(source.captured())
                .as("the checkpoint flush after reconciliation invoked the metadata overload")
                .isTrue();
        assertThat(source.lastReconciledToVersion())
                .as("reconciledToVersion carries the transition's target version "
                        + "(M4.0b's backfill gate binds to this — not optional polish)")
                .isEqualTo(2);
        assertThat(source.lastReconciledFromVersion())
                .as("reconciledFromVersion carries the persisted version that triggered "
                        + "reconciliation")
                .isEqualTo(1);
        assertThat(source.lastReconciledAt())
                .as("reconciledAt is stamped from the injected Clock")
                .isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("REC-82: reconciliation reads loadedProjectionVersion(), not the CheckpointRecord sentinel")
    void reconciliationReadsLoadedVersionNotRecordSentinel() throws SequenceConflictException {
        // The InMemoryViewCheckpointStore hardcodes CheckpointRecord.projectionVersion() = 1
        // (the sentinel). Construct a scenario where that sentinel (1) MATCHES the
        // running projection version (1) — so a buggy reconciliation that read the
        // sentinel would NOT fire — while the authoritative loadedProjectionVersion()
        // (2) does NOT match, so the correct reconciliation MUST fire. State being
        // cleared therefore proves the loaded value (not the sentinel) is read.
        checkpointStore.writeCheckpoint(VIEW_NAME, 100L, SEEDED_PAYLOAD); // record sentinel = 1
        EntityId staleEntity = new EntityId(UlidFactory.generate());
        stateStore.put(staleEntity, freshEntityState(staleEntity, 42L));

        StateProjection projection = createProjection(
                1 /* runtime version == record sentinel; a sentinel-reader would NOT reconcile */,
                fixedSource(SEEDED_PAYLOAD, 2 /* loadedProjectionVersion != runtime -> MUST reconcile */));
        projection.setMode(SubscriberMode.LIVE);

        projection.onEvent(publishStateReported(freshSubject(), 1L));

        assertThat(stateStore.get(staleEntity))
                .as("reconciliation fired because loadedProjectionVersion()=2 != projectionVersion=1, "
                        + "proving the loaded value (not the CheckpointRecord sentinel=1) is authoritative")
                .isEmpty();
        assertThat(projection.cursorPosition())
                .as("cursor reset to 0 by reconciliation, then advanced to 1 by the inbound event")
                .isEqualTo(1L);
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-50 reconciliation backfill (§2.1–§2.5, §5 tests 2–5 + generality)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AMD-50 §2.1: a 1->2 reconciliation reconstructs historical attributes from the state_reported log (event-time lastChanged)")
    void reconciliation1to2ReconstructsHistoricalAttributesWithEventTimeLastChanged()
            throws SequenceConflictException {
        // The pre-2 log holds only state_reported (the M4.0b-1 rule logged no
        // state_changed historically). A plain replay-from-zero would leave
        // attributes empty (AMD-50 §1.1); the backfill reconstructs them. This is
        // Success Criterion 3: query a populated historical attribute after a 1->2
        // reconciliation.
        SubjectRef subject = freshSubject();
        EntityId entityId = new EntityId(subject.id());
        EventEnvelope r1 = reportedAt(subject, "temperature_c", "20.0", EVENT_TIME);
        EventEnvelope r2 = reportedAt(subject, "temperature_c", "21.5", EVENT_TIME.plusSeconds(30));

        InMemoryStateStore store = new InMemoryStateStore();
        StateProjection p = projectionFor("recon-1to2", 1, 2,
                DerivationRule.production(), store, 0L);
        p.setMode(SubscriberMode.REPLAY);
        p.onEvent(r1);
        p.onEvent(r2);

        EntityState state = store.get(entityId).orElseThrow();
        assertThat(state.attributes().get("temperature_c"))
                .as("historical attribute reconstructed by the backfill (was dark before AMD-50)")
                .isEqualTo(new StringValue("21.5"));
        assertThat(state.stateVersion())
                .as("two state_reported -> stateVersion 2; backfill drafts carry no increment (INV-01)")
                .isEqualTo(2L);
        assertThat(state.lastChanged())
                .as("lastChanged is the causing event's log-fixed eventTime, NOT the projection clock")
                .isEqualTo(EVENT_TIME.plusSeconds(30));
        assertThat(state.lastChanged())
                .as("...and is therefore distinct from clock.instant() — the masking this WU forbids")
                .isNotEqualTo(clock.instant());
    }

    @Test
    @DisplayName("AMD-50 §5.2: backfill attribute values equal a native LIVE log's; stateVersion = state_reported count (no double-increment)")
    void backfillAttributeValuesEqualNativeButStateVersionIsReportCount()
            throws SequenceConflictException {
        // Native: a fresh LIVE projection (matching version, no reconciliation)
        // folds state_reported -> publishes + applies state_changed -> final attr
        // value AND a doubled stateVersion (report +1 and derived +1 each).
        SubjectRef nativeSubject = freshSubject();
        EntityId nativeId = new EntityId(nativeSubject.id());
        InMemoryStateStore nativeStore = new InMemoryStateStore();
        StateProjection live = projectionFor("native-live", 1, 1,
                DerivationRule.production(), nativeStore, 0L);
        live.setMode(SubscriberMode.LIVE);
        live.onEvent(reportedAt(nativeSubject, "level", "10", EVENT_TIME));
        live.onEvent(reportedAt(nativeSubject, "level", "20", EVENT_TIME.plusSeconds(5)));
        live.onEvent(reportedAt(nativeSubject, "level", "30", EVENT_TIME.plusSeconds(10)));
        EntityState nativeState = nativeStore.get(nativeId).orElseThrow();

        // Backfill: a fresh 1->2 reconciliation over an equivalent
        // state_reported-only log reconstructs the SAME final attribute value, but
        // stateVersion equals the state_reported count (3) — backfill drafts carry
        // no increment (INV-01).
        SubjectRef backfillSubject = freshSubject();
        EntityId backfillId = new EntityId(backfillSubject.id());
        InMemoryStateStore backfillStore = new InMemoryStateStore();
        StateProjection recon = projectionFor("native-backfill", 1, 2,
                DerivationRule.production(), backfillStore, 0L);
        recon.setMode(SubscriberMode.REPLAY);
        recon.onEvent(reportedAt(backfillSubject, "level", "10", EVENT_TIME));
        recon.onEvent(reportedAt(backfillSubject, "level", "20", EVENT_TIME.plusSeconds(5)));
        recon.onEvent(reportedAt(backfillSubject, "level", "30", EVENT_TIME.plusSeconds(10)));
        EntityState backfillState = backfillStore.get(backfillId).orElseThrow();

        assertThat(backfillState.attributes().get("level"))
                .as("backfill reconstructs the SAME attribute value as the native LIVE fold")
                .isEqualTo(nativeState.attributes().get("level"));
        assertThat(backfillState.stateVersion())
                .as("backfill stateVersion = the reconciliation log's event count (3 state_reported), "
                        + "NOT the native log's, with no double-increment (INV-01)")
                .isEqualTo(3L);
        assertThat(nativeState.stateVersion())
                .as("the native fold legitimately counts the derived state_changed too (3 + 3 = 6)")
                .isEqualTo(6L);
    }

    @Test
    @DisplayName("AMD-50 §5.3 (one-shot): a restart at matching version does NOT reconcile and the backfill does not run")
    void oneShotMatchingVersionDoesNotBackfill() throws SequenceConflictException {
        // persisted version == runtime version (2 == 2): no reconciliation, gate
        // inactive (AMD-50-INV-02). A pre-seeded entity survives; a differing
        // state_reported delivered in REPLAY is applied but its re-derived draft is
        // NOT backfilled (gate off) and NOT published (REPLAY) -> attribute dark.
        SubjectRef subject = freshSubject();
        EntityId entityId = new EntityId(subject.id());
        InMemoryStateStore store = new InMemoryStateStore();
        EntityId survivor = new EntityId(UlidFactory.generate());
        store.put(survivor, freshEntityState(survivor, 7L));

        StateProjection p = projectionFor("one-shot", 2, 2,
                DerivationRule.production(), store, 50L);
        p.setMode(SubscriberMode.REPLAY);
        p.onEvent(reportedAt(subject, "level", "99", EVENT_TIME));

        assertThat(store.get(survivor))
                .as("matching version => no reconciliation; pre-existing entity survives")
                .isPresent();
        assertThat(p.cursorPosition())
                .as("cursor restored from the persisted checkpoint (50), not reset to 0 — no reconciliation")
                .isGreaterThanOrEqualTo(50L);
        EntityState state = store.get(entityId).orElseThrow();
        assertThat(state.attributes())
                .as("backfill is dormant at matching version (AMD-50-INV-02); attribute NOT reconstructed")
                .doesNotContainKey("level");
        assertThat(state.stateVersion())
                .as("the inbound state_reported is still applied (cursor advances)")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("AMD-50 §5.4 (steady-state): gate-inactive replay applies logged state_changed once; re-derived drafts discarded")
    void steadyStateAppliesLoggedStateChangedOnceAndDiscardsReDerived()
            throws SequenceConflictException {
        // Matching version (2 == 2) -> no reconciliation, gate inactive. In a
        // REPLAY catch-up: a state_reported is applied but its re-derived draft is
        // discarded for state (gate off + REPLAY no-publish); a logged
        // state_changed is applied once as inbound (supersession is OFF outside the
        // gate), so it is the sole source of the attribute.
        SubjectRef subject = freshSubject();
        EntityId entityId = new EntityId(subject.id());
        InMemoryStateStore store = new InMemoryStateStore();
        StateProjection p = projectionFor("steady-state", 2, 2,
                DerivationRule.production(), store, 10L);
        p.setMode(SubscriberMode.REPLAY);

        p.onEvent(reportedAt(subject, "level", "42", EVENT_TIME));
        assertThat(store.get(entityId).orElseThrow().attributes())
                .as("re-derived draft discarded for state outside the gate")
                .doesNotContainKey("level");

        p.onEvent(changedAt(subject, "level", "", "42", EVENT_TIME.plusSeconds(1)));
        EntityState state = store.get(entityId).orElseThrow();
        assertThat(state.attributes().get("level"))
                .as("the logged state_changed is the sole source of the attribute "
                        + "(supersession OFF outside the gate)")
                .isEqualTo(new StringValue("42"));
        assertThat(state.stateVersion())
                .as("two log events applied once each (report +1, state_changed +1)")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("AMD-50 §5.5 (supersession/generality): an N->M reconciliation supersedes a spurious prior-version state_changed")
    void supersessionReconstructsCurrentRuleValueAndSuppressesSpuriousLoggedChange()
            throws SequenceConflictException {
        // Scenario 3.3 in miniature: under the prior rule a sensor reported 20.0
        // then 20.0000001 and a prior-version state_changed(20.0 -> 20.0000001) was
        // logged (string compare treated them as different). The current rule
        // (epsilon compare) does NOT treat the within-tolerance second report as a
        // change. The reconciliation must reconstruct 20.0 (the current rule's
        // value), suppressing the spurious logged state_changed — while stateVersion
        // still counts all 3 log events (the suppressed change advances the cursor,
        // INV-01). This is the regression guard for the AMD-50 review fix; it proves
        // a rule upgrade takes effect on historical data.
        SubjectRef subject = freshSubject();
        EntityId entityId = new EntityId(subject.id());
        EventEnvelope r1 = reportedAt(subject, "temperature_c", "20.0", EVENT_TIME);
        EventEnvelope r2 = reportedAt(subject, "temperature_c", "20.0000001",
                EVENT_TIME.plusSeconds(30));
        EventEnvelope spurious = changedAt(subject, "temperature_c", "20.0", "20.0000001",
                EVENT_TIME.plusSeconds(31));

        InMemoryStateStore store = new InMemoryStateStore();
        // A 2->3-shaped transition (loaded 2, runtime 3) with a tolerance rule.
        StateProjection p = projectionFor("supersession", 2, 3,
                new ToleranceRule(1e-3), store, 0L);
        p.setMode(SubscriberMode.REPLAY);
        p.onEvent(r1);
        p.onEvent(r2);
        p.onEvent(spurious);

        EntityState state = store.get(entityId).orElseThrow();
        assertThat(state.attributes().get("temperature_c"))
                .as("the current (tolerance) rule's value wins; the spurious within-tolerance change is gone")
                .isEqualTo(new StringValue("20.0"));
        assertThat(state.stateVersion())
                .as("3 log events processed (2 reports + the suppressed state_changed advances the cursor, INV-01)")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("AMD-50 D-1: the backfill also runs on the processBatch path (BOTH REPLAY paths gated)")
    void backfillAlsoRunsOnProcessBatchPath() throws SequenceConflictException {
        // The D-1 lesson: gate BOTH derivation paths. onEvent is covered by the
        // tests above; this drives the SAME backfill through processBatch (the
        // advancer-driven batch path). Events must be in the event store so the
        // advancer can read them.
        SubjectRef subject = freshSubject();
        EntityId entityId = new EntityId(subject.id());
        reportedAt(subject, "level", "1", EVENT_TIME);                 // pos 1
        reportedAt(subject, "level", "2", EVENT_TIME.plusSeconds(5));  // pos 2

        InMemoryStateStore store = new InMemoryStateStore();
        StateProjection p = projectionFor("batch-backfill", 1, 2,
                DerivationRule.production(), store, 0L);
        p.setMode(SubscriberMode.REPLAY);
        AdvanceResult result = p.processBatch(10);

        assertThat(result.eventsProcessed())
                .as("both state_reported processed in one batch")
                .isEqualTo(2);
        EntityState state = store.get(entityId).orElseThrow();
        assertThat(state.attributes().get("level"))
                .as("the backfill reconstructed the attribute via processBatch too (D-1: both paths gated)")
                .isEqualTo(new StringValue("2"));
        assertThat(state.stateVersion())
                .as("two state_reported -> stateVersion 2; backfill drafts carry no increment")
                .isEqualTo(2L);
    }

    // ──────────────────────────────────────────────────────────────────
    // AMD-51 typed comparator — the 2->3 transition + shouldPublishDerived coherence
    // (§5 tests #6 and #10), driven by the REAL production typed rule (not a stand-in).
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AMD-51 §5.6: a 2->3 typed reconciliation suppresses a within-epsilon report and supersedes the spurious logged state_changed")
    void typed2to3ReconciliationSuppressesWithinEpsilonChange()
            throws SequenceConflictException {
        // Under the version-2 (string) rule a sensor reported 20.0 then 20.0000000001 and a
        // prior-version state_changed(20.0 -> 20.0000000001) was logged (string compare saw a
        // change). The version-3 typed FLOAT rule does NOT treat the within-1e-9 second report
        // as a change. The 2->3 reconciliation reconstructs 20.0 (the current rule's value),
        // suppressing the spurious logged change — while stateVersion still counts all 3 log
        // events (INV-01). temperature_c is FLOAT in StandardCapabilities.
        SubjectRef subject = freshSubject();
        EntityId entityId = new EntityId(subject.id());
        EventEnvelope r1 = reportedAt(subject, "temperature_c", "20.0", EVENT_TIME);
        EventEnvelope r2 = reportedAt(subject, "temperature_c", "20.0000000001",
                EVENT_TIME.plusSeconds(30));
        EventEnvelope spurious = changedAt(subject, "temperature_c", "20.0", "20.0000000001",
                EVENT_TIME.plusSeconds(31));

        InMemoryStateStore store = new InMemoryStateStore();
        StateProjection p = projectionFor("typed-2to3", 2, 3, typedRule(), store, 0L);
        p.setMode(SubscriberMode.REPLAY);
        p.onEvent(r1);
        p.onEvent(r2);
        p.onEvent(spurious);

        EntityState state = store.get(entityId).orElseThrow();
        assertThat(state.attributes().get("temperature_c"))
                .as("the within-epsilon second report is suppressed; the spurious logged "
                        + "change is superseded by the typed rule's re-derivation")
                .isEqualTo(new StringValue("20.0"));
        assertThat(state.stateVersion())
                .as("3 log events advance the cursor (2 reports + the superseded "
                        + "state_changed, INV-01)")
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("AMD-51 §5.10: shouldPublishDerived stays coherent with the typed verdict on LIVE")
    void typedRuleStaysCoherentWithStringPublishGuard() throws SequenceConflictException {
        // Matching versions (3 == 3) => no reconciliation, gate inactive, plain LIVE. The
        // typed FLOAT rule must emit only genuine changes; the string-based shouldPublishDerived
        // guard must neither suppress a genuine typed-changed emit nor be reached when the
        // typed rule emits nothing.
        SubjectRef subject = freshSubject();
        EntityId entityId = new EntityId(subject.id());
        InMemoryStateStore store = new InMemoryStateStore();
        StateProjection p = projectionFor("typed-live", 3, 3, typedRule(), store, 0L);
        p.setMode(SubscriberMode.LIVE);

        // First report establishes the attribute (prior null => emit).
        p.onEvent(reportedAt(subject, "temperature_c", "20.0", EVENT_TIME));
        assertThat(store.get(entityId).orElseThrow().attributes().get("temperature_c"))
                .isEqualTo(new StringValue("20.0"));

        // Within-epsilon report: typed-unchanged => the rule emits nothing, so the publish
        // guard is never reached and the attribute is unchanged.
        p.onEvent(reportedAt(subject, "temperature_c", "20.0000000001", EVENT_TIME.plusSeconds(1)));
        assertThat(store.get(entityId).orElseThrow().attributes().get("temperature_c"))
                .as("typed-unchanged emits nothing; shouldPublishDerived never reached")
                .isEqualTo(new StringValue("20.0"));

        // Genuine change: typed-changed => published and applied; NOT suppressed by the string
        // guard (newValue "21.5" != current "20.0").
        p.onEvent(reportedAt(subject, "temperature_c", "21.5", EVENT_TIME.plusSeconds(2)));
        assertThat(store.get(entityId).orElseThrow().attributes().get("temperature_c"))
                .as("typed-changed is not suppressed by shouldPublishDerived")
                .isEqualTo(new StringValue("21.5"));
    }

    @Test
    @Disabled("AMD-51 §5 #9 — catalogue-expansion backfill. Cannot be exercised at M4.0b-3: "
            + "no new unit is added to the QuantityValue catalogue this WU, so there is no "
            + "previously-unrecognised-now-recognised unit to replay. Enable (and complete) "
            + "when a future WU expands QuantityValue.CATALOGUE — a historical state_reported "
            + "whose unit was unrecognised (degraded) but is now recognised must reconstruct "
            + "correctly during the next version-transition backfill (AMD-50 generality clause, "
            + "value-layer case).")
    @DisplayName("AMD-51 §5.9: catalogue-expansion backfill (later-WU — disabled stub)")
    void catalogueExpansionBackfillReconstructsNewlyRecognisedUnit() {
        // TODO(AMD-51 §5 #9): when QuantityValue.CATALOGUE gains a unit, publish a historical
        // state_reported with that unit (which would previously have degraded), run a
        // version-transition backfill, and assert the attribute reconstructs to the canonical
        // QuantityValue rather than staying degraded.
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns the production typed change-detection rule (AMD-51) wired with the structural
     * comparator, the default float/quantity epsilon, and a resolver over the standard
     * capability schemas (so {@code temperature_c} resolves to FLOAT).
     */
    private static DerivationRule typedRule() {
        return DerivationRule.production(
                AttributeValueComparator.structural(),
                ComparisonPolicy.FP_NOISE_DEFAULT,
                AttributeSchemaResolver.of(StandardCapabilities.attributeSchemas()));
    }

    private StateProjection createProjection(int projectionVersion,
                                             StateCheckpointSource source) {
        return new StateProjection(
                new ProjectionId(VIEW_NAME),
                projectionVersion,
                checkpointStore,
                source,
                AtomicCheckpointSink.viewOnly(checkpointStore),
                stateStore,
                noopRule,
                eventStore,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate,
                new SelfProducedFilter(clock, Duration.ofSeconds(60)));
    }

    private static SubjectRef freshSubject() {
        return SubjectRef.entity(new EntityId(UlidFactory.generate()));
    }

    private EventEnvelope publishStateReported(SubjectRef subject, long expectedPosition)
            throws SequenceConflictException {
        EventDraft draft = new EventDraft(
                EventTypes.STATE_REPORTED,
                1,
                clock.instant(),
                subject,
                EventPriority.NORMAL,
                EventOrigin.PHYSICAL,
                new StateReportedEvent("k", "v", null, null, null),
                null,
                null);
        EventEnvelope env = eventStore.publishRoot(draft);
        // Verify the test's positional assumption — caller passes the
        // expected globalPosition for clarity in failure messages.
        assertThat(env.globalPosition())
                .as("positional check for test clarity")
                .isEqualTo(expectedPosition);
        return env;
    }

    private EntityState freshEntityState(EntityId id, long version) {
        var now = clock.instant();
        Map<String, AttributeValue> attrs = Map.of("k", new StringValue("v"));
        return new EntityState(
                id, attrs, Availability.AVAILABLE,
                version, now, now, now, null, false);
    }

    private static StateCheckpointSource fixedSource(byte[] payload, int loadedVersion) {
        return new StateCheckpointSource() {
            @Override
            public byte[] serializeCheckpoint(int projectionVersion) {
                return payload;
            }

            @Override
            public int loadedProjectionVersion() {
                return loadedVersion;
            }
        };
    }

    /**
     * Publishes a {@code state_reported} with an explicit {@code eventTime}
     * (distinct from the projection clock) so the backfill's event-time-sourced
     * {@code lastChanged} is provable. Returns the persisted envelope.
     */
    private EventEnvelope reportedAt(SubjectRef subject, String key, String value,
                                     Instant eventTime) throws SequenceConflictException {
        EventDraft draft = new EventDraft(
                EventTypes.STATE_REPORTED, 1, eventTime, subject,
                EventPriority.NORMAL, EventOrigin.PHYSICAL,
                new StateReportedEvent(key, value, null, null, null),
                null, null);
        return eventStore.publishRoot(draft);
    }

    /**
     * Publishes a prior-version {@code state_changed} (stands in for an event the
     * pre-transition rule logged) with an explicit {@code eventTime}. Used by the
     * supersession and steady-state tests.
     */
    private EventEnvelope changedAt(SubjectRef subject, String key, String oldValue,
                                    String newValue, Instant eventTime)
            throws SequenceConflictException {
        EventDraft draft = new EventDraft(
                EventTypes.STATE_CHANGED, 1, eventTime, subject,
                EventPriority.NORMAL, EventOrigin.SYSTEM,
                new StateChangedEvent(key, oldValue, newValue, EventId.of(UlidFactory.generate())),
                null, null);
        return eventStore.publishRoot(draft);
    }

    /**
     * Builds a projection over a seeded checkpoint with a controllable
     * {@code loadedProjectionVersion} and runtime version, plus the given rule and
     * state store. When {@code loadedVersion != runtimeVersion} (escape hatch off),
     * the first {@code onEvent}/{@code processBatch} reconciles and opens the
     * backfill gate; when they match, the gate stays inactive.
     */
    private StateProjection projectionFor(String viewName, int loadedVersion,
                                          int runtimeVersion, DerivationRule rule,
                                          StateStore store, long seededPosition) {
        checkpointStore.writeCheckpoint(viewName, seededPosition, SEEDED_PAYLOAD);
        return new StateProjection(
                new ProjectionId(viewName),
                runtimeVersion,
                checkpointStore,
                fixedSource(SEEDED_PAYLOAD, loadedVersion),
                AtomicCheckpointSink.viewOnly(checkpointStore),
                store,
                rule,
                eventStore,
                advancer,
                FixedCheckpointPolicy.HOME_DEFAULT,
                clock,
                publishGate,
                new SelfProducedFilter(clock, Duration.ofSeconds(60)));
    }

    /**
     * Test {@link DerivationRule} that suppresses a within-epsilon numeric change —
     * a stand-in for the typed comparator (REC-90 / AMD-51), which is out of scope
     * for M4.0b-2. It derives a {@code state_changed} for a genuinely-different
     * report but NOTHING for a within-tolerance one, so a logged prior-version
     * {@code state_changed} for that within-tolerance delta has no current-rule
     * counterpart and is suppressed under the §2.2 supersession gate.
     */
    private static final class ToleranceRule implements DerivationRule {

        private final double epsilon;

        ToleranceRule(double epsilon) {
            this.epsilon = epsilon;
        }

        @Override
        public List<EventDraft> evaluate(DerivationContext context) {
            EventEnvelope env = context.envelope();
            if (!(env.payload() instanceof StateReportedEvent sr)) {
                return List.of();
            }
            String key = sr.attributeKey();
            String newValue = sr.value();
            String oldValue = priorValue(context.priorState(), key);
            if (oldValue != null && withinTolerance(oldValue, newValue)) {
                return List.of();
            }
            if (Objects.equals(oldValue, newValue)) {
                return List.of();
            }
            String oldNonNull = (oldValue == null) ? "" : oldValue;
            StateChangedEvent payload =
                    new StateChangedEvent(key, oldNonNull, newValue, env.eventId());
            EventDraft draft = new EventDraft(
                    EventTypes.STATE_CHANGED, 1, env.eventTime(), env.subjectRef(),
                    EventPriority.NORMAL, EventOrigin.SYSTEM, payload, env.actorRef(), null);
            return List.of(draft);
        }

        private boolean withinTolerance(String a, String b) {
            try {
                return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < epsilon;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        private static String priorValue(EntityState prior, String key) {
            if (prior == null) {
                return null;
            }
            AttributeValue v = prior.attributes().get(key);
            if (v == null) {
                return null;
            }
            return (v instanceof StringValue sv) ? sv.value() : v.rawValue().toString();
        }
    }

    /**
     * {@link StateCheckpointSource} double that captures the reconciliation
     * metadata threaded through the M4.0a
     * {@link StateCheckpointSource#serializeCheckpoint(int, Instant, Integer, Integer)}
     * overload. Used by {@code reconciliationRecordsMetadataInDataSlot} to prove
     * the projection populates the data slot after a version transition.
     */
    private static final class RecordingCheckpointSource implements StateCheckpointSource {

        private final byte[] payload;
        private final int loadedVersion;
        private Instant lastReconciledAt;
        private Integer lastReconciledFromVersion;
        private Integer lastReconciledToVersion;
        private boolean captured;

        RecordingCheckpointSource(byte[] payload, int loadedVersion) {
            this.payload = payload;
            this.loadedVersion = loadedVersion;
        }

        @Override
        public byte[] serializeCheckpoint(int projectionVersion) {
            // Only the metadata overload is exercised by the projection; this
            // exists to satisfy the abstract method contract.
            return payload;
        }

        @Override
        public byte[] serializeCheckpoint(int projectionVersion, Instant reconciledAt,
                                          Integer reconciledFromVersion,
                                          Integer reconciledToVersion) {
            this.lastReconciledAt = reconciledAt;
            this.lastReconciledFromVersion = reconciledFromVersion;
            this.lastReconciledToVersion = reconciledToVersion;
            this.captured = true;
            return payload;
        }

        @Override
        public int loadedProjectionVersion() {
            return loadedVersion;
        }

        boolean captured() {
            return captured;
        }

        Instant lastReconciledAt() {
            return lastReconciledAt;
        }

        Integer lastReconciledFromVersion() {
            return lastReconciledFromVersion;
        }

        Integer lastReconciledToVersion() {
            return lastReconciledToVersion;
        }
    }

}
