/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import com.homesynapse.event.AvailabilityChangedEvent;
import com.homesynapse.event.CausalContext;
import com.homesynapse.event.DomainEvent;
import com.homesynapse.event.EventDraft;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventOrigin;
import com.homesynapse.event.EventPriority;
import com.homesynapse.event.EventPublisher;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.SequenceConflictException;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.event.TriggerDurationCancelledEvent;
import com.homesynapse.event.TriggerDurationExpiredEvent;
import com.homesynapse.event.TriggerDurationLimitExceededEvent;
import com.homesynapse.event.TriggerDurationStartedEvent;
import com.homesynapse.event.TriggerDurationStateValidatedEvent;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.DeviceId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.value.AttributeValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link TriggerEvaluator}: O(1) trigger-index lookup plus AMD-25
 * {@code for_duration} sustained-state timers (Doc 07 §3.4).
 *
 * <p><strong>What M7.1 does and does not do.</strong> {@link #evaluate(EventEnvelope)}
 * returns the automation IDs whose triggers matched the event — the decision to run. It
 * does NOT initiate Runs, publish {@code automation_triggered}, or execute actions
 * (M7.2). Its observable production behavior is the duration-timer lifecycle and the
 * {@code trigger_duration_*} diagnostics (SD-3 C1-interim pin). The
 * {@code automation_slug_redirect}/{@code automation_capability_mismatch} records are
 * registered in the vocabulary but have no production publish site here (their
 * substrates are M-future).</p>
 *
 * <p><strong>Concurrency (LTD-11 / LTD-01).</strong> The timer map is guarded by a
 * {@link ReentrantLock} — never {@code synchronized}. Each duration timer runs on its
 * own virtual thread; cancellation is via {@link Thread#interrupt()}.</p>
 *
 * <p><strong>Expiry seam (REC-156).</strong> A timer's expiry is decided by comparing
 * the injected {@link Clock} to {@code expiresAt} (computed from the clock at start),
 * NOT by a raw wall-clock sleep. The per-timer virtual thread wakes after a wall-clock
 * estimate and delegates the decision to {@link #pollExpirations()}; tests advance a
 * stepping clock and call {@code pollExpirations()} to drive expiry deterministically
 * without real sleeping. {@code Instant + Duration} arithmetic is DST-agnostic
 * (REC-167).</p>
 *
 * <p><strong>Replay (Doc 07 §3.10).</strong> In REPLAY mode no timers are started; the
 * adapter feeds prior {@code trigger_duration_started} events to
 * {@link #rebuildTimer} at the REPLAY→LIVE transition, re-deriving each timer with its
 * remaining duration (fire-immediately if already elapsed during downtime).</p>
 */
public final class StandardTriggerEvaluator implements TriggerEvaluator, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(StandardTriggerEvaluator.class);

    /** Default concurrent-duration-timer ceiling (config: automation.trigger.max_concurrent_duration_timers). */
    public static final int DEFAULT_MAX_DURATION_TIMERS = 256;

    private final StandardAutomationRegistry registry;
    private final SelectorResolver resolver;
    private final StateQueryService stateQuery;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int maxConcurrentDurationTimers;

    private final ReentrantLock timerLock = new ReentrantLock();
    private final Map<TimerKey, ActiveTimer> activeTimers = new LinkedHashMap<>();
    private volatile boolean replayMode;

    /**
     * Constructs a trigger evaluator with the default timer ceiling.
     *
     * @param registry   the definition registry + trigger index, never {@code null}
     * @param resolver   the selector resolver (entity membership), never {@code null}
     * @param stateQuery the state query service (expiry validation read), never {@code null}
     * @param publisher  the event publisher (diagnostics), never {@code null}
     * @param clock      the injected clock (timer arithmetic), never {@code null}
     */
    public StandardTriggerEvaluator(StandardAutomationRegistry registry,
                                    SelectorResolver resolver,
                                    StateQueryService stateQuery,
                                    EventPublisher publisher,
                                    Clock clock) {
        this(registry, resolver, stateQuery, publisher, clock, DEFAULT_MAX_DURATION_TIMERS);
    }

    /**
     * Constructs a trigger evaluator with an explicit timer ceiling.
     *
     * @param maxConcurrentDurationTimers the concurrent-timer ceiling, {@code >= 0}
     */
    public StandardTriggerEvaluator(StandardAutomationRegistry registry,
                                    SelectorResolver resolver,
                                    StateQueryService stateQuery,
                                    EventPublisher publisher,
                                    Clock clock,
                                    int maxConcurrentDurationTimers) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.stateQuery = Objects.requireNonNull(stateQuery, "stateQuery");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxConcurrentDurationTimers < 0) {
            throw new IllegalArgumentException(
                    "maxConcurrentDurationTimers must be >= 0: " + maxConcurrentDurationTimers);
        }
        this.maxConcurrentDurationTimers = maxConcurrentDurationTimers;
    }

    @Override
    public List<AutomationId> evaluate(EventEnvelope event) {
        List<TriggerMatch> matches = evaluateMatches(event);
        List<AutomationId> ids = new ArrayList<>(matches.size());
        for (TriggerMatch match : matches) {
            ids.add(match.automationId());
        }
        return List.copyOf(ids);
    }

    /**
     * The same evaluation as {@link #evaluate(EventEnvelope)} — duration-timer cancellation,
     * timer starts, and immediate matching — but returns, per matched automation, the indices
     * of the triggers that produced an <em>immediate</em> match (a {@code for_duration} trigger
     * starts a timer and is NOT an immediate match; it fires later via timer expiry, not run
     * initiation). This is the single source of truth for the matched-trigger indices the M7.4b
     * run-initiation path needs ({@link RunContext#matchedTriggers()} / {@code automation_triggered}):
     * they come straight from this loop, never from re-checking triggers (which would duplicate
     * the matching logic and risk silent divergence). {@link #evaluate(EventEnvelope)} is the
     * thin projection of this to the automation IDs.
     *
     * <p>Package-private — consumed by the co-located {@code automation_engine} subscriber's
     * run initiator (LIVE only; D2). Side-effect parity with {@link #evaluate(EventEnvelope)} is
     * exact, so the subscriber calls this once and never both methods.</p>
     *
     * @param event the incoming event, never {@code null}
     * @return the matched automations with their immediate-match trigger indices, in execution
     *         order (the registry's candidate buckets are pre-sorted priority-desc then
     *         {@code automationId}-asc), never {@code null}
     */
    List<TriggerMatch> evaluateMatches(EventEnvelope event) {
        Objects.requireNonNull(event, "event must not be null");

        // (1) Re-evaluate active timers affected by this event for cancellation.
        processCancellations(event);

        // (2) Match candidate automations; start timers or record immediate-match indices.
        Map<AutomationId, List<Integer>> matched = new LinkedHashMap<>();
        for (AutomationDefinition automation : registry.candidatesForEventType(event.eventType())) {
            if (!automation.enabled()) {
                continue;
            }
            List<TriggerDefinition> triggers = automation.triggers();
            for (int index = 0; index < triggers.size(); index++) {
                TriggerDefinition trigger = triggers.get(index);
                if (!triggerMatchesEvent(trigger, event, automation.automationId())) {
                    continue;
                }
                Duration forDuration = forDurationOf(trigger);
                if (forDuration != null) {
                    maybeStartDurationTimer(automation, index, trigger, forDuration, event);
                } else {
                    matched.computeIfAbsent(automation.automationId(), key -> new ArrayList<>())
                            .add(index);
                }
            }
        }
        List<TriggerMatch> result = new ArrayList<>(matched.size());
        for (Map.Entry<AutomationId, List<Integer>> entry : matched.entrySet()) {
            result.add(new TriggerMatch(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return List.copyOf(result);
    }

    @Override
    public void cancelDurationTimer(AutomationId automationId, int triggerIndex) {
        Objects.requireNonNull(automationId, "automationId must not be null");
        // Public entry point used by hot-reload reconciliation (§3.7): a changed trigger
        // definition cancels its timer with reason "definition_changed".
        cancel(new TimerKey(automationId, triggerIndex), "definition_changed");
    }

    @Override
    public int activeDurationTimerCount() {
        timerLock.lock();
        try {
            return activeTimers.size();
        } finally {
            timerLock.unlock();
        }
    }

    // ---- Package-private seams (subscriber adapter + tests) -----------------

    /** Switches the evaluator between REPLAY (timers suppressed) and LIVE (§3.10). */
    void setReplayMode(boolean replay) {
        this.replayMode = replay;
    }

    /** Whether the evaluator is currently suppressing timer starts for REPLAY. */
    boolean isReplayMode() {
        return replayMode;
    }

    /**
     * Cancels a timer with an explicit reason (predicate_false / definition_changed /
     * automation_removed). No-op if no timer is active for the key.
     */
    void cancel(TimerKey key, String reason) {
        ActiveTimer timer;
        timerLock.lock();
        try {
            timer = activeTimers.remove(key);
        } finally {
            timerLock.unlock();
        }
        if (timer == null) {
            return;
        }
        timer.virtualThread.interrupt();
        publish(EventTypes.TRIGGER_DURATION_CANCELLED,
                new TriggerDurationCancelledEvent(key.automationId(), key.triggerIndex(),
                        timer.triggerId, timer.startingEventId, reason),
                key.automationId(), timer.correlationId, timer.startingEventId.value(),
                timer.actorRef);
    }

    /**
     * Drives clock-gated expiry: fires every active timer whose {@code expiresAt} has
     * been reached per the injected clock. Called by the per-timer virtual thread on
     * wake-up, by a production scheduler tick, and by tests after stepping the clock.
     */
    void pollExpirations() {
        List<TimerKey> due = new ArrayList<>();
        Instant now = clock.instant();
        timerLock.lock();
        try {
            for (Map.Entry<TimerKey, ActiveTimer> entry : activeTimers.entrySet()) {
                if (!now.isBefore(entry.getValue().expiresAt)) {
                    due.add(entry.getKey());
                }
            }
        } finally {
            timerLock.unlock();
        }
        for (TimerKey key : due) {
            expire(key);
        }
    }

    /**
     * Re-derives a duration timer at the REPLAY→LIVE transition from a prior
     * {@code trigger_duration_started} event (Doc 07 §3.10). The remaining duration is
     * {@code forDuration - (now - originalStartedAt)}; if it has already elapsed, the
     * trigger fires immediately.
     */
    void rebuildTimer(AutomationId automationId, int triggerIndex, EventId startingEventId,
                      Ulid subjectUlid, Duration forDuration, Instant originalStartedAt,
                      Ulid correlationId, Ulid actorRef) {
        Optional<AutomationDefinition> definition = registry.get(automationId);
        if (definition.isEmpty() || triggerIndex >= definition.get().triggers().size()) {
            return; // definition changed/removed during downtime — conservative skip
        }
        TriggerDefinition trigger = definition.get().triggers().get(triggerIndex);
        Instant now = clock.instant();
        Instant expiresAt = originalStartedAt.plus(forDuration);
        TimerKey key = new TimerKey(automationId, triggerIndex);
        if (!now.isBefore(expiresAt)) {
            // Should have expired during downtime — fire immediately.
            publishExpired(key, triggerIdOf(trigger), startingEventId, correlationId, actorRef);
            validateAtExpiry(key, trigger, subjectUlid, startingEventId, correlationId, actorRef);
            return;
        }
        timerLock.lock();
        try {
            if (activeTimers.containsKey(key)) {
                return;
            }
            ActiveTimer timer = new ActiveTimer(triggerIdOf(trigger), startingEventId,
                    subjectUlid, trigger, expiresAt, correlationId, actorRef);
            timer.virtualThread = startTimerThread(key, expiresAt);
            activeTimers.put(key, timer);
        } finally {
            timerLock.unlock();
        }
    }

    @Override
    public void close() {
        timerLock.lock();
        try {
            for (ActiveTimer timer : activeTimers.values()) {
                timer.virtualThread.interrupt();
            }
            activeTimers.clear();
        } finally {
            timerLock.unlock();
        }
    }

    // ---- Cancellation + timer lifecycle ------------------------------------

    private void processCancellations(EventEnvelope event) {
        Ulid subject = event.subjectRef().id();
        List<TimerKey> toCancel = new ArrayList<>();
        timerLock.lock();
        try {
            for (Map.Entry<TimerKey, ActiveTimer> entry : activeTimers.entrySet()) {
                ActiveTimer timer = entry.getValue();
                if (!timer.subjectUlid.equals(subject)) {
                    continue;
                }
                if (relevantToTimer(timer.trigger, event)
                        && !triggerMatchesEvent(timer.trigger, event, entry.getKey().automationId())) {
                    toCancel.add(entry.getKey());
                }
            }
        } finally {
            timerLock.unlock();
        }
        for (TimerKey key : toCancel) {
            cancel(key, "predicate_false");
        }
    }

    private void maybeStartDurationTimer(AutomationDefinition automation, int triggerIndex,
                                         TriggerDefinition trigger, Duration forDuration,
                                         EventEnvelope event) {
        if (replayMode) {
            return; // §3.10 — no timers started during REPLAY
        }
        TimerKey key = new TimerKey(automation.automationId(), triggerIndex);
        Ulid subject = event.subjectRef().id();
        Ulid correlationId = event.causalContext().correlationId();
        EventId startingEventId = event.eventId();
        Ulid actorRef = event.actorRef();
        Instant expiresAt = clock.instant().plus(forDuration);

        boolean started = false;
        int limitCount = -1;
        timerLock.lock();
        try {
            if (activeTimers.containsKey(key)) {
                return; // already running — first matching event owns the window (§3.4 step 1)
            }
            if (activeTimers.size() >= maxConcurrentDurationTimers) {
                limitCount = activeTimers.size();
            } else {
                ActiveTimer timer = new ActiveTimer(triggerIdOf(trigger), startingEventId,
                        subject, trigger, expiresAt, correlationId, actorRef);
                timer.virtualThread = startTimerThread(key, expiresAt);
                activeTimers.put(key, timer);
                started = true;
            }
        } finally {
            timerLock.unlock();
        }

        // Publish outside the lock — never hold a lock during event-store I/O.
        if (limitCount >= 0) {
            publish(EventTypes.TRIGGER_DURATION_LIMIT_EXCEEDED,
                    new TriggerDurationLimitExceededEvent(automation.automationId(), triggerIndex,
                            triggerIdOf(trigger), limitCount, maxConcurrentDurationTimers),
                    automation.automationId(), correlationId, startingEventId.value(), actorRef);
        } else if (started) {
            publish(EventTypes.TRIGGER_DURATION_STARTED,
                    new TriggerDurationStartedEvent(automation.automationId(), triggerIndex,
                            triggerIdOf(trigger), startingEventId, EntityId.of(subject),
                            forDuration.toMillis()),
                    automation.automationId(), correlationId, startingEventId.value(), actorRef);
        }
    }

    private Thread startTimerThread(TimerKey key, Instant expiresAt) {
        return Thread.ofVirtual()
                .name("automation-duration-timer-" + key.automationId() + "-" + key.triggerIndex())
                .start(() -> {
                    long sleepMs = Duration.between(clock.instant(), expiresAt).toMillis();
                    try {
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs);
                        }
                    } catch (InterruptedException ex) {
                        return; // cancelled — the canceller already published the event
                    }
                    pollExpirations(); // fire if the clock confirms expiry
                });
    }

    private void expire(TimerKey key) {
        ActiveTimer timer;
        timerLock.lock();
        try {
            timer = activeTimers.remove(key);
        } finally {
            timerLock.unlock();
        }
        if (timer == null) {
            return; // already fired or cancelled — first remover wins
        }
        timer.virtualThread.interrupt();
        publishExpired(key, timer.triggerId, timer.startingEventId, timer.correlationId,
                timer.actorRef);
        validateAtExpiry(key, timer.trigger, timer.subjectUlid, timer.startingEventId,
                timer.correlationId, timer.actorRef);
    }

    private void publishExpired(TimerKey key, String triggerId, EventId startingEventId,
                                Ulid correlationId, Ulid actorRef) {
        publish(EventTypes.TRIGGER_DURATION_EXPIRED,
                new TriggerDurationExpiredEvent(key.automationId(), key.triggerIndex(),
                        triggerId, startingEventId),
                key.automationId(), correlationId, startingEventId.value(), actorRef);
    }

    /**
     * Defense-in-depth read at expiry (§3.4 step 4): if the predicate is no longer true
     * at expiry (state changed without a {@code state_changed} event, or the store was
     * rebuilt), the trigger still fires — but the divergence is published.
     */
    private void validateAtExpiry(TimerKey key, TriggerDefinition trigger, Ulid subjectUlid,
                                  EventId startingEventId, Ulid correlationId, Ulid actorRef) {
        Boolean stillTrue = predicateHoldsInState(trigger, subjectUlid);
        if (stillTrue != null && !stillTrue) {
            publish(EventTypes.TRIGGER_DURATION_STATE_VALIDATED,
                    new TriggerDurationStateValidatedEvent(key.automationId(), key.triggerIndex(),
                            triggerIdOf(trigger), startingEventId, false),
                    key.automationId(), correlationId, startingEventId.value(), actorRef);
        }
    }

    /**
     * Re-evaluates the trigger predicate against the current state snapshot at expiry.
     * Returns {@code null} when the predicate cannot be checked against state (e.g. the
     * entity is absent, or the trigger has no state predicate).
     */
    private Boolean predicateHoldsInState(TriggerDefinition trigger, Ulid subjectUlid) {
        EntityState state = stateQuery.getSnapshot().states().get(EntityId.of(subjectUlid));
        if (state == null) {
            return null;
        }
        return switch (trigger) {
            case StateTrigger t -> AttributeValues.stringEquals(
                    state.attributes().get(t.attribute()), t.value());
            case NumericThresholdTrigger t -> {
                AttributeValue value = state.attributes().get(t.attribute());
                var numeric = AttributeValues.asDouble(value);
                yield numeric.isPresent() && withinBounds(numeric.getAsDouble(), t.above(), t.below());
            }
            case AvailabilityTrigger t -> state.availability() == t.targetAvailability();
            default -> null; // StateChange is edge-based; Reachability is device-keyed
        };
    }

    // ---- Predicate matching -------------------------------------------------

    private boolean triggerMatchesEvent(TriggerDefinition trigger, EventEnvelope event,
                                        AutomationId automationId) {
        DomainEvent payload = event.payload();
        return switch (trigger) {
            case StateChangeTrigger t -> matchStateChange(t, event, payload);
            case StateTrigger t -> matchState(t, event, payload);
            case NumericThresholdTrigger t -> matchNumeric(t, event, payload);
            case AvailabilityTrigger t -> matchAvailability(t, event, payload);
            case ReachabilityTrigger t -> matchReachability(t, event, payload);
            case EventTrigger t -> event.eventType().equals(t.eventType());
            case ManualTrigger ignored -> event.subjectRef().type() == SubjectType.AUTOMATION
                    && event.subjectRef().id().equals(automationId.value());
            case CalendarTrigger ignored -> false; // M10 producer
            case WebhookTrigger ignored -> false;  // M10 producer
            case TimeTrigger ignored -> false;     // Tier 2
            case SunTrigger ignored -> false;      // Tier 2
            case PresenceTrigger ignored -> false; // Tier 2
        };
    }

    private boolean matchStateChange(StateChangeTrigger t, EventEnvelope event, DomainEvent payload) {
        if (!(payload instanceof StateChangedEvent sce)
                || event.subjectRef().type() != SubjectType.ENTITY) {
            return false;
        }
        if (!resolver.resolve(t.selector()).contains(EntityId.of(event.subjectRef().id()))
                || !sce.attributeKey().equals(t.attribute())) {
            return false;
        }
        if (t.from() != null && !AttributeValues.stringEquals(sce.oldValue(), t.from())) {
            return false;
        }
        return t.to() == null || AttributeValues.stringEquals(sce.newValue(), t.to());
    }

    private boolean matchState(StateTrigger t, EventEnvelope event, DomainEvent payload) {
        if (!(payload instanceof StateChangedEvent sce)
                || event.subjectRef().type() != SubjectType.ENTITY) {
            return false;
        }
        return resolver.resolve(t.selector()).contains(EntityId.of(event.subjectRef().id()))
                && sce.attributeKey().equals(t.attribute())
                && AttributeValues.stringEquals(sce.newValue(), t.value());
    }

    private boolean matchNumeric(NumericThresholdTrigger t, EventEnvelope event, DomainEvent payload) {
        if (!(payload instanceof StateChangedEvent sce)
                || event.subjectRef().type() != SubjectType.ENTITY) {
            return false;
        }
        if (!resolver.resolve(t.selector()).contains(EntityId.of(event.subjectRef().id()))
                || !sce.attributeKey().equals(t.attribute())) {
            return false;
        }
        var numeric = AttributeValues.asDouble(sce.newValue());
        return numeric.isPresent() && withinBounds(numeric.getAsDouble(), t.above(), t.below());
    }

    private boolean matchAvailability(AvailabilityTrigger t, EventEnvelope event, DomainEvent payload) {
        if (!(payload instanceof AvailabilityChangedEvent ace)
                || event.subjectRef().type() != SubjectType.ENTITY) {
            return false;
        }
        return resolver.resolve(t.selector()).contains(EntityId.of(event.subjectRef().id()))
                && AttributeValues.mapAvailability(ace.newStatus()) == t.targetAvailability();
    }

    private boolean matchReachability(ReachabilityTrigger t, EventEnvelope event, DomainEvent payload) {
        if (!(payload instanceof AvailabilityChangedEvent ace)
                || event.subjectRef().type() != SubjectType.DEVICE) {
            return false;
        }
        return DeviceId.of(event.subjectRef().id()).equals(t.deviceId())
                && AttributeValues.mapAvailability(ace.newStatus()) == t.targetAvailability();
    }

    private boolean relevantToTimer(TriggerDefinition trigger, EventEnvelope event) {
        return switch (trigger) {
            case StateChangeTrigger t -> isStateChangeFor(event, t.attribute());
            case StateTrigger t -> isStateChangeFor(event, t.attribute());
            case NumericThresholdTrigger t -> isStateChangeFor(event, t.attribute());
            case AvailabilityTrigger ignored -> event.payload() instanceof AvailabilityChangedEvent;
            case ReachabilityTrigger ignored -> event.payload() instanceof AvailabilityChangedEvent;
            default -> false;
        };
    }

    private static boolean isStateChangeFor(EventEnvelope event, String attribute) {
        return event.payload() instanceof StateChangedEvent sce
                && sce.attributeKey().equals(attribute);
    }

    private static boolean withinBounds(double value, Double above, Double below) {
        if (above != null && value <= above) {
            return false;
        }
        return below == null || value < below;
    }

    private static Duration forDurationOf(TriggerDefinition trigger) {
        return switch (trigger) {
            case StateChangeTrigger t -> t.forDuration();
            case StateTrigger t -> t.forDuration();
            case NumericThresholdTrigger t -> t.forDuration();
            case AvailabilityTrigger t -> t.forDuration();
            case ReachabilityTrigger t -> t.forDuration();
            default -> null;
        };
    }

    private static String triggerIdOf(TriggerDefinition trigger) {
        return switch (trigger) {
            case StateChangeTrigger t -> t.triggerId();
            case StateTrigger t -> t.triggerId();
            case EventTrigger t -> t.triggerId();
            case AvailabilityTrigger t -> t.triggerId();
            case NumericThresholdTrigger t -> t.triggerId();
            case CalendarTrigger t -> t.triggerId();
            case ReachabilityTrigger t -> t.triggerId();
            case ManualTrigger t -> t.triggerId();
            case WebhookTrigger t -> t.triggerId();
            case TimeTrigger ignored -> "";
            case SunTrigger ignored -> "";
            case PresenceTrigger ignored -> "";
        };
    }

    // ---- Publish helper -----------------------------------------------------

    private void publish(String eventType, DomainEvent payload, AutomationId subject,
                         Ulid correlationId, Ulid causationId, Ulid actorRef) {
        EventDraft draft = new EventDraft(
                eventType,
                1,
                null, // never Instant.now() in the publish path (AMD-92 §2.4)
                SubjectRef.automation(subject),
                EventPriority.DIAGNOSTIC,
                EventOrigin.AUTOMATION,
                payload,
                actorRef,
                null);
        try {
            publisher.publish(draft, CausalContext.chain(correlationId, causationId));
        } catch (SequenceConflictException ex) {
            LOG.error("Failed to publish {} for automation {}: sequence conflict",
                    eventType, subject, ex);
        }
    }

    /** Positional timer key {@code (automationId, triggerIndex)} (Doc 07 §3.4). */
    record TimerKey(AutomationId automationId, int triggerIndex) {
    }

    /**
     * A matched automation plus the indices of the triggers that produced an immediate match for
     * one event (M7.4b run initiation). Carries enough for the run initiator to call
     * {@code RunManager.initiateRun(...)} with a correct {@code matchedTriggers} without
     * re-checking triggers. Package-private — the run initiator lives in this package.
     *
     * @param automationId          the matched automation, never {@code null}
     * @param matchedTriggerIndices the immediate-match trigger indices, unmodifiable, non-empty,
     *                              never {@code null}
     */
    record TriggerMatch(AutomationId automationId, List<Integer> matchedTriggerIndices) {
    }

    /** Mutable per-timer state held under {@link #timerLock}. */
    private static final class ActiveTimer {
        private final String triggerId;
        private final EventId startingEventId;
        private final Ulid subjectUlid;
        private final TriggerDefinition trigger;
        private final Instant expiresAt;
        private final Ulid correlationId;
        private final Ulid actorRef;
        private Thread virtualThread;

        private ActiveTimer(String triggerId, EventId startingEventId, Ulid subjectUlid,
                            TriggerDefinition trigger, Instant expiresAt, Ulid correlationId,
                            Ulid actorRef) {
            this.triggerId = triggerId;
            this.startingEventId = startingEventId;
            this.subjectUlid = subjectUlid;
            this.trigger = trigger;
            this.expiresAt = expiresAt;
            this.correlationId = correlationId;
            this.actorRef = actorRef;
        }
    }
}
