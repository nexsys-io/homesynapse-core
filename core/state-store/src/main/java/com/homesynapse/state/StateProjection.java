/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.state;

import com.homesynapse.device.AttributeValue;
import com.homesynapse.device.StringValue;
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
 * <p>When the persisted checkpoint's {@code projectionVersion} does not match
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

    private static final Logger log = LoggerFactory.getLogger(StateProjection.class);

    private final ProjectionId projectionId;
    private final int projectionVersion;
    private final ViewCheckpointStore checkpointStore;
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
            StateStore stateStore,
            DerivationRule rule,
            EventPublisher publisher,
            ProjectionAdvancer advancer,
            CheckpointPolicy checkpointPolicy,
            Clock clock,
            DerivedPublishGate publishGate) {
        Objects.requireNonNull(clock, "clock must not be null");
        return new StateProjection(
                projectionId, projectionVersion, checkpointStore, stateStore,
                rule, publisher, advancer, checkpointPolicy, clock, publishGate,
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
     * Sets the subscriber's lifecycle mode. The bus's lifecycle code calls this
     * during {@code COLD → REPLAY → TRANSITION → LIVE → SUSPENDED} transitions.
     *
     * @param mode the new mode; never {@code null}
     */
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
            DerivationContext ctx = new DerivationContext(priorState, inbound, clock);
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
            log.info("StateProjection {} caught up at position {}",
                    projectionId.value(), cursorPosition);
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
                DerivationContext ctx = new DerivationContext(prior, env, clock);
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
                    buffered.add(new BufferedDerivation(env, entityId, d));
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
            if (record.projectionVersion() != projectionVersion) {
                boolean allowStale = Boolean.parseBoolean(
                        System.getProperty(ALLOW_STALE_SNAPSHOTS_PROPERTY, "false"));
                if (allowStale) {
                    log.warn("Stale snapshot accepted for {}: checkpoint version {} != "
                                    + "projection version {} (escape hatch active)",
                            projectionId.value(),
                            record.projectionVersion(),
                            projectionVersion);
                    cursorPosition = record.position();
                } else {
                    log.info("Reconciliation triggered for {}: checkpoint version {} != "
                                    + "projection version {}; clearing state, resetting cursor to 0",
                            projectionId.value(),
                            record.projectionVersion(),
                            projectionVersion);
                    stateStore.clear();
                    cursorPosition = 0L;
                }
            } else {
                cursorPosition = record.position();
                // Phase 2 stub: byte[] data is opaque; deserialization is M3.5b
                // scope. For M3.5a, the in-memory state store is shared across
                // restart in tests via the fixture, so we don't re-hydrate from
                // bytes.
            }
        } else {
            cursorPosition = 0L;
        }
        lastCheckpointAt = clock.instant();
        eventsSinceCheckpoint = 0L;
        initialized = true;
    }

    private void advanceCheckpointCadence(long globalPosition) {
        cursorPosition = Math.max(cursorPosition, globalPosition);
        eventsSinceCheckpoint++;
        Instant now = clock.instant();
        Duration since = (lastCheckpointAt != null)
                ? Duration.between(lastCheckpointAt, now)
                : Duration.ZERO;
        if (checkpointPolicy.shouldCheckpoint(eventsSinceCheckpoint, since, 0L)) {
            writeCheckpoint(now);
        }
    }

    private void writeCheckpoint(Instant now) {
        // Phase 2 stub data: M3.5b will add Jackson serialization of the
        // materialized state map. The opaque byte[] contract is preserved by
        // ViewCheckpointStore, so the empty payload is harmless for M3.5a's
        // in-memory tests.
        byte[] data = new byte[0];
        checkpointStore.writeCheckpoint(projectionId.value(), cursorPosition, data);
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
