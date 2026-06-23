/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.homesynapse.device.AnyChange;
import com.homesynapse.device.CapabilityInstance;
import com.homesynapse.device.CommandDefinition;
import com.homesynapse.device.ConfirmationMode;
import com.homesynapse.device.ConfirmationResult;
import com.homesynapse.device.Entity;
import com.homesynapse.device.EntityRegistry;
import com.homesynapse.device.EnumTransition;
import com.homesynapse.device.ExactMatch;
import com.homesynapse.device.ExpectedOutcome;
import com.homesynapse.device.Expectation;
import com.homesynapse.device.WithinTolerance;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandIdempotency;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.bus.Subscriber;
import com.homesynapse.event.bus.SubscriberMode;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.value.AttributeType;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.BooleanValue;
import com.homesynapse.value.DegradedAttributeValue;
import com.homesynapse.value.EnumValue;
import com.homesynapse.value.FloatValue;
import com.homesynapse.value.IntValue;
import com.homesynapse.value.QuantityValue;
import com.homesynapse.value.StringValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link PendingCommandLedger}: the command-outcome correlation FSM that turns
 * "command sent" into the honest {@code dispatched -> confirmed | unconfirmed | failed}
 * (Doc 07 §3.11.2; the confirmed/unconfirmed half of the differentiator). It is also the
 * {@code pending_command_ledger} event-bus {@link Subscriber} (its own VT + checkpoint,
 * coalescing DISABLED), correlating each issued command to the device's actual reported
 * state and publishing {@code state_confirmed} on a match or
 * {@code command_confirmation_timed_out} on deadline expiry — both pre-existing event types
 * (AMD-92: this slice mints zero).
 *
 * <h2>Architecture (built against the FROZEN reality at {@code 1b0b6c9} — see the Completion
 * Report for the source-drift notes)</h2>
 * <ul>
 *   <li><strong>Build path.</strong> {@link #trackCommand(PendingCommand)} is the primitive —
 *       a fully-formed {@link PendingCommand} (with its {@link Expectation}) is added to the
 *       dual index. On a LIVE {@code command_issued} the ledger derives that command itself by
 *       resolving the target entity's capability for the command type
 *       ({@link EntityRegistry#findEntity} &rarr; {@link Entity#capabilities()} &rarr; the
 *       matching {@link CapabilityInstance}) and reading its {@link ExpectedOutcome}
 *       (attribute + {@link Expectation}). {@code CommandIssuedEvent} carries no attribute /
 *       expectation / policy, and no {@code ExpectationFactory} implementation exists at HEAD,
 *       so the {@link ExpectedOutcome} on the capability's {@link CommandDefinition} is the
 *       expectation source (NOT {@code ExpectationFactory}).</li>
 *   <li><strong>OPTIMISTIC bypass</strong> (AMD-90). A command whose capability declares
 *       {@link ConfirmationMode#DISABLED} (the in-tree "confirmation off / optimistic" signal;
 *       AMD-90's {@code CommandAction.confirmation} enum is not on the frozen action model) is
 *       never tracked — the guard fronts the {@code command_issued} handler, so OPTIMISTIC
 *       commands bypass {@code trackCommand} entirely.</li>
 *   <li><strong>Correlation.</strong> {@code command_result} matches an in-flight command by
 *       {@code (targetRef, commandName)} (the §3.11.2 key), tie-broken by the result's
 *       causation id; {@code state_reported} is evaluated against every in-flight command on
 *       the subject entity whose {@code targetAttribute} matches the reported key.</li>
 *   <li><strong>Pure projection</strong> (INV-SA-03). The in-memory dual index is a derived
 *       view of the immutable log — rebuilt during REPLAY by reprocessing the command
 *       lifecycle events; no parallel store. No {@code state_confirmed} is re-emitted during
 *       replay (Doc 01 §3.7).</li>
 *   <li><strong>Crash recovery by idempotency class</strong> (Doc 07 §3.11.2 / Doc 01 §3.7).
 *       At the REPLAY&rarr;LIVE boundary ({@link #onCaughtUp()}), a command still in-flight in
 *       the log is classified: {@code IDEMPOTENT} &rarr; re-offered for re-issue (a SIGNAL the
 *       ledger raises; execution is M8.2, never here — AMD-90-INV-01); {@code NOT_IDEMPOTENT}
 *       &rarr; {@code command_result(expired_on_restart)} (the {@link PendingStatus#EXPIRED}
 *       case — never silently re-fire a momentary actuator); {@code CONDITIONAL} &rarr; offered
 *       to the integration adapter for evaluation.</li>
 *   <li><strong>No autonomous retry</strong> (AMD-90-INV-01 / D2). The ledger correlates and
 *       reports; it never re-issues a command and holds no dispatch collaborator.</li>
 * </ul>
 *
 * <p>Thread-safe (LTD-11: a {@link ReentrantLock}, never {@code synchronized}; every publish
 * happens after the lock is released). All time is read from an injected {@link Clock}
 * (REC-156/167) — timeout is a deadline comparison in {@link #pollExpirations()}, never a
 * wall-clock sleep.</p>
 */
final class StandardPendingCommandLedger implements PendingCommandLedger, Subscriber {

    private static final Logger LOG = LoggerFactory.getLogger(StandardPendingCommandLedger.class);

    private static final int SCHEMA_VERSION = 1;

    /** {@code command_result.outcome} the adapter acknowledged the command (still unconfirmed). */
    private static final String OUTCOME_ACKNOWLEDGED = "acknowledged";

    /**
     * The {@code command_result.outcome} the ledger writes for a {@code NOT_IDEMPOTENT} command
     * found in-flight after restart (the {@link PendingStatus#EXPIRED} disposition;
     * {@link CommandIdempotency#NOT_IDEMPOTENT} javadoc).
     */
    private static final String OUTCOME_EXPIRED_ON_RESTART = "expired_on_restart";

    private final EventPublisher publisher;
    private final EntityRegistry entityRegistry;
    private final Clock clock;
    private final long defaultConfirmationTimeoutMs;

    /**
     * Guards every mutation of the indices, the replay accumulator, and the restart-offer
     * holding lists. Publishes are collected under the lock and flushed after release (LTD-11).
     */
    private final ReentrantLock lock = new ReentrantLock();

    /** Primary index: {@code command_issued} event id &rarr; the tracked command. */
    private final Map<EventId, Tracked> byCommand = new ConcurrentHashMap<>();

    /** Secondary index: target entity &rarr; the command ids in flight for it (§3.11.2 shape). */
    private final Map<EntityId, Set<EventId>> byEntity = new ConcurrentHashMap<>();

    /** REPLAY accumulator: commands seen in-flight in the log, pending boundary classification. */
    private final Map<EventId, ReplayInFlight> replayInFlight = new LinkedHashMap<>();

    /** {@code IDEMPOTENT} commands in-flight at restart, re-offered for re-issue (M8.2 drains). */
    private final List<ReplayInFlight> reissueOffered = new ArrayList<>();

    /** {@code CONDITIONAL} commands in-flight at restart, offered to the adapter (M8.2 drains). */
    private final List<ReplayInFlight> adapterEvaluationOffered = new ArrayList<>();

    private volatile boolean replayMode;

    /**
     * Constructs the ledger against its injected collaborators.
     *
     * @param publisher                    the durable publish surface for {@code state_confirmed}
     *                                     / {@code command_confirmation_timed_out}, never
     *                                     {@code null}
     * @param entityRegistry               resolves a target entity to its capability (and thus
     *                                     the expectation + confirmation policy) on a LIVE
     *                                     {@code command_issued}, never {@code null}
     * @param clock                        the injected clock (REC-156/167) — deadlines and
     *                                     expiry comparisons read it, never wall-clock time,
     *                                     never {@code null}
     * @param defaultConfirmationTimeoutMs the fallback confirmation window when a
     *                                     {@code command_issued} carries a non-positive
     *                                     {@code confirmationTimeoutMs} (AMD-90 default 30000;
     *                                     REC-161 calibration); must be {@code > 0}
     */
    StandardPendingCommandLedger(EventPublisher publisher, EntityRegistry entityRegistry,
                                 Clock clock, long defaultConfirmationTimeoutMs) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.entityRegistry = Objects.requireNonNull(entityRegistry, "entityRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (defaultConfirmationTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "defaultConfirmationTimeoutMs must be positive: " + defaultConfirmationTimeoutMs);
        }
        this.defaultConfirmationTimeoutMs = defaultConfirmationTimeoutMs;
    }

    // ── PendingCommandLedger ────────────────────────────────────────────────

    @Override
    public void trackCommand(PendingCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        lock.lock();
        try {
            index(new Tracked(command, command.commandEventId().value(), null));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<PendingCommand> getCommand(EventId commandEventId) {
        Objects.requireNonNull(commandEventId, "commandEventId must not be null");
        lock.lock();
        try {
            Tracked tracked = byCommand.get(commandEventId);
            return tracked == null ? Optional.empty() : Optional.of(tracked.command());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<PendingCommand> getPendingForEntity(EntityId entityRef) {
        Objects.requireNonNull(entityRef, "entityRef must not be null");
        lock.lock();
        try {
            Set<EventId> ids = byEntity.get(entityRef);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<PendingCommand> result = new ArrayList<>(ids.size());
            for (EventId id : ids) {
                Tracked tracked = byCommand.get(id);
                if (tracked != null) {
                    result.add(tracked.command());
                }
            }
            return List.copyOf(result);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int pendingCount() {
        lock.lock();
        try {
            return byCommand.size();
        } finally {
            lock.unlock();
        }
    }

    // ── Subscriber (bus lifecycle) ──────────────────────────────────────────

    @Override
    public void setMode(SubscriberMode mode) {
        this.replayMode = mode != SubscriberMode.LIVE;
    }

    @Override
    public void onEvent(EventEnvelope event) {
        if (replayMode) {
            accumulateReplay(event);    // rebuild the projection; never publish during REPLAY
            return;
        }
        switch (event.payload()) {
            case CommandIssuedEvent issued -> onCommandIssued(event, issued);
            case CommandResultEvent result -> onCommandResult(event, result);
            case StateReportedEvent reported -> onStateReported(event, reported);
            case StateConfirmedEvent confirmed -> onStateConfirmed(confirmed);
            default -> { /* not a correlation event — the filter should preclude this */ }
        }
    }

    @Override
    public void onCaughtUp() {
        classifyRestart();
        replayMode = false;
    }

    // ── LIVE handlers ───────────────────────────────────────────────────────

    /**
     * A LIVE {@code command_issued}: derive the {@link PendingCommand} from the target's
     * capability and track it (status {@link PendingStatus#DISPATCHED}). A capability with
     * {@link ConfirmationMode#DISABLED} (or no expected outcome) is the OPTIMISTIC bypass —
     * nothing is tracked (AMD-90).
     */
    private void onCommandIssued(EventEnvelope event, CommandIssuedEvent issued) {
        EntityId target = EntityId.of(issued.targetEntityRef());
        String commandName = issued.commandType();
        Optional<CapabilityInstance> capability = resolveCapability(target, commandName);
        if (capability.isEmpty()) {
            LOG.debug("command_issued {} for {}: no capability defines '{}'; not tracked",
                    event.eventId(), target, commandName);
            return;
        }
        CapabilityInstance instance = capability.get();
        if (instance.confirmation().mode() == ConfirmationMode.DISABLED) {
            return;                                 // OPTIMISTIC bypass (AMD-90)
        }
        Optional<ExpectedOutcome> outcome =
                chooseOutcome(instance.commands().get(commandName), instance);
        if (outcome.isEmpty()) {
            return;                                 // nothing to confirm — optimistic in effect
        }
        long timeoutMs = issued.confirmationTimeoutMs() > 0
                ? issued.confirmationTimeoutMs() : defaultConfirmationTimeoutMs;
        Instant deadline = clock.instant().plusMillis(timeoutMs);
        PendingCommand command = new PendingCommand(event.eventId(), target, commandName,
                outcome.get().attributeKey(), outcome.get().expectation(), deadline,
                issued.idempotencyClass(), PendingStatus.DISPATCHED);
        lock.lock();
        try {
            index(new Tracked(command, event.causalContext().correlationId(), null));
        } finally {
            lock.unlock();
        }
    }

    /**
     * A LIVE {@code command_result}: {@code acknowledged} advances the entry to
     * {@link PendingStatus#ACKNOWLEDGED}; {@code rejected}/{@code timed_out} removes it and
     * emits {@code command_confirmation_timed_out} once (removal makes the emission idempotent).
     */
    private void onCommandResult(EventEnvelope event, CommandResultEvent result) {
        EntityId target = EntityId.of(result.targetEntityRef());
        List<Publication> pending = new ArrayList<>();
        lock.lock();
        try {
            Optional<Tracked> match =
                    findPending(target, result.commandType(), event.causalContext().causationId());
            if (match.isPresent()) {
                Tracked tracked = match.get();
                if (OUTCOME_ACKNOWLEDGED.equals(result.outcome())) {
                    index(tracked.withStatus(PendingStatus.ACKNOWLEDGED).withResult(event.eventId()));
                } else {                            // rejected / timed_out
                    remove(tracked);
                    pending.add(timedOut(tracked, event.eventId()));
                }
            }
        } finally {
            lock.unlock();
        }
        publishAll(pending);
    }

    /**
     * A LIVE {@code state_reported}: evaluate each in-flight command on the subject entity whose
     * {@code targetAttribute} matches the reported key. A {@link ConfirmationResult#CONFIRMED}
     * emits {@code state_confirmed} (referencing the command and this report) and drops the
     * entry; any other verdict keeps it (it ages out via {@link #pollExpirations()} — no
     * autonomous retry).
     */
    private void onStateReported(EventEnvelope event, StateReportedEvent reported) {
        EntityId target = EntityId.of(event.subjectRef().id());
        List<Publication> pending = new ArrayList<>();
        lock.lock();
        try {
            Set<EventId> ids = byEntity.get(target);
            if (ids != null) {
                for (EventId id : List.copyOf(ids)) {       // copy: confirmation mutates the set
                    Tracked tracked = byCommand.get(id);
                    if (tracked == null
                            || !tracked.command().targetAttribute().equals(reported.attributeKey())) {
                        continue;
                    }
                    Expectation expectation = tracked.command().expectation();
                    AttributeValue value = coerce(reported.value(), reported.unit(), expectation);
                    if (expectation.evaluate(value) == ConfirmationResult.CONFIRMED) {
                        remove(tracked);
                        pending.add(confirmed(tracked, event.eventId(), reported.value()));
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        publishAll(pending);
    }

    /**
     * A LIVE {@code state_confirmed} (the ledger's own output, redelivered, or an externally
     * produced confirmation): drop the matching in-flight entry if any survives. Idempotent —
     * the entry was already removed when this confirmation was produced.
     */
    private void onStateConfirmed(StateConfirmedEvent confirmed) {
        lock.lock();
        try {
            Tracked tracked = byCommand.get(confirmed.commandEventId());
            if (tracked != null) {
                remove(tracked);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Compares the injected clock to every in-flight deadline and times out the expired ones —
     * emitting {@code command_confirmation_timed_out} once each (removal makes it idempotent).
     * This is the timeout tick: deterministic under a stepped clock, no wall-clock sleep
     * (REC-156/167). A periodic driver is wired above the ledger (composition-root handoff).
     */
    void pollExpirations() {
        Instant now = clock.instant();
        List<Publication> pending = new ArrayList<>();
        lock.lock();
        try {
            for (EventId id : List.copyOf(byCommand.keySet())) {
                Tracked tracked = byCommand.get(id);
                if (tracked != null && !now.isBefore(tracked.command().deadline())) {
                    remove(tracked);
                    pending.add(timedOut(tracked, tracked.resultEventId()));
                }
            }
        } finally {
            lock.unlock();
        }
        publishAll(pending);
    }

    // ── REPLAY rebuild + crash-recovery classification ──────────────────────

    /**
     * Rebuilds in-flight state from a replayed command-lifecycle event (INV-SA-03). A
     * {@code command_issued} records an in-flight candidate; a terminal {@code command_result}
     * or a {@code state_confirmed} removes it; an {@code acknowledged} result records the result
     * id. No event is published during REPLAY (Doc 01 §3.7 — no {@code state_confirmed} re-emit).
     */
    private void accumulateReplay(EventEnvelope event) {
        switch (event.payload()) {
            case CommandIssuedEvent issued -> {
                EntityId target = EntityId.of(issued.targetEntityRef());
                long timeoutMs = issued.confirmationTimeoutMs() > 0
                        ? issued.confirmationTimeoutMs() : defaultConfirmationTimeoutMs;
                Instant base = event.eventTime() != null ? event.eventTime() : event.ingestTime();
                lock.lock();
                try {
                    replayInFlight.put(event.eventId(), new ReplayInFlight(event.eventId(), target,
                            issued.commandType(), issued.idempotencyClass(),
                            base.plusMillis(timeoutMs), event.causalContext().correlationId(), null));
                } finally {
                    lock.unlock();
                }
            }
            case CommandResultEvent result -> {
                EntityId target = EntityId.of(result.targetEntityRef());
                lock.lock();
                try {
                    findReplayKey(target, result.commandType(), event.causalContext().causationId())
                            .ifPresent(key -> {
                                if (OUTCOME_ACKNOWLEDGED.equals(result.outcome())) {
                                    replayInFlight.put(key,
                                            replayInFlight.get(key).withResult(event.eventId()));
                                } else {
                                    replayInFlight.remove(key);     // concluded — not in-flight
                                }
                            });
                } finally {
                    lock.unlock();
                }
            }
            case StateConfirmedEvent confirmed -> {
                lock.lock();
                try {
                    replayInFlight.remove(confirmed.commandEventId());
                } finally {
                    lock.unlock();
                }
            }
            default -> { /* state_reported in REPLAY: confirmation is recorded as state_confirmed */ }
        }
    }

    /**
     * Classifies the commands still in-flight at the REPLAY&rarr;LIVE boundary by idempotency
     * class (Doc 07 §3.11.2). The ledger raises re-offer SIGNALS but never re-issues
     * (AMD-90-INV-01); {@code NOT_IDEMPOTENT} is expired with a {@code command_result}.
     */
    private void classifyRestart() {
        List<Publication> pending = new ArrayList<>();
        lock.lock();
        try {
            for (ReplayInFlight candidate : replayInFlight.values()) {
                switch (candidate.idempotency()) {
                    case IDEMPOTENT -> reissueOffered.add(candidate);
                    case CONDITIONAL -> adapterEvaluationOffered.add(candidate);
                    case NOT_IDEMPOTENT -> pending.add(expiredOnRestart(candidate));
                }
            }
            replayInFlight.clear();
        } finally {
            lock.unlock();
        }
        publishAll(pending);
    }

    // ── Index maintenance (all callers hold {@link #lock}) ──────────────────

    private void index(Tracked tracked) {
        EventId id = tracked.command().commandEventId();
        byCommand.put(id, tracked);
        byEntity.computeIfAbsent(tracked.command().targetRef(), key -> new HashSet<>()).add(id);
    }

    private void remove(Tracked tracked) {
        EventId id = tracked.command().commandEventId();
        byCommand.remove(id);
        Set<EventId> ids = byEntity.get(tracked.command().targetRef());
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) {
                byEntity.remove(tracked.command().targetRef());
            }
        }
    }

    private Optional<Tracked> findPending(EntityId target, String commandName, Ulid causationId) {
        if (causationId != null) {
            Tracked precise = byCommand.get(EventId.of(causationId));
            if (precise != null && precise.command().targetRef().equals(target)
                    && precise.command().commandName().equals(commandName)) {
                return Optional.of(precise);
            }
        }
        Set<EventId> ids = byEntity.get(target);
        if (ids == null) {
            return Optional.empty();
        }
        for (EventId id : ids) {
            Tracked tracked = byCommand.get(id);
            if (tracked != null && tracked.command().commandName().equals(commandName)) {
                return Optional.of(tracked);
            }
        }
        return Optional.empty();
    }

    private Optional<EventId> findReplayKey(EntityId target, String commandName, Ulid causationId) {
        if (causationId != null) {
            EventId key = EventId.of(causationId);
            ReplayInFlight precise = replayInFlight.get(key);
            if (precise != null && precise.targetRef().equals(target)
                    && precise.commandName().equals(commandName)) {
                return Optional.of(key);
            }
        }
        for (ReplayInFlight candidate : replayInFlight.values()) {
            if (candidate.targetRef().equals(target) && candidate.commandName().equals(commandName)) {
                return Optional.of(candidate.commandEventId());
            }
        }
        return Optional.empty();
    }

    // ── Capability resolution (no lock; reads the immutable registry) ───────

    private Optional<CapabilityInstance> resolveCapability(EntityId target, String commandName) {
        Optional<Entity> entity = entityRegistry.findEntity(target);
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        for (CapabilityInstance instance : entity.get().capabilities()) {
            if (instance.commands().containsKey(commandName)) {
                return Optional.of(instance);
            }
        }
        return Optional.empty();
    }

    /**
     * Chooses the expected outcome to confirm against: the one on an authoritative attribute if
     * the policy names one, else the first declared outcome. Empty when the command declares no
     * outcome (nothing to confirm).
     */
    private static Optional<ExpectedOutcome> chooseOutcome(CommandDefinition definition,
                                                           CapabilityInstance instance) {
        if (definition == null || definition.expectedOutcomes().isEmpty()) {
            return Optional.empty();
        }
        List<String> authoritative = instance.confirmation().authoritativeAttributes();
        for (ExpectedOutcome outcome : definition.expectedOutcomes()) {
            if (authoritative.contains(outcome.attributeKey())) {
                return Optional.of(outcome);
            }
        }
        return Optional.of(definition.expectedOutcomes().get(0));
    }

    // ── Publication builders ────────────────────────────────────────────────

    private Publication confirmed(Tracked tracked, EventId reportEventId, String actualValue) {
        PendingCommand command = tracked.command();
        StateConfirmedEvent payload = new StateConfirmedEvent(command.commandEventId(),
                reportEventId, command.targetAttribute(), expectedValueOf(command.expectation()),
                actualValue, matchTypeOf(command.expectation()));
        return new Publication(EventTypes.STATE_CONFIRMED, payload, command.targetRef(),
                EventPriority.NORMAL, tracked.correlationId(), reportEventId.value());
    }

    private Publication timedOut(Tracked tracked, EventId resultEventId) {
        PendingCommand command = tracked.command();
        CommandConfirmationTimedOutEvent payload =
                new CommandConfirmationTimedOutEvent(command.commandEventId(), resultEventId);
        return new Publication(EventTypes.COMMAND_CONFIRMATION_TIMED_OUT, payload,
                command.targetRef(), EventPriority.DIAGNOSTIC, tracked.correlationId(),
                command.commandEventId().value());
    }

    private Publication expiredOnRestart(ReplayInFlight candidate) {
        CommandResultEvent payload = new CommandResultEvent(candidate.targetRef().value(),
                candidate.commandName(), OUTCOME_EXPIRED_ON_RESTART,
                "command was in-flight at restart and is not idempotent");
        return new Publication(EventTypes.COMMAND_RESULT, payload, candidate.targetRef(),
                EventPriority.NORMAL, candidate.correlationId(), candidate.commandEventId().value());
    }

    private void publishAll(List<Publication> publications) {
        for (Publication publication : publications) {
            EventDraft draft = new EventDraft(publication.eventType(), SCHEMA_VERSION, null,
                    SubjectRef.entity(publication.subject()), publication.priority(),
                    EventOrigin.AUTOMATION, publication.payload(), null, null);
            try {
                publisher.publish(draft, CausalContext.chain(
                        publication.correlationId(), publication.causationId()));
            } catch (SequenceConflictException ex) {
                LOG.error("Failed to publish {} for entity {}: sequence conflict",
                        publication.eventType(), publication.subject(), ex);
            }
        }
    }

    // ── Reported-value coercion (String state_reported -> typed AttributeValue) ─

    /**
     * Coerces a {@code state_reported} serialized value to the {@link AttributeType} the
     * {@link Expectation} compares against. {@code StateReportedEvent.value} is a String at HEAD
     * while {@link Expectation#evaluate(AttributeValue)} needs a typed value, so the coercion is
     * directed by the expectation's expected type. Total — an uncoercible value yields a
     * {@link DegradedAttributeValue} (typed-absent; the M7.2b precedent), never an exception.
     */
    private static AttributeValue coerce(String reported, String unit, Expectation expectation) {
        AttributeType type = targetTypeOf(expectation);
        try {
            return switch (type) {
                case BOOLEAN -> new BooleanValue(Boolean.parseBoolean(reported.trim()));
                case INT -> new IntValue(Long.parseLong(reported.trim()));
                case FLOAT -> new FloatValue(Double.parseDouble(reported.trim()));
                case STRING -> new StringValue(reported);
                case ENUM -> new EnumValue(reported);
                case QUANTITY -> unit != null && !unit.isBlank()
                        ? new QuantityValue(Double.parseDouble(reported.trim()), unit)
                        : new FloatValue(Double.parseDouble(reported.trim()));
                case ARRAY, DEGRADED -> degraded(reported, type);
            };
        } catch (RuntimeException ex) {
            return degraded(reported, type);
        }
    }

    private static AttributeValue degraded(String reported, AttributeType type) {
        return new DegradedAttributeValue(type.name(), reported,
                "state_reported value not coercible to " + type);
    }

    private static AttributeType targetTypeOf(Expectation expectation) {
        return switch (expectation) {
            case ExactMatch exact -> exact.expectedValue().attributeType();
            case AnyChange any -> any.previousValue().attributeType();
            case WithinTolerance ignored -> AttributeType.FLOAT;
            case EnumTransition ignored -> AttributeType.ENUM;
        };
    }

    private static String matchTypeOf(Expectation expectation) {
        return switch (expectation) {
            case ExactMatch ignored -> "exact";
            case WithinTolerance ignored -> "within_tolerance";
            case EnumTransition ignored -> "enum_transition";
            case AnyChange ignored -> "any_change";
        };
    }

    private static String expectedValueOf(Expectation expectation) {
        return switch (expectation) {
            case ExactMatch exact -> String.valueOf(exact.expectedValue().rawValue());
            case WithinTolerance tolerance -> String.valueOf(tolerance.target());
            case EnumTransition transition -> transition.expectedValue();
            case AnyChange any -> String.valueOf(any.previousValue().rawValue());
        };
    }

    // ── Restart-offer accessors (package-private — the signal M8.2 drains; tested here) ─

    /**
     * The {@code IDEMPOTENT} commands found in-flight at the last restart and re-offered for
     * re-issue. The ledger raises the signal; execution is M8.2, above the engine
     * (AMD-90-INV-01). Never re-issued here.
     *
     * @return an unmodifiable snapshot, never {@code null}
     */
    List<ReplayInFlight> reissueOffered() {
        lock.lock();
        try {
            return List.copyOf(reissueOffered);
        } finally {
            lock.unlock();
        }
    }

    /**
     * The {@code CONDITIONAL} commands found in-flight at the last restart and offered to the
     * integration adapter for re-issue evaluation (M8.2).
     *
     * @return an unmodifiable snapshot, never {@code null}
     */
    List<ReplayInFlight> adapterEvaluationOffered() {
        lock.lock();
        try {
            return List.copyOf(adapterEvaluationOffered);
        } finally {
            lock.unlock();
        }
    }

    // ── Internal records ────────────────────────────────────────────────────

    /**
     * A live-tracked command plus the correlation metadata the {@link PendingCommand} record
     * cannot carry: the run {@code correlation_id} to thread onto outcome events, and the
     * {@code command_result} event id observed at {@code ACKNOWLEDGED} (the {@code resultEventId}
     * referenced by a later {@code command_confirmation_timed_out}).
     */
    private record Tracked(PendingCommand command, Ulid correlationId, EventId resultEventId) {

        Tracked withStatus(PendingStatus status) {
            PendingCommand updated = new PendingCommand(command.commandEventId(),
                    command.targetRef(), command.commandName(), command.targetAttribute(),
                    command.expectation(), command.deadline(), command.idempotency(), status);
            return new Tracked(updated, correlationId, resultEventId);
        }

        Tracked withResult(EventId resultEventId) {
            return new Tracked(command, correlationId, resultEventId);
        }
    }

    /**
     * A command observed in-flight in the log during REPLAY (and the crash-recovery signal it
     * becomes). Reconstructable from the immutable log alone (INV-SA-03): identity, target,
     * idempotency class, the event-derived deadline, the run correlation, and the acknowledging
     * {@code command_result} id (if one was logged).
     */
    record ReplayInFlight(
            EventId commandEventId,
            EntityId targetRef,
            String commandName,
            CommandIdempotency idempotency,
            Instant deadline,
            Ulid correlationId,
            EventId resultEventId) {

        ReplayInFlight withResult(EventId resultEventId) {
            return new ReplayInFlight(commandEventId, targetRef, commandName, idempotency,
                    deadline, correlationId, resultEventId);
        }
    }

    /** A pending event publication, captured under the lock and flushed after release (LTD-11). */
    private record Publication(
            String eventType,
            DomainEvent payload,
            EntityId subject,
            EventPriority priority,
            Ulid correlationId,
            Ulid causationId) {
    }
}
