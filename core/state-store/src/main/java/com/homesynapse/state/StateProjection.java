/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.StringValue;
import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State Projection subscriber — materializes entity state from the event log
 * (AMD-41, Doc 03 §3.2, §4.1).
 *
 * <p>This class is the first end-to-end implementation of the State Store's
 * projection pipeline. It implements {@link Subscriber} (event-bus runtime
 * callback) and processes {@code state_reported}, {@code state_changed}, and
 * {@code availability_changed} events into materialized {@link EntityState}
 * stored in a {@link StateStore}. Derived {@code state_changed} events produced
 * from {@code state_reported} are published via {@link EventPublisher} during
 * LIVE mode.</p>
 *
 * <h2>Two-phase discipline (AMD-41 §3.2.1, DEC-M3-01)</h2>
 *
 * <p>Each {@code onEvent(envelope)} executes a fixed three-phase sequence:</p>
 * <ol>
 *   <li><b>READ</b>: Load prior {@link EntityState}, ask the
 *       {@link DerivationRule} for zero or more derived drafts. No publishes
 *       happen yet.</li>
 *   <li><b>PUBLISH</b> (LIVE only): For each derived draft, acquire a permit
 *       from the {@link DerivedPublishGate} and call
 *       {@code publisher.publish(draft, chain)}. Record each published event ID
 *       in the {@link SelfProducedFilter} so re-delivery is suppressed.</li>
 *   <li><b>STATE UPDATE</b>: Apply the inbound envelope and any
 *       published-derived envelopes to the {@link StateStore}, advancing
 *       {@code stateVersion} on every event.</li>
 *   <li><b>CHECKPOINT</b>: Advance the internal cursor and consult the
 *       {@link CheckpointPolicy}; flush a checkpoint when the policy says so.</li>
 * </ol>
 *
 * <h2>Self-produced filter (AMD-41 §3.2.2)</h2>
 *
 * <p>The {@link SelfProducedFilter} suppresses re-derivation when the bus
 * re-delivers a {@code state_changed} that the projection itself just published.
 * Bypassed in {@code REPLAY}/{@code TRANSITION} so that determinism is preserved
 * during catch-up.</p>
 *
 * <h2>Mode awareness (AMD-42)</h2>
 *
 * <p>The projection holds its own {@link SubscriberMode} via {@link #setMode}.
 * The bus's lifecycle wiring is expected to call {@code setMode} on
 * {@code COLD → REPLAY → TRANSITION → LIVE → SUSPENDED} transitions. In
 * {@code SUSPENDED} or {@code COLD}, {@code onEvent} returns immediately. In
 * {@code REPLAY} or {@code TRANSITION}, publishes are suppressed but state is
 * still applied. In {@code LIVE}, the full pipeline runs.</p>
 *
 * <h2>Lazy initialization</h2>
 *
 * <p>Construction stores parameters only — no I/O, no checkpoint reads. The first
 * {@code onEvent} call (in REPLAY or LIVE) triggers
 * {@link #initialize()}, which loads the persisted checkpoint, runs the
 * reconciliation pass on version mismatch (AMD-41 §3.2.4), and sets up the
 * checkpoint cadence trackers. This keeps construction cheap, simplifies tests,
 * and avoids side effects in projections constructed but never subscribed.</p>
 *
 * <h2>Reconciliation (AMD-41 §3.2.4)</h2>
 *
 * <p>When the {@link StateCheckpointSource#loadedProjectionVersion() persisted
 * projection version} (recovered from the checkpoint data blob) does not match
 * the running code's {@code projectionVersion}:</p>
 * <ul>
 *   <li>If the system property
 *       {@value #ALLOW_STALE_SNAPSHOTS_PROPERTY} is {@code true}, log a WARN and
 *       proceed with the stale checkpoint (escape hatch).</li>
 *   <li>Otherwise, discard the checkpoint, clear the {@link StateStore}, and set
 *       the cursor to position 0. The bus's REPLAY mechanism re-derives state
 *       from the start of the log.</li>
 * </ul>
 *
 * <h2>Reconciliation backfill (AMD-50)</h2>
 *
 * <p>A plain replay-from-zero would rebuild {@code stateVersion}/timestamps but
 * leave {@code attributes} empty, because {@code applyToState} writes attributes
 * only on inbound {@code state_changed} and the pre-transition log holds none.
 * AMD-50 closes this: a version-transition reconciliation opens a provenance gate
 * ({@code backfillActive}, set in {@link #initialize} and cleared in
 * {@link #onCaughtUp}). While the gate is open the projection performs a one-shot,
 * non-emitting backfill — re-derived {@code state_changed} drafts are applied to
 * in-memory state ({@link #applyBackfillAttribute}) so the historical attribute
 * map reconstructs from the {@code state_reported} history, with no second
 * {@code stateVersion} increment (AMD-50-INV-01) and no publish
 * (INV-WRITER-01). The gate is honoured on BOTH derivation paths
 * ({@link #onEvent} and {@link #processBatch}). For generality (any N&rarr;M
 * transition, §2.5) a logged prior-version {@code state_changed} replaying under
 * the gate advances the cursor but is suppressed for attributes (supersession,
 * §2.2 — see {@code applyToState}), so the current rule's re-derivation always
 * wins. Outside the gate (matching version, escape hatch, or LIVE) the backfill
 * and supersession are dormant and logged {@code state_changed} is the sole
 * authority for attributes (AMD-50-INV-02).</p>
 *
 * <h2>Defence-in-depth on {@code stateVersion} (DEC-M3-02)</h2>
 *
 * <p>If the {@link SelfProducedFilter} misses (e.g., after a process restart
 * that loses the in-memory filter set), a self-produced {@code state_changed}
 * may arrive in LIVE. {@link #shouldPublishDerived} blocks publishes whose
 * derived {@code newValue} already matches the currently-materialized attribute
 * value, providing the second defence layer.</p>
 *
 * <h2>Thread confinement</h2>
 *
 * <p>{@code onEvent} executes on the subscriber's single virtual thread (per the
 * bus's per-subscriber VT model, INV-SUB-ISO-01). All in-projection state
 * ({@link #initialized}, {@link #cursorPosition}, {@link #eventsSinceCheckpoint},
 * {@link #lastCheckpointAt}) is single-threaded. {@link #currentMode} uses
 * {@link AtomicReference} so external lifecycle code (the bus) can read or update
 * the mode without violating the subscriber's exclusive ownership of the other
 * fields.</p>
 *
 * @see Subscriber
 * @see DerivationRule
 * @see SelfProducedFilter
 * @see DerivedPublishGate
 * @see StateCheckpointSource
 * @see CheckpointPolicy
 */
public final class StateProjection implements Subscriber {

    /**
     * System property controlling the reconciliation escape hatch. When set to
     * {@code "true"} (case-insensitive), the projection proceeds with a
     * version-mismatched checkpoint instead of clearing state and restarting
     * from position 0. Default is {@code false}.
     */
    public static final String ALLOW_STALE_SNAPSHOTS_PROPERTY =
            "homesynapse.projection.allow_stale_snapshots";

    /**
     * Advisory size threshold (10 MB) for serialized checkpoint payloads. The
     * projection emits a WARN once per checkpoint write when
     * {@link StateCheckpointSource#serializeCheckpoint(int)} returns a payload
     * larger than this. The write is not blocked — the guardrail is a hint to
     * operators that entity count or attribute density is approaching the
     * point where checkpoint cadence starts costing significant I/O.
     */
    static final int CHECKPOINT_SIZE_WARN_BYTES = 10 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(StateProjection.class);

    private final ProjectionId projectionId;
    private final int projectionVersion;
    private final ViewCheckpointStore checkpointStore;
    private final StateCheckpointSource checkpointSource;
    private final AtomicCheckpointSink checkpointSink;
    private final StateStore stateStore;
    private final DerivationRule rule;
    private final EventPublisher publisher;
    private final ProjectionAdvancer advancer;
    private final CheckpointPolicy checkpointPolicy;
    private final Clock clock;
    private final DerivedPublishGate publishGate;
    private final SelfProducedFilter selfFilter;

    // External lifecycle updates this; the subscriber VT reads it.
    private final AtomicReference<SubscriberMode> currentMode =
            new AtomicReference<>(SubscriberMode.COLD);
    private final AtomicBoolean caughtUpFired = new AtomicBoolean(false);

    // Single-threaded state — only the subscriber VT mutates these.
    private boolean initialized;
    private long cursorPosition;
    private long eventsSinceCheckpoint;
    private Instant lastCheckpointAt;

    // Reconciliation metadata (AMD-41 §3.2.4, OR-M3-13). Set during
    // initialize() when a version-mismatch reconciliation fires; threaded into
    // every subsequent checkpoint write so the transition is recorded in the
    // checkpoint data slot. reconciledToVersion is a downstream dependency:
    // M4.0b's backfill gate binds to it. Null until a reconciliation occurs.
    private Instant reconciledAt;
    private Integer reconciledFromVersion;
    private Integer reconciledToVersion;

    // AMD-50 §2.2 reconciliation provenance gate. Set true ONLY in initialize()'s
    // version-transition reconciliation branch (genuine N->M mismatch, escape
    // hatch off); cleared at onCaughtUp() (the REPLAY -> LIVE signal). While true,
    // the projection is performing a one-shot, replay-from-zero rebuild: re-derived
    // state_changed drafts are applied to in-memory state (backfill, §2.1/§2.3) and
    // logged prior-version state_changed events are suppressed for attributes
    // (supersession, §2.2). False on every non-reconciliation rebuild (matching
    // version, escape hatch, steady-state catch-up) and in LIVE — there the gate is
    // dormant and logged state_changed is the sole authority for attributes
    // (AMD-50-INV-02). Single-threaded like the other init/cursor fields.
    private boolean backfillActive;

    // REC-80 replay-duration metric. replayStartedAt is stamped at the first
    // initialize(); eventsReplayed counts processed events; both are read at
    // onCaughtUp() (the REPLAY -> LIVE signal, AMD-42 §3.4.3) to emit the
    // projection.replay.duration_ms / events_replayed measurement that later
    // feeds the AMD-41 §3.2.3 5 s full-replay decision.
    private Instant replayStartedAt;
    private long eventsReplayed;

    /**
     * Public factory for production wiring. Creates a {@link SelfProducedFilter}
     * with the default 60-second TTL internally.
     *
     * <p>Use this factory from the composition root or lifecycle module. The
     * package-private constructor (taking an explicit {@link SelfProducedFilter})
     * remains available for in-package tests that need a custom filter.</p>
     *
     * @param projectionId      stable identifier for this projection; never {@code null}
     * @param projectionVersion running code's projection version; must be ≥ 1
     * @param checkpointStore   durable checkpoint storage; never {@code null}
     * @param checkpointSource  source of serialized checkpoint data and the
     *                          authoritative loaded projection version (AMD-41
     *                          §3.2.3–3.2.4); never {@code null}. Pass
     *                          {@link StateCheckpointSource#stub()} when
     *                          checkpoint persistence is not needed (tests,
     *                          in-memory deployments).
     * @param checkpointSink    atomic subscriber+view checkpoint sink (AMD-45);
     *                          checkpoint writes route through this so the bus
     *                          subscriber position and the view snapshot advance
     *                          in a single transaction. Never {@code null}. Pass
     *                          {@link AtomicCheckpointSink#viewOnly(ViewCheckpointStore)}
     *                          for in-memory deployments with no coupled
     *                          subscriber checkpoint.
     * @param stateStore        port for materialized state; never {@code null}
     * @param rule              derivation strategy; never {@code null}
     * @param publisher         event publisher for derived events; never {@code null}
     * @param advancer          projection advancer (used by batch catch-up paths);
     *                          never {@code null}
     * @param checkpointPolicy  cadence policy; never {@code null}
     * @param clock             injected clock; never {@code null}
     * @param publishGate       rate-limiting gate around derived publishes; never
     *                          {@code null}
     * @return a new {@code StateProjection}
     */
    public static StateProjection create(
            ProjectionId projectionId,
            int projectionVersion,
            ViewCheckpointStore checkpointStore,
            StateCheckpointSource checkpointSource,
            AtomicCheckpointSink checkpointSink,
            StateStore stateStore,
            DerivationRule rule,
            EventPublisher publisher,
            ProjectionAdvancer advancer,
            CheckpointPolicy checkpointPolicy,
            Clock clock,
            DerivedPublishGate publishGate) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new StateProjection(
                projectionId, projectionVersion, checkpointStore, checkpointSource,
                checkpointSink, stateStore, rule, publisher, advancer,
                checkpointPolicy, clock, publishGate,
                new SelfProducedFilter(clock, SelfProducedFilter.DEFAULT_TTL));
    }

    /**
     * Package-private constructor. In-package tests use this to inject a custom
     * {@link SelfProducedFilter} (e.g., short TTL for filter-eviction tests).
     */
    StateProjection(
            ProjectionId projectionId,
            int projectionVersion,
            ViewCheckpointStore checkpointStore,
            StateCheckpointSource checkpointSource,
            AtomicCheckpointSink checkpointSink,
            StateStore stateStore,
            DerivationRule rule,
            EventPublisher publisher,
            ProjectionAdvancer advancer,
            CheckpointPolicy checkpointPolicy,
            Clock clock,
            DerivedPublishGate publishGate,
            SelfProducedFilter selfFilter) {
        this.projectionId = Objects.requireNonNull(projectionId, "projectionId");
        if (projectionVersion < 1) {
            throw new IllegalArgumentException(
                    "projectionVersion must be >= 1, got " + projectionVersion);
        }
        this.projectionVersion = projectionVersion;
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.checkpointSource = Objects.requireNonNull(checkpointSource, "checkpointSource");
        this.checkpointSink = Objects.requireNonNull(checkpointSink, "checkpointSink");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.advancer = Objects.requireNonNull(advancer, "advancer");
        this.checkpointPolicy = Objects.requireNonNull(checkpointPolicy, "checkpointPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.publishGate = Objects.requireNonNull(publishGate, "publishGate");
        this.selfFilter = Objects.requireNonNull(selfFilter, "selfFilter");
    }

    /**
     * Sets the subscriber's lifecycle mode. The bus invokes this immediately
     * after each successful CAS on the runtime's authoritative mode reference
     * (M3.7 fix round 4) — at {@code COLD → REPLAY}, {@code REPLAY → TRANSITION},
     * {@code TRANSITION → LIVE}, and any transition to {@code SUSPENDED}. The
     * resume path is intentionally NOT wired (pending bus VT-respawn fix).
     *
     * @param mode the new mode; never {@code null}
     */
    @Override
    public void setMode(SubscriberMode mode) {
        Objects.requireNonNull(mode, "mode must not be null");
        currentMode.set(mode);
    }

    /**
     * Returns the projection's current lifecycle mode.
     *
     * @return current mode; never {@code null}
     */
    public SubscriberMode currentMode() {
        return currentMode.get();
    }

    /**
     * Returns the projection's stable identifier.
     *
     * @return projection identifier; never {@code null}
     */
    public ProjectionId projectionId() {
        return projectionId;
    }

    /**
     * Returns the projection's running code version.
     *
     * @return positive integer version
     */
    public int projectionVersion() {
        return projectionVersion;
    }

    /**
     * Returns the highest global position processed so far. Equal to the
     * persisted checkpoint position after a successful checkpoint flush.
     *
     * @return cursor position (≥ 0)
     */
    public long cursorPosition() {
        return cursorPosition;
    }

    /**
     * Returns whether {@link #initialize} has run. Test-only accessor.
     *
     * @return {@code true} once the lazy init has completed
     */
    boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the projection's internal {@link SelfProducedFilter}. Test-only
     * accessor for in-package tests that need to inspect or evict entries.
     *
     * @return the filter instance
     */
    SelfProducedFilter selfFilter() {
        return selfFilter;
    }

    /**
     * Processes one inbound event from the bus's subscriber dispatch.
     *
     * <p>Runs the four-phase pipeline (READ → PUBLISH → STATE UPDATE →
     * CHECKPOINT). In {@code COLD}/{@code SUSPENDED} modes, returns immediately
     * without side effects. In {@code REPLAY}/{@code TRANSITION}, derivation
     * runs and state is applied but publishes are suppressed.</p>
     *
     * @param inbound the inbound event envelope; never {@code null}
     */
    @Override
    public void onEvent(EventEnvelope inbound) {
        Objects.requireNonNull(inbound, "inbound must not be null");

        if (!initialized) {
            initialize();
        }

        SubscriberMode mode = currentMode.get();
        if (mode == SubscriberMode.SUSPENDED || mode == SubscriberMode.COLD) {
            return;
        }

        // Step 3: self-produced filter
        if (selfFilter.isSelfProduced(inbound.eventId().value(), mode)) {
            // Count toward checkpoint cadence only; no state advance, no derivation.
            advanceCheckpointCadence(inbound.globalPosition());
            return;
        }

        EntityId subjectEntity = subjectEntityIdOrNull(inbound.subjectRef());
        EntityState priorState = (subjectEntity != null)
                ? stateStore.get(subjectEntity).orElse(null)
                : null;

        // Step 4: READ phase — evaluate derivation rule
        List<EventDraft> drafts = List.of();
        if (subjectEntity != null) {
            DerivationContext ctx = new DerivationContext(priorState, inbound);
            drafts = rule.evaluate(ctx);
            if (drafts == null) {
                drafts = List.of();
            }
        }

        // Step 6 (part 1): apply inbound to state BEFORE publishing derived. This
        // preserves the determinism guarantee with REPLAY where state_reported
        // arrives first (advancing stateVersion), then state_changed advances
        // again.
        if (subjectEntity != null) {
            applyToState(inbound, subjectEntity);
        }

        // Step 5: PUBLISH phase (LIVE only)
        if (mode == SubscriberMode.LIVE && !drafts.isEmpty() && subjectEntity != null) {
            for (EventDraft draft : drafts) {
                if (!shouldPublishDerived(draft, subjectEntity)) {
                    continue;
                }
                try {
                    publishGate.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    // Bail out — subscriber teardown in progress.
                    return;
                }
                CausalContext cause = CausalContext.chain(
                        inbound.causalContext().correlationId(),
                        inbound.eventId().value());
                try {
                    EventEnvelope published = publisher.publish(draft, cause);
                    selfFilter.record(published.eventId().value());
                    // Apply the published derived event to state immediately. When
                    // the bus re-delivers it, the self-filter suppresses re-apply.
                    applyToState(published, subjectEntity);
                } catch (SequenceConflictException sce) {
                    log.warn("Sequence conflict publishing derived event for {}: seq={}",
                            sce.subjectRef(), sce.conflictingSequence());
                }
            }
        } else if (backfillActive && !drafts.isEmpty() && subjectEntity != null) {
            // AMD-50 §2.1/§2.3 reconciliation backfill (the active production REPLAY
            // path: ReplayDriver -> supervisor.deliver -> onEvent). The re-derived
            // state_changed drafts reconstruct the historical attribute map directly
            // in in-memory state — NEVER published (INV-WRITER-01) and WITHOUT a
            // second stateVersion increment (INV-01). The triggering state_reported,
            // applied above, owns the single cursor +1 and lastReported. A rule that
            // produced no draft (unchanged value) writes nothing. This branch is the
            // REPLAY twin of the LIVE publish-and-apply above; both must gate the
            // derived-apply behaviour (the M4.0a D-1 lesson — guard every path).
            for (EventDraft draft : drafts) {
                applyBackfillDraft(subjectEntity, inbound, draft);
            }
        }

        // Step 7: checkpoint cadence
        advanceCheckpointCadence(inbound.globalPosition());
    }

    /**
     * Fires exactly once when the bus completes the REPLAY → LIVE transition
     * (AMD-42 §3.4.3).
     */
    @Override
    public void onCaughtUp() {
        if (caughtUpFired.compareAndSet(false, true)) {
            // AMD-50 §2.2: exiting REPLAY closes the provenance gate. From LIVE
            // onward the backfill apply (§2.1) and supersession suppression (§2.2)
            // are dormant; logged state_changed is the sole authority for
            // attributes until the next version transition (AMD-50-INV-02).
            backfillActive = false;
            // REC-80: emit the replay-duration metric at the REPLAY -> LIVE
            // signal. State-store has no metrics facade (the bus uses JFR via a
            // dependency state-store does not carry), so the canonical metric
            // names are emitted as structured SLF4J fields per LTD-15 — this is
            // the measurement hook that later feeds the AMD-41 §3.2.3 5 s
            // full-replay-on-Pi decision (no SqliteSnapshotStore work here).
            long durationMs = (replayStartedAt != null)
                    ? Math.max(0L, Duration.between(replayStartedAt, clock.instant()).toMillis())
                    : 0L;
            log.info("StateProjection {} caught up at position {}; "
                            + "projection.replay.duration_ms={} events_replayed={}",
                    projectionId.value(), cursorPosition, durationMs, eventsReplayed);
        }
    }

    /**
     * Returns whether {@link #onCaughtUp} has fired. Test-only accessor.
     *
     * @return {@code true} if caught-up has fired once
     */
    boolean caughtUpFired() {
        return caughtUpFired.get();
    }

    /**
     * Drives a bounded batch of events through the advancer's read transaction
     * (AMD-41 §3.2.1 two-phase discipline).
     *
     * <p>The advancer opens a read transaction, invokes a processor callback for
     * each event (the projection's READ phase: derive + apply state), and closes
     * the transaction before returning. Derived publishes are BUFFERED inside the
     * callback and emitted by this method AFTER the advancer returns — proving
     * that the read tx is closed before any publish executes.</p>
     *
     * <p>This method is used by the contract test
     * {@code readTxClosesBeforePublish} and (in future bus wiring) by the
     * subscriber's REPLAY/catch-up loop. LIVE event delivery goes through
     * {@link #onEvent} directly without the advancer.</p>
     *
     * @param maxRows maximum events to process in this batch (capped at
     *                {@link ProjectionAdvancer#DEFAULT_MAX_ROWS})
     * @return the {@link AdvanceResult} from the underlying advancer
     */
    public AdvanceResult processBatch(int maxRows) {
        if (!initialized) {
            initialize();
        }
        SubscriberMode mode = currentMode.get();
        if (mode == SubscriberMode.SUSPENDED || mode == SubscriberMode.COLD) {
            return new AdvanceResult(cursorPosition, 0, false);
        }

        List<BufferedDerivation> buffered = new ArrayList<>();

        Consumer<EventEnvelope> processor = env -> {
            // READ phase — runs inside advancer's read tx.
            if (selfFilter.isSelfProduced(env.eventId().value(), mode)) {
                return;
            }
            EntityId entityId = subjectEntityIdOrNull(env.subjectRef());
            EntityState prior = (entityId != null)
                    ? stateStore.get(entityId).orElse(null)
                    : null;
            List<EventDraft> derived = List.of();
            if (entityId != null) {
                DerivationContext ctx = new DerivationContext(prior, env);
                derived = rule.evaluate(ctx);
                if (derived == null) {
                    derived = List.of();
                }
            }
            // Apply inbound to state (Map-backed StateStore is safe to mutate
            // here — it's not the event-store tx).
            if (entityId != null) {
                applyToState(env, entityId);
                for (EventDraft d : derived) {
                    if (backfillActive) {
                        // AMD-50 §2.1 reconciliation backfill on the batch path
                        // (the D-1 lesson: gate BOTH derivation paths, not just
                        // onEvent). Apply the re-derived draft to in-memory state
                        // immediately — inside the read-tx callback, exactly where
                        // the inbound apply already happens — so the NEXT event's
                        // derivation in this batch sees the updated attribute,
                        // matching the native LIVE fold. Non-emitting; no second
                        // stateVersion increment (INV-01/INV-WRITER-01). Deferring
                        // the apply to a post-advance phase (like the LIVE publish
                        // below) would feed the rule a stale prior across a batch
                        // boundary and diverge from native — see coder-handoff D-A.
                        applyBackfillDraft(entityId, env, d);
                    } else {
                        buffered.add(new BufferedDerivation(env, entityId, d));
                    }
                }
            }
        };

        AdvanceResult result = advancer.advance(cursorPosition, maxRows, processor);

        // PUBLISH phase — AFTER advancer.advance() returns (tx closed).
        if (mode == SubscriberMode.LIVE) {
            for (BufferedDerivation bd : buffered) {
                if (!shouldPublishDerived(bd.draft(), bd.entityId())) {
                    continue;
                }
                try {
                    publishGate.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return result;
                }
                CausalContext cc = CausalContext.chain(
                        bd.causingEnvelope().causalContext().correlationId(),
                        bd.causingEnvelope().eventId().value());
                try {
                    EventEnvelope published = publisher.publish(bd.draft(), cc);
                    selfFilter.record(published.eventId().value());
                    applyToState(published, bd.entityId());
                } catch (SequenceConflictException sce) {
                    log.warn("Sequence conflict in batch publish for {}: seq={}",
                            sce.subjectRef(), sce.conflictingSequence());
                }
            }
        }

        // Advance cursor and consult checkpoint policy.
        if (result.eventsProcessed() > 0) {
            advanceCheckpointCadence(result.lastProcessedPosition());
        }

        return result;
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal lifecycle
    // ──────────────────────────────────────────────────────────────────

    private void initialize() {
        Optional<CheckpointRecord> latest =
                checkpointStore.readLatestCheckpoint(projectionId.value());
        if (latest.isPresent()) {
            CheckpointRecord record = latest.get();
            // AMD-41 §3.2.4: the authoritative version lives in the checkpoint
            // data blob (recovered via StateCheckpointSource), NOT in
            // CheckpointRecord.projectionVersion() — that field is a sentinel
            // hardcoded to 1 by both in-memory and SQLite ViewCheckpointStore
            // implementations.
            int persistedVersion = checkpointSource.loadedProjectionVersion();
            if (persistedVersion != projectionVersion) {
                boolean allowStale = Boolean.parseBoolean(
                        System.getProperty(ALLOW_STALE_SNAPSHOTS_PROPERTY, "false"));
                if (allowStale) {
                    log.warn("Stale snapshot accepted for {}: persisted version {} != "
                                    + "projection version {} (escape hatch active)",
                            projectionId.value(),
                            persistedVersion,
                            projectionVersion);
                    cursorPosition = record.position();
                } else {
                    log.info("Reconciliation triggered for {}: persisted version {} != "
                                    + "projection version {}; clearing state, resetting cursor to 0",
                            projectionId.value(),
                            persistedVersion,
                            projectionVersion);
                    stateStore.clear();
                    cursorPosition = 0L;
                    // OR-M3-13 / AMD-41 §3.2.4: record the version transition so
                    // the next checkpoint write persists it in the data slot.
                    // reconciledToVersion is what M4.0b's backfill gate binds to.
                    reconciledFromVersion = persistedVersion;
                    reconciledToVersion = projectionVersion;
                    reconciledAt = clock.instant();
                    // AMD-50 §2.2: this is the one genuine version-transition
                    // reconciliation (mismatch, escape hatch off) — open the
                    // provenance gate so the replay-from-zero rebuild backfills
                    // historical attributes (§2.1) and supersedes stale logged
                    // state_changed (§2.2). Closed at onCaughtUp().
                    backfillActive = true;
                }
            } else {
                cursorPosition = record.position();
            }
        } else {
            cursorPosition = 0L;
        }
        lastCheckpointAt = clock.instant();
        eventsSinceCheckpoint = 0L;
        // REC-80: stamp the replay-window start. eventsReplayed accumulates as
        // events are processed; both are read at onCaughtUp().
        replayStartedAt = clock.instant();
        initialized = true;
    }

    private void advanceCheckpointCadence(long globalPosition) {
        cursorPosition = Math.max(cursorPosition, globalPosition);
        eventsSinceCheckpoint++;
        eventsReplayed++; // REC-80: total events processed (snapshotted at onCaughtUp)
        Instant now = clock.instant();
        Duration since = (lastCheckpointAt != null)
                ? Duration.between(lastCheckpointAt, now)
                : Duration.ZERO;
        if (checkpointPolicy.shouldCheckpoint(eventsSinceCheckpoint, since, 0L)) {
            writeCheckpoint(now);
        }
    }

    private void writeCheckpoint(Instant now) {
        // AMD-41 §3.2.4 / OR-M3-13: thread the reconciliation metadata into the
        // serialized payload. The fields are null until a version-mismatch
        // reconciliation fires (see initialize()); once set, every subsequent
        // checkpoint records the transition.
        byte[] data = checkpointSource.serializeCheckpoint(
                projectionVersion, reconciledAt, reconciledFromVersion, reconciledToVersion);
        if (data.length > CHECKPOINT_SIZE_WARN_BYTES) {
            log.warn("Checkpoint data for {} is {} bytes — consider reducing "
                            + "entity count or attribute density",
                    projectionId.value(), data.length);
        }
        // AMD-45 §2.1: write the subscriber checkpoint and the view checkpoint
        // atomically (one SQLite transaction). The bus's per-delivery subscriber
        // checkpoint write is suppressed for this subscriber (atomicCheckpoint),
        // so the projection is the sole writer of the coupled position.
        checkpointSink.writeAtomicCheckpoint(projectionId.value(), cursorPosition, data);
        eventsSinceCheckpoint = 0L;
        lastCheckpointAt = now;
    }

    // ──────────────────────────────────────────────────────────────────
    // Derivation and state application
    // ──────────────────────────────────────────────────────────────────

    /**
     * Defence-in-depth (DEC-M3-02): skip the publish if the derived event would
     * not forward-advance the canonical state.
     *
     * <p>If the derived is a {@link StateChangedEvent} whose {@code newValue}
     * already equals the current materialized attribute value, the publish is
     * suppressed silently. This guards against re-publishing self-produced
     * derived events that slipped past the {@link SelfProducedFilter} (e.g.,
     * after a process restart that lost the in-memory filter set).</p>
     */
    private boolean shouldPublishDerived(EventDraft draft, EntityId subjectEntity) {
        if (!(draft.payload() instanceof StateChangedEvent sc)) {
            return true;
        }
        EntityState current = stateStore.get(subjectEntity).orElse(null);
        if (current == null) {
            return true;
        }
        AttributeValue currentValue = current.attributes().get(sc.attributeKey());
        if (currentValue == null) {
            return true;
        }
        String currentSerialized = serializeAttribute(currentValue);
        return !currentSerialized.equals(sc.newValue());
    }

    /**
     * Applies the given envelope to the entity's materialized state.
     *
     * <p>Always advances {@code stateVersion} and {@code lastUpdated}. Type-specific
     * updates per the brief's step 6:
     * <ul>
     *   <li>{@code state_reported} → update {@code lastReported}; attributes
     *       untouched.</li>
     *   <li>{@code state_changed} → update {@code attributes} and
     *       {@code lastChanged}.</li>
     *   <li>{@code availability_changed} → update {@code availability}.</li>
     *   <li>Other payload types → only {@code stateVersion} and
     *       {@code lastUpdated} advance.</li>
     * </ul>
     */
    private void applyToState(EventEnvelope envelope, EntityId entityId) {
        EntityState prior = stateStore.get(entityId).orElseGet(() -> initialEntityState(entityId));
        Instant now = clock.instant();

        EntityState updated;
        if (envelope.payload() instanceof StateReportedEvent) {
            updated = new EntityState(
                    prior.entityId(),
                    prior.attributes(),
                    prior.availability(),
                    prior.stateVersion() + 1,
                    prior.lastChanged(),
                    now,
                    now,
                    prior.staleAfter(),
                    prior.stale());
        } else if (envelope.payload() instanceof StateChangedEvent sc) {
            if (backfillActive) {
                // AMD-50 §2.2 supersession. During an active version-transition
                // reconciliation, a logged PRIOR-VERSION state_changed replaying as
                // inbound is a log event, so it advances stateVersion (the cursor,
                // INV-01) — but its attribute/lastChanged write is SUPPRESSED. The
                // current rule's re-derivation (the backfill) is the sole authority
                // for attributes during the rebuild, so a stale prior-rule value
                // cannot win the interleaving. Cursor-only, identical to the "other
                // payload" branch. (For the production 1->2 transition this path is
                // never hit — the pre-2 log holds no state_changed — but it is
                // required for generality, AMD-50 §2.5, and is exercised by the
                // supersession test.) Outside the gate this branch is unchanged
                // (the else), so LIVE/steady-state derivation is never a no-op.
                updated = new EntityState(
                        prior.entityId(),
                        prior.attributes(),
                        prior.availability(),
                        prior.stateVersion() + 1,
                        prior.lastChanged(),
                        now,
                        prior.lastReported(),
                        prior.staleAfter(),
                        prior.stale());
            } else {
                Map<String, AttributeValue> newAttrs = new HashMap<>(prior.attributes());
                newAttrs.put(sc.attributeKey(), new StringValue(sc.newValue()));
                updated = new EntityState(
                        prior.entityId(),
                        Map.copyOf(newAttrs),
                        prior.availability(),
                        prior.stateVersion() + 1,
                        now,
                        now,
                        prior.lastReported(),
                        prior.staleAfter(),
                        prior.stale());
            }
        } else if (envelope.payload() instanceof AvailabilityChangedEvent ac) {
            updated = new EntityState(
                    prior.entityId(),
                    prior.attributes(),
                    parseAvailability(ac.newStatus()),
                    prior.stateVersion() + 1,
                    prior.lastChanged(),
                    now,
                    prior.lastReported(),
                    prior.staleAfter(),
                    prior.stale());
        } else {
            updated = new EntityState(
                    prior.entityId(),
                    prior.attributes(),
                    prior.availability(),
                    prior.stateVersion() + 1,
                    prior.lastChanged(),
                    now,
                    prior.lastReported(),
                    prior.staleAfter(),
                    prior.stale());
        }
        stateStore.put(entityId, updated);
    }

    /**
     * Applies a single re-derived draft to in-memory state as part of the
     * AMD-50 §2.1 reconciliation backfill. Only {@code state_changed} drafts
     * carry attribute reconstruction (the production and future typed rules emit
     * nothing else); any other payload is ignored. The write is non-emitting and
     * cursor-preserving — see {@link #applyBackfillAttribute}.
     *
     * @param entityId        the subject entity to update
     * @param causingEnvelope the inbound event whose re-derivation produced the
     *                        draft — supplies the deterministic {@code lastChanged}
     * @param draft           the re-derived draft
     */
    private void applyBackfillDraft(EntityId entityId, EventEnvelope causingEnvelope,
                                    EventDraft draft) {
        if (draft.payload() instanceof StateChangedEvent sc) {
            applyBackfillAttribute(entityId, sc, backfillTimestamp(causingEnvelope));
        }
    }

    /**
     * Returns the deterministic, log-fixed instant used as {@code lastChanged} for
     * a reconciliation backfill write (AMD-50 §2.3): the causing event's
     * {@code eventTime} when present, else its {@code ingestTime} (the envelope's
     * recorded/ordering time, always non-null). It is NEVER the projection
     * wall-clock ({@code clock.instant()}): wall-clock would stamp every
     * reconstructed historical attribute with the rebuild time (and re-stamp it on
     * every later transition), which is both semantically wrong and a latent
     * rebuild non-determinism that a fixed test clock would silently mask — exactly
     * the failure class AMD-50 exists to prevent.
     *
     * @param causingEnvelope the inbound event that triggered the re-derivation
     * @return the log-fixed timestamp; never {@code null}
     */
    private static Instant backfillTimestamp(EventEnvelope causingEnvelope) {
        Instant eventTime = causingEnvelope.eventTime();
        return (eventTime != null) ? eventTime : causingEnvelope.ingestTime();
    }

    /**
     * Narrow attribute-write path for the reconciliation backfill (AMD-50 §2.3).
     *
     * <p>Updates ONLY the entity's {@code attributes} map (to the re-derived value)
     * and {@code lastChanged} (to the causing event's log-fixed time). It
     * <em>preserves</em> {@code stateVersion} (no increment — a backfill draft is
     * not a log event, AMD-50-INV-01), {@code lastReported}, and {@code lastUpdated}:
     * those, and the single per-event cursor {@code +1}, are owned by the triggering
     * {@code state_reported} already applied through {@link #applyToState}. It must
     * NOT route through {@code applyToState}'s {@code state_changed} branch, which
     * would double-increment the cursor and re-stamp the timestamps.</p>
     *
     * <p>Under an active gate this helper is therefore the sole writer of
     * {@code attributes} (every inbound logged {@code state_changed} is suppressed,
     * §2.2), so the materialized attribute values are fully determined by
     * re-derivation over the {@code state_reported} history — independent of any
     * logged-event interleaving.</p>
     *
     * <p>Note (conscious interim, [REVIEW]): {@code lastChanged} is event-time-sourced
     * here but remains wall-clock-sourced in the LIVE {@code applyToState}
     * {@code state_changed} branch (pre-existing, deliberately untouched). The
     * unifier is a scheduled follow-up WU.</p>
     *
     * @param entityId       the subject entity to update
     * @param sc             the re-derived {@code state_changed} payload
     * @param causeEventTime the causing event's log-fixed time (see
     *                       {@link #backfillTimestamp})
     */
    private void applyBackfillAttribute(EntityId entityId, StateChangedEvent sc,
                                        Instant causeEventTime) {
        EntityState prior = stateStore.get(entityId)
                .orElseGet(() -> initialEntityState(entityId));
        Map<String, AttributeValue> newAttrs = new HashMap<>(prior.attributes());
        newAttrs.put(sc.attributeKey(), new StringValue(sc.newValue()));
        EntityState updated = new EntityState(
                prior.entityId(),
                Map.copyOf(newAttrs),
                prior.availability(),
                prior.stateVersion(),     // preserved — backfill draft carries no cursor +1
                causeEventTime,           // lastChanged = log-fixed event time, not wall-clock
                prior.lastUpdated(),      // preserved (owned by the triggering state_reported)
                prior.lastReported(),     // preserved (owned by the triggering state_reported)
                prior.staleAfter(),
                prior.stale());
        stateStore.put(entityId, updated);
    }

    private EntityState initialEntityState(EntityId entityId) {
        Instant now = clock.instant();
        return new EntityState(
                entityId,
                Map.of(),
                Availability.UNKNOWN,
                0L,
                now,
                now,
                now,
                null,
                false);
    }

    private static Availability parseAvailability(String value) {
        if (value == null) {
            return Availability.UNKNOWN;
        }
        // AvailabilityChangedEvent uses "online"/"offline"/"unknown" per its Javadoc
        // (Doc 01 §4.3). The state-store Availability enum uses AVAILABLE/UNAVAILABLE/UNKNOWN.
        return switch (value.toLowerCase()) {
            case "online", "available" -> Availability.AVAILABLE;
            case "offline", "unavailable" -> Availability.UNAVAILABLE;
            default -> Availability.UNKNOWN;
        };
    }

    private static EntityId subjectEntityIdOrNull(SubjectRef ref) {
        if (ref.type() == SubjectType.ENTITY) {
            return new EntityId(ref.id());
        }
        return null;
    }

    private static String serializeAttribute(AttributeValue value) {
        if (value instanceof StringValue sv) {
            return sv.value();
        }
        Object raw = value.rawValue();
        return (raw == null) ? "" : raw.toString();
    }

    /**
     * Internal buffer entry for {@link #processBatch}. The READ phase produces
     * one per derived draft; the PUBLISH phase consumes them after the
     * advancer's read tx closes.
     *
     * @param causingEnvelope the inbound envelope that triggered derivation
     * @param entityId        the subject entity
     * @param draft           the derived draft to publish
     */
    private record BufferedDerivation(
            EventEnvelope causingEnvelope,
            EntityId entityId,
            EventDraft draft
    ) { }
}
