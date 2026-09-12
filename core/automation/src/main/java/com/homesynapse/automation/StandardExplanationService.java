/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.homesynapse.event.AutomationActionCompletedEvent;
import com.homesynapse.event.AutomationActionStartedEvent;
import com.homesynapse.event.AutomationCompletedEvent;
import com.homesynapse.event.AutomationConditionEvaluatedEvent;
import com.homesynapse.event.AutomationDisabledEvent;
import com.homesynapse.event.AutomationTriggeredEvent;
import com.homesynapse.event.CommandConfirmationTimedOutEvent;
import com.homesynapse.event.CommandIssuedEvent;
import com.homesynapse.event.CommandResultEvent;
import com.homesynapse.event.EventEnvelope;
import com.homesynapse.event.EventId;
import com.homesynapse.event.EventPage;
import com.homesynapse.event.EventStore;
import com.homesynapse.event.EventTypes;
import com.homesynapse.event.StateChangedEvent;
import com.homesynapse.event.StateConfirmedEvent;
import com.homesynapse.event.StateReportedEvent;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

/**
 * The standard log-derived {@link ExplanationService} (Doc 16 §3.3): assembles run summaries
 * and causal-chain explanations purely by reading the immutable {@link EventStore}. Writes
 * nothing, persists nothing, mints no event (INV-SA-03 / SP2).
 *
 * <p>Stateless and thread-safe over an injected thread-safe {@link EventStore} and
 * {@link AutomationRegistry}; safe for concurrent reads on per-request virtual threads.</p>
 *
 * <h2>Recovering identity and confirmation purely from the log</h2>
 * <ul>
 *   <li>The run-lifecycle event <em>payloads</em> carry no automation id; the producer
 *       publishes them on {@code SubjectRef.automation(...)}, so {@code automationId} is read
 *       from the event <em>envelope</em> subject.</li>
 *   <li>A per-action outcome is derived by following the command by id (v1.1.2 precedence,
 *       CORE-P1): a {@code state_confirmed} whose payload {@code commandEventId} matches the
 *       {@code command_issued} id ⇒ {@code CONFIRMED}; else the last failure-class
 *       {@code command_result} caused by the command ⇒ {@code FAILED} (failure-class = outcome
 *       outside {acknowledged, superseded, unconfirmed} — SD-7); else a {@code command_result}
 *       with outcome {@code "unconfirmed"} ⇒ {@code UNCONFIRMED} with the recorded reason
 *       verbatim; else a {@code command_confirmation_timed_out} ⇒ {@code UNCONFIRMED}; otherwise
 *       {@code DISPATCHED} (a superseded- or acknowledged-only result lands here — supersession
 *       is an intent change, not a failure). Every {@code ActionView} additionally carries the
 *       raw last {@code command_result.outcome} as {@code resultOutcome} and the derived
 *       {@code settled} flag. No device registry is consulted — a confirmation-disabled command
 *       is simply never confirmed in the log, so it renders {@code DISPATCHED}, never a false
 *       {@code CONFIRMED} (the honest-confirmation guarantee, DP-A2). Keeping the outcome a pure
 *       function of the log preserves INV-SA-03 determinism (a mutable registry read at read
 *       time would not be replay-safe).</li>
 *   <li>Since v1.1.4 (EXPLAIN-114a) the projection also serves, from what the correlation
 *       already holds and never by guess: the trigger's {@code firingValue} (the triggering
 *       {@code state_changed}'s {@code newValue} in the module's one string dialect, or the
 *       {@code state_reported} value; {@code null} for any other payload or an absent
 *       envelope), each action's {@code settledAt} / {@code confirmedAt} (the CLASSIFYING
 *       envelope's instant — never the command's), the run's {@code definitionKey}
 *       ({@code automation_triggered.definitionHash}); and on the non-firing read the
 *       {@code DISABLED} facts ({@code disabledAt} / {@code disabledReason} from the latest
 *       {@code automation_disabled}, else the literal {@code "configuration"}) and the
 *       registry definition's {@code definitionKey} via {@link DefinitionHashes} — the same
 *       function the engine stamps, so the key agrees across the three reads.</li>
 * </ul>
 *
 * <h2>Ordering and cost</h2>
 * The {@link EventStore} pages forward (ascending {@code globalPosition}) only. {@code listRuns}
 * therefore scans the type-indexed {@code automation_completed} stream and keeps the newest
 * page in an {@code O(limit)} sliding window, reversing for newest-first; {@code explainRun}
 * locates the run's {@code automation_triggered} by a bounded type scan, then reads the bounded
 * correlation. Cost is {@code O(retained-runs-of-that-type)} (the type index, not the whole
 * log); a future backward-read store primitive would make it strictly {@code O(page)}.
 */
final class StandardExplanationService implements ExplanationService {

    private static final Logger LOG = LoggerFactory.getLogger(StandardExplanationService.class);

    /** Page size for the forward type scans (kept modest to bound per-read memory). */
    private static final int SCAN_BATCH = 500;

    /** {@code command_result} outcome that is a protocol ack, not a terminal failure. */
    private static final String OUTCOME_ACKNOWLEDGED = "acknowledged";

    /** {@code command_result} disposition for a command replaced by a newer one — an intent change, never a failure (CORE-P1(b)). */
    private static final String OUTCOME_SUPERSEDED = "superseded";

    /** {@code command_result} disposition for an ACK followed by silence — zigbee's honest-unconfirmed (CORE-P1(c)). */
    private static final String OUTCOME_UNCONFIRMED = "unconfirmed";

    /**
     * The non-failure {@code command_result} outcomes (SD-7, v1.1.2). Any outcome outside this
     * set — including unknown adapter-specific strings — classifies failure-class, keeping the
     * conservative FAILED semantics for everything not explicitly ruled otherwise.
     */
    private static final Set<String> NON_FAILURE_OUTCOMES =
            Set.of(OUTCOME_ACKNOWLEDGED, OUTCOME_SUPERSEDED, OUTCOME_UNCONFIRMED);

    /** {@code automation_action_completed} outcomes for a non-dispatched command action. */
    private static final String ACTION_SKIPPED = "skipped";
    private static final String ACTION_ERROR = "error";

    /**
     * The v1.1.4 {@code disabledReason} for an automation whose definition is disabled while the
     * log holds no {@code automation_disabled} for it (DP-6): the configuration turned it off.
     */
    private static final String DISABLED_BY_CONFIGURATION = "configuration";

    private final EventStore eventStore;
    private final AutomationRegistry automationRegistry;

    StandardExplanationService(EventStore eventStore, AutomationRegistry automationRegistry) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.automationRegistry = Objects.requireNonNull(automationRegistry, "automationRegistry");
    }

    @Override
    public RunPage listRuns(Optional<AutomationId> automationId, long beforePosition, int limit) {
        Objects.requireNonNull(automationId, "automationId");
        int cap = Math.max(1, limit);
        long before = beforePosition <= 0 ? Long.MAX_VALUE : beforePosition;

        // Forward scan of the type-indexed terminal markers; keep the newest (cap + 1) below
        // the cursor in a sliding window (ascending). The extra one detects hasMore.
        Deque<EventEnvelope> window = new ArrayDeque<>(cap + 1);
        long after = 0;
        while (true) {
            EventPage page = eventStore.readByType(EventTypes.AUTOMATION_COMPLETED, after, SCAN_BATCH);
            for (EventEnvelope e : page.events()) {
                if (e.globalPosition() >= before) {
                    continue;
                }
                if (automationId.isPresent() && !automationOf(e).equals(automationId.get())) {
                    continue;
                }
                window.addLast(e);
                if (window.size() > cap + 1) {
                    window.removeFirst();
                }
            }
            if (!page.hasMore()) {
                break;
            }
            after = page.nextPosition();
        }

        List<EventEnvelope> ascending = new ArrayList<>(window);
        boolean hasMore = ascending.size() > cap;
        if (hasMore) {
            ascending = ascending.subList(ascending.size() - cap, ascending.size());
        }
        long nextCursorPosition = ascending.isEmpty() ? 0 : ascending.get(0).globalPosition();

        List<EventEnvelope> newestFirst = new ArrayList<>(ascending);
        Collections.reverse(newestFirst);

        List<RunSummary> summaries = new ArrayList<>(newestFirst.size());
        for (EventEnvelope e : newestFirst) {
            RunSummary summary = toSummary(e);
            if (summary != null) {
                summaries.add(summary);
            }
        }
        return new RunPage(summaries, nextCursorPosition, hasMore);
    }

    @Override
    public Optional<RunExplanation> explainRun(RunId runId) {
        Objects.requireNonNull(runId, "runId");

        EventEnvelope triggered = locateTriggered(runId);
        if (triggered == null) {
            return Optional.empty();
        }
        AutomationTriggeredEvent triggerPayload = (AutomationTriggeredEvent) triggered.payload();

        Ulid correlationId = triggered.causalContext().correlationId();
        List<EventEnvelope> chain = eventStore.readByCorrelation(correlationId);

        EventEnvelope completed = firstForRun(chain, EventTypes.AUTOMATION_COMPLETED, runId);
        if (completed == null) {
            // Triggered but not terminal: in-flight runs are out of V1 scope.
            return Optional.empty();
        }
        AutomationCompletedEvent completedPayload = (AutomationCompletedEvent) completed.payload();
        RunStatus status = parseStatus(completedPayload.finalStatus());
        if (status == null) {
            LOG.warn("Skipping run {} with unrecognized finalStatus '{}'",
                    runId, completedPayload.finalStatus());
            return Optional.empty();
        }

        AutomationId automationId = automationOf(triggered);
        Optional<AutomationDefinition> definition = automationRegistry.get(automationId);
        String automationName = definition.map(AutomationDefinition::name).orElse(null);

        RunExplanation.TriggerView trigger = buildTrigger(triggered, triggerPayload, chain, definition);
        List<RunExplanation.ConditionView> conditions = buildConditions(chain, runId);
        List<RunExplanation.ActionView> actions = buildActions(chain, runId);
        RunExplanation.OutcomeView outcome = new RunExplanation.OutcomeView(
                status, firstNonBlank(completedPayload.failureReason(), completedPayload.abortReason()),
                completedPayload.durationMs(), completedPayload.actionCount(),
                completedPayload.commandCount());
        RunExplanation.CascadeView cascade =
                new RunExplanation.CascadeView(null, triggerPayload.cascadeDepth());

        // v1.1.4: the stable definition key is the hash the engine stamped on this run.
        return Optional.of(new RunExplanation(runId, automationId, automationName,
                trigger, conditions, actions, outcome, cascade, triggerPayload.definitionHash()));
    }

    // ---- non-firing projection (M7.5b) --------------------------------------

    @Override
    public Optional<NonFiringExplanation> explainNonFiring(AutomationId automationId,
                                                           long expectedSincePosition) {
        Objects.requireNonNull(automationId, "automationId");

        Optional<AutomationDefinition> maybe = automationRegistry.get(automationId);
        if (maybe.isEmpty()) {
            // Unknown automation → empty → 404 at the boundary.
            return Optional.empty();
        }
        AutomationDefinition definition = maybe.get();
        String automationName = definition.name();
        String triggerSummary = triggerSummary(definition.triggers());
        // v1.1.3 (CG-1): the FIRST trigger's single-entity ref (DP-2), derived ONCE beside the
        // summary and threaded into every construction below; null when the definition has no
        // trigger or its first trigger names no single entity (never a fabricated id).
        RunExplanation.SubjectRefView triggerRef =
                definition.triggers().isEmpty() ? null : refOf(definition.triggers().get(0));
        // v1.1.4 (DP-5): the stable definition key over the registry's current definition — the
        // SAME function and input the engine hashes into automation_triggered.definitionHash
        // (StandardRunManager.initiateRun → RunContext.definitionHash), so it equals the causal
        // chain's key for a run of this definition. No store read.
        String definitionKey = DefinitionHashes.forDefinition(definition);

        // DISABLED short-circuits: a disabled automation's non-firing reason is that it is off,
        // regardless of any run history (DP-B2 step 3).
        if (!definition.enabled()) {
            // v1.1.4: the disable facts come from the latest automation_disabled the log holds
            // for this automation (the failure governor's marker); absent that, the definition
            // itself is what turned it off (DP-6: the literal "configuration").
            EventEnvelope disabled = latestDisabled(automationId);
            Instant disabledAt = disabled == null ? null : instantOf(disabled);
            String disabledReason = disabled == null
                    ? DISABLED_BY_CONFIGURATION
                    : ((AutomationDisabledEvent) disabled.payload()).reason();
            return Optional.of(new NonFiringExplanation(automationId, automationName, false,
                    NonFiringExplanation.NonFiringVerdict.DISABLED, null,
                    "Automation '" + automationName + "' is currently disabled.",
                    triggerSummary, null, null, triggerRef, disabledAt, disabledReason,
                    definitionKey));
        }

        long sinceInclusive = Math.max(0L, expectedSincePosition);
        EventEnvelope latest = latestTerminalRun(automationId, sinceInclusive);
        if (latest == null) {
            String windowNote = sinceInclusive > 0 ? " in the requested window" : "";
            return Optional.of(new NonFiringExplanation(automationId, automationName, true,
                    NonFiringExplanation.NonFiringVerdict.NEVER_TRIGGERED, null,
                    "Automation '" + automationName + "' has not been triggered" + windowNote
                            + "; it fires on " + triggerSummary + ".",
                    triggerSummary, null, null, triggerRef, null, null, definitionKey));
        }

        AutomationCompletedEvent payload = (AutomationCompletedEvent) latest.payload();
        // latestTerminalRun only returns runs whose finalStatus parses, so status is non-null.
        RunStatus status = parseStatus(payload.finalStatus());
        RunId runId = new RunId(payload.runId());
        Instant evaluatedAt = derivedTriggerInstant(latest, payload.durationMs());

        return Optional.of(deriveNonFiring(automationId, automationName, triggerSummary,
                triggerRef, definitionKey, latest, status, runId, evaluatedAt));
    }

    /**
     * Maps the most-recent in-window terminal run to a verdict. Exhaustive over {@link RunStatus}
     * with no {@code default}, so a future status is a compile error here, not a silent miswire.
     * Every construction is the canonical thirteen-component form carrying the derived
     * {@code triggerRef} (v1.1.3) and {@code definitionKey} (v1.1.4); the disable facts are
     * {@code null} on every non-{@code DISABLED} verdict.
     */
    private NonFiringExplanation deriveNonFiring(AutomationId automationId, String automationName,
                                                 String triggerSummary,
                                                 RunExplanation.SubjectRefView triggerRef,
                                                 String definitionKey,
                                                 EventEnvelope completed, RunStatus status,
                                                 RunId runId, Instant evaluatedAt) {
        return switch (status) {
            case CONDITION_NOT_MET -> new NonFiringExplanation(automationId, automationName, true,
                    NonFiringExplanation.NonFiringVerdict.CONDITION_NOT_MET, runId,
                    "Automation '" + automationName
                            + "' was triggered, but its conditions were not met, so no actions ran.",
                    triggerSummary,
                    new NonFiringExplanation.LastEvaluationView(evaluatedAt, "false"),
                    null, triggerRef, null, null, definitionKey);
            case COMPLETED -> completedVerdict(automationId, automationName, triggerSummary,
                    triggerRef, definitionKey, completed, runId, evaluatedAt);
            case FAILED, ABORTED, INTERRUPTED -> new NonFiringExplanation(automationId,
                    automationName, true,
                    NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED, runId,
                    "Automation '" + automationName + "' fired, but the run ended in state "
                            + status.name().toLowerCase(Locale.ROOT)
                            + " without a confirmed result.",
                    triggerSummary,
                    new NonFiringExplanation.LastEvaluationView(evaluatedAt, null),
                    null, triggerRef, null, null, definitionKey);
            case EVALUATING, RUNNING -> {
                // A non-terminal status on a terminal marker is a producer anomaly. Report it
                // honestly as "ran, outcome not confirmed" rather than fabricate a clean success.
                LOG.warn("Run {} carries non-terminal finalStatus '{}' on a terminal marker; "
                        + "reporting ACTED_BUT_UNCONFIRMED", runId, status);
                yield new NonFiringExplanation(automationId, automationName, true,
                        NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED, runId,
                        "Automation '" + automationName
                                + "' fired recently; its outcome is not yet confirmed.",
                        triggerSummary,
                        new NonFiringExplanation.LastEvaluationView(evaluatedAt, null),
                        null, triggerRef, null, null, definitionKey);
            }
        };
    }

    /**
     * A {@code COMPLETED} run that issued <em>zero device commands</em> while defining actions
     * ({@code commandCount == 0 && actionCount > 0} on the terminal payload) is
     * {@code ACTED_BUT_UNCONFIRMED} with the v1.1.2 {@code noCommandsIssued} marker (CORE-P2):
     * the Doc 07 §3.9 per-target skip emits nothing, so the payload arithmetic is the ONLY
     * log-visible disclosure of a do-nothing run, and the clean-success sentence must be
     * unreachable for it (a do-nothing run asserting confirmation with zero confirmable commands
     * was the defect). Otherwise a {@code COMPLETED} run is {@code ACTED_BUT_UNCONFIRMED} when any
     * of its device actions did not confirm (outcome {@code UNCONFIRMED}/{@code FAILED}); else it
     * is a clean confirmed success, reported since v1.1.4 (EXPLAIN-114a, the growth path
     * <strong>DP-B2</strong> named) as {@code FIRED_CONFIRMED} with that run's
     * {@code lastRelevantRunId} and the unchanged "last fired and confirmed at" sentence — until
     * v1.1.3 the frozen 4-value verdict had no "fired fine" value and this case borrowed
     * {@code NEVER_TRIGGERED} with a non-null run id. The action-outcome check reuses
     * {@link #buildActions} (the M7.5a honest-confirmation derivation) — no duplication.
     */
    private NonFiringExplanation completedVerdict(AutomationId automationId, String automationName,
                                                  String triggerSummary,
                                                  RunExplanation.SubjectRefView triggerRef,
                                                  String definitionKey,
                                                  EventEnvelope completed, RunId runId,
                                                  Instant evaluatedAt) {
        AutomationCompletedEvent payload = (AutomationCompletedEvent) completed.payload();
        if (payload.commandCount() == 0 && payload.actionCount() > 0) {
            return new NonFiringExplanation(automationId, automationName, true,
                    NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED, runId,
                    "Automation '" + automationName + "' fired, but issued no device commands — "
                            + "its device actions were skipped or issued nothing (targets "
                            + "unavailable or no device actions defined).",
                    triggerSummary,
                    new NonFiringExplanation.LastEvaluationView(evaluatedAt, "true"),
                    Boolean.TRUE, triggerRef, null, null, definitionKey);
        }
        List<EventEnvelope> chain =
                eventStore.readByCorrelation(completed.causalContext().correlationId());
        List<RunExplanation.ActionView> actions = buildActions(chain, runId);
        boolean unconfirmedOrFailed = actions.stream().anyMatch(a ->
                a.outcome() == RunExplanation.ActionOutcome.UNCONFIRMED
                        || a.outcome() == RunExplanation.ActionOutcome.FAILED);
        if (unconfirmedOrFailed) {
            return new NonFiringExplanation(automationId, automationName, true,
                    NonFiringExplanation.NonFiringVerdict.ACTED_BUT_UNCONFIRMED, runId,
                    "Automation '" + automationName
                            + "' fired, but a device did not confirm the requested change.",
                    triggerSummary,
                    new NonFiringExplanation.LastEvaluationView(evaluatedAt, "true"),
                    null, triggerRef, null, null, definitionKey);
        }
        return new NonFiringExplanation(automationId, automationName, true,
                NonFiringExplanation.NonFiringVerdict.FIRED_CONFIRMED, runId,
                "Automation '" + automationName + "' last fired and confirmed at "
                        + evaluatedAt + "; no non-firing was detected in the requested window.",
                triggerSummary,
                new NonFiringExplanation.LastEvaluationView(evaluatedAt, "true"),
                null, triggerRef, null, null, definitionKey);
    }

    @Override
    public List<AutomationSummary> listAutomations() {
        Map<AutomationId, RunId> lastRuns = latestRunByAutomation();
        List<AutomationDefinition> definitions = automationRegistry.getAll();
        List<AutomationSummary> summaries = new ArrayList<>(definitions.size());
        for (AutomationDefinition definition : definitions) {
            summaries.add(new AutomationSummary(definition.automationId(), definition.name(),
                    definition.enabled(), componentsOf(definition),
                    lastRuns.get(definition.automationId()),
                    DefinitionHashes.forDefinition(definition)));
        }
        return summaries;
    }

    // ---- non-firing / list helpers (M7.5b) ----------------------------------

    /**
     * The most-recent terminal run for one automation at or after the inclusive lower-bound global
     * position {@code sinceInclusive}, or {@code null} if none. Reuses {@link #automationOf} and
     * {@link #parseStatus} over the same forward type-scan idiom as {@code listRuns}, but is a
     * distinct scan: the bound is a <em>lower</em> bound (the "expected since" window) and the
     * caller needs the full envelope (to read the run's correlation for the action-outcome check).
     * Runs with an unrecognized status are logged and skipped, matching {@code toSummary}.
     */
    private EventEnvelope latestTerminalRun(AutomationId automationId, long sinceInclusive) {
        EventEnvelope newest = null;
        long after = 0;
        while (true) {
            EventPage page =
                    eventStore.readByType(EventTypes.AUTOMATION_COMPLETED, after, SCAN_BATCH);
            for (EventEnvelope e : page.events()) {
                if (e.globalPosition() < sinceInclusive) {
                    continue;
                }
                if (!(e.payload() instanceof AutomationCompletedEvent p)) {
                    continue;
                }
                if (!automationOf(e).equals(automationId)) {
                    continue;
                }
                if (parseStatus(p.finalStatus()) == null) {
                    LOG.warn("Skipping run {} with unrecognized finalStatus '{}'",
                            p.runId(), p.finalStatus());
                    continue;
                }
                newest = e; // ascending scan — the last match seen is the newest
            }
            if (!page.hasMore()) {
                break;
            }
            after = page.nextPosition();
        }
        return newest;
    }

    /**
     * The latest {@code automation_disabled} the log holds for one automation, or {@code null}
     * (v1.1.4). One forward pass over that type's index — the same paging idiom as
     * {@link #latestTerminalRun} (ascending scan ⇒ the last match is the newest) — matched on
     * the payload's own {@code automationId}. Not window-bounded: the disabling event precedes
     * any "expected since" window by definition, and the type is rare (one event per
     * auto-disable), so the walk is {@code O(retained auto-disables)}, never {@code O(log)}.
     * Read only on the {@code DISABLED} verdict, so an enabled automation pays nothing.
     */
    private EventEnvelope latestDisabled(AutomationId automationId) {
        EventEnvelope newest = null;
        long after = 0;
        while (true) {
            EventPage page =
                    eventStore.readByType(EventTypes.AUTOMATION_DISABLED, after, SCAN_BATCH);
            for (EventEnvelope e : page.events()) {
                if (e.payload() instanceof AutomationDisabledEvent p
                        && p.automationId().equals(automationId)) {
                    newest = e; // ascending scan — the last match seen is the newest
                }
            }
            if (!page.hasMore()) {
                break;
            }
            after = page.nextPosition();
        }
        return newest;
    }

    /**
     * One forward pass over the {@code automation_completed} stream recording the newest terminal
     * run id per automation (ascending scan ⇒ last write wins). {@code O(retained terminal runs)}
     * once, not {@code O(automations × log)} — the bounded best-effort {@code lastRunId} source for
     * {@link #listAutomations()}.
     */
    private Map<AutomationId, RunId> latestRunByAutomation() {
        Map<AutomationId, RunId> latest = new HashMap<>();
        long after = 0;
        while (true) {
            EventPage page =
                    eventStore.readByType(EventTypes.AUTOMATION_COMPLETED, after, SCAN_BATCH);
            for (EventEnvelope e : page.events()) {
                if (e.payload() instanceof AutomationCompletedEvent p) {
                    latest.put(automationOf(e), new RunId(p.runId()));
                }
            }
            if (!page.hasMore()) {
                break;
            }
            after = page.nextPosition();
        }
        return latest;
    }

    /**
     * The run's trigger instant, derived from its terminal envelope (v1.1.2, both DP-3 sites).
     * Under the ruled DP-G inheritance the terminal envelope's {@code eventTime} IS the
     * triggering event's instant, so when present it is returned with NO arithmetic (the old
     * unconditional {@code − durationMs} understated the trigger time by exactly the run's
     * duration). Only the {@code eventTime}-absent fallback subtracts the recorded duration from
     * {@code ingestTime} (best-effort from wall-time), clamped non-negative. Do not "simplify"
     * the two branches back into one subtraction.
     */
    private static Instant derivedTriggerInstant(EventEnvelope terminal, long durationMs) {
        return terminal.eventTime() != null
                ? terminal.eventTime()
                : terminal.ingestTime().minusMillis(Math.max(0L, durationMs));
    }

    private static List<AutomationSummary.ComponentView> componentsOf(AutomationDefinition def) {
        List<AutomationSummary.ComponentView> components = new ArrayList<>(
                def.triggers().size() + def.conditions().size() + def.actions().size());
        for (TriggerDefinition t : def.triggers()) {
            components.add(componentView(t.getClass().getSimpleName(), refOf(t)));
        }
        for (ConditionDefinition c : def.conditions()) {
            components.add(componentView(c.getClass().getSimpleName(), refOf(c)));
        }
        for (ActionDefinition a : def.actions()) {
            components.add(componentView(a.getClass().getSimpleName(), refOf(a)));
        }
        return components;
    }

    private static AutomationSummary.ComponentView componentView(String simpleName,
                                                                 RunExplanation.SubjectRefView ref) {
        return new AutomationSummary.ComponentView(simpleName, humanize(simpleName), ref);
    }

    // ---- the v1.1.3 ref rule (CG-1, DP-2) -----------------------------------

    /**
     * The single-entity reference of one trigger (v1.1.3, DP-2): the {@code {type:"entity", id}}
     * view of the ONE entity the trigger addresses by identity, or {@code null}. A trigger
     * contributes a ref <em>iff</em> it addresses exactly one entity by identity — a
     * {@link Selector} that is a {@link DirectRefSelector} (the {@link #selectorRef} rule), or a
     * {@link CalendarTrigger}'s {@code calendarEntityId}. Every other permit yields {@code null}:
     * the subject-less triggers ({@link EventTrigger}, {@link ManualTrigger},
     * {@link WebhookTrigger}; the Tier-2 reserved {@link TimeTrigger}, {@link SunTrigger},
     * {@link PresenceTrigger}) name no entity, and a {@link ReachabilityTrigger} addresses a
     * DEVICE — the frozen read API has no device read for a consumer to census a
     * {@code {type:"device"}} ref against, so it is {@code null} here (a stated limitation; a
     * device-typed ref is a candidate for a later additive bump, not this one). Exhaustive over
     * the sealed hierarchy with no {@code default}, so a new permit is a compile error here, not a
     * silent {@code null}.
     */
    private static RunExplanation.SubjectRefView refOf(TriggerDefinition trigger) {
        return switch (trigger) {
            case StateChangeTrigger t -> selectorRef(t.selector());
            case StateTrigger t -> selectorRef(t.selector());
            case NumericThresholdTrigger t -> selectorRef(t.selector());
            case AvailabilityTrigger t -> selectorRef(t.selector());
            case CalendarTrigger t -> entityRef(t.calendarEntityId());
            case ReachabilityTrigger t -> null; // a DEVICE subject: no entity census target
            case EventTrigger t -> null;
            case ManualTrigger t -> null;
            case WebhookTrigger t -> null;
            case TimeTrigger t -> null;
            case SunTrigger t -> null;
            case PresenceTrigger t -> null;
        };
    }

    /**
     * The single-entity reference of one condition (v1.1.3, DP-2): the {@link #selectorRef} rule
     * for the selector-bearing permits ({@link StateCondition}, {@link NumericCondition});
     * {@code null} for {@link TimeCondition} and the Tier-2 {@link ZoneCondition} (no selector),
     * and for the compound {@link AndCondition}/{@link OrCondition}/{@link NotCondition}, which
     * are never descended — a compound names a combination, not an entity. Exhaustive, no
     * {@code default}.
     */
    private static RunExplanation.SubjectRefView refOf(ConditionDefinition condition) {
        return switch (condition) {
            case StateCondition c -> selectorRef(c.selector());
            case NumericCondition c -> selectorRef(c.selector());
            case TimeCondition c -> null;
            case AndCondition c -> null; // compound: never descended
            case OrCondition c -> null;
            case NotCondition c -> null;
            case ZoneCondition c -> null;
        };
    }

    /**
     * The single-entity reference of one action (v1.1.3, DP-2): the {@link #selectorRef} rule for
     * a {@link CommandAction}'s {@code target}; {@code null} for every other permit —
     * {@link DelayAction}, {@link EmitEventAction} and the Tier-2 reserved actions carry no
     * selector, and {@link WaitForAction}/{@link ConditionBranchAction} carry a condition that is
     * never descended. Exhaustive, no {@code default}.
     */
    private static RunExplanation.SubjectRefView refOf(ActionDefinition action) {
        return switch (action) {
            case CommandAction a -> selectorRef(a.target());
            case DelayAction a -> null;
            case WaitForAction a -> null; // carries a condition, not a selector: never descended
            case ConditionBranchAction a -> null;
            case EmitEventAction a -> null;
            case ActivateSceneAction a -> null;
            case InvokeIntegrationAction a -> null;
            case ParallelAction a -> null;
        };
    }

    /**
     * The selector rule (DP-2 / D5): only a {@link DirectRefSelector} addresses exactly one entity
     * by identity, so only it yields a ref. Every group-resolving permit ({@link SlugSelector},
     * {@link AreaSelector}, {@link LabelSelector}, {@link TypeSelector},
     * {@link SemanticTagSelector}, {@link CompoundSelector}) names a SET resolved at trigger time
     * — a ref would be a fabrication — and yields {@code null}; a {@code CompoundSelector} is not
     * descended even when it wraps a single {@code DirectRefSelector} (the rule as written).
     * Exhaustive, no {@code default}.
     */
    private static RunExplanation.SubjectRefView selectorRef(Selector selector) {
        return switch (selector) {
            case DirectRefSelector s -> entityRef(s.entityId());
            case SlugSelector s -> null;
            case AreaSelector s -> null;
            case LabelSelector s -> null;
            case TypeSelector s -> null;
            case SemanticTagSelector s -> null;
            case CompoundSelector s -> null; // a set, never descended (see the javadoc)
        };
    }

    /** The {@code {type:"entity", id}} view of one entity — the causal chain's own rendering. */
    private static RunExplanation.SubjectRefView entityRef(EntityId entityId) {
        return subjectRefView("entity", entityId.toString());
    }

    /**
     * Renders a plain-words trigger summary from the definition's trigger kinds — the
     * suffix-stripped, humanized concrete-record names joined readably (e.g. "state change" or
     * "state change or numeric threshold"). The sealed {@link TriggerDefinition} has no common
     * accessor and entity refs are ULIDs (not names), so a richer rendering is not derivable here;
     * this is deterministic and safe across all twelve permits and any future one.
     */
    private static String triggerSummary(List<TriggerDefinition> triggers) {
        if (triggers.isEmpty()) {
            return "a configured trigger"; // defensive — triggers are guaranteed non-empty
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < triggers.size(); i++) {
            if (i > 0) {
                sb.append(i == triggers.size() - 1 ? " or " : ", ");
            }
            sb.append(humanize(stripTrailing(triggers.get(i).getClass().getSimpleName(), "Trigger")));
        }
        return sb.toString();
    }

    /** Humanizes a record simple name to lower-cased, space-separated words. Deterministic. */
    private static String humanize(String simpleName) {
        StringBuilder sb = new StringBuilder(simpleName.length() + 4);
        for (int i = 0; i < simpleName.length(); i++) {
            char ch = simpleName.charAt(i);
            if (i > 0 && Character.isUpperCase(ch)) {
                sb.append(' ');
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    private static String stripTrailing(String value, String suffix) {
        return value.endsWith(suffix) && value.length() > suffix.length()
                ? value.substring(0, value.length() - suffix.length())
                : value;
    }

    // ---- run summary --------------------------------------------------------

    private RunSummary toSummary(EventEnvelope completed) {
        AutomationCompletedEvent payload = (AutomationCompletedEvent) completed.payload();
        RunStatus status = parseStatus(payload.finalStatus());
        if (status == null) {
            LOG.warn("Skipping run {} with unrecognized finalStatus '{}'",
                    payload.runId(), payload.finalStatus());
            return null;
        }
        AutomationId automationId = automationOf(completed);
        String automationName =
                automationRegistry.get(automationId).map(AutomationDefinition::name).orElse(null);
        Instant triggeredAt = derivedTriggerInstant(completed, payload.durationMs());
        return new RunSummary(new RunId(payload.runId()), automationId, automationName,
                triggeredAt, status, firstNonBlank(payload.failureReason(), payload.abortReason()));
    }

    // ---- trigger ------------------------------------------------------------

    private RunExplanation.TriggerView buildTrigger(EventEnvelope triggered,
                                                    AutomationTriggeredEvent payload,
                                                    List<EventEnvelope> chain,
                                                    Optional<AutomationDefinition> definition) {
        String type = definition
                .filter(d -> !d.triggers().isEmpty())
                .map(d -> d.triggers().get(0).getClass().getSimpleName())
                .orElse(null);
        // The triggering envelope, located by id inside the run's correlation. An engine run
        // inherits that envelope's correlation (StandardRunManager.initiateRun), so it is here
        // for every run whose triggering event is still retained; a retention-trimmed one is
        // simply absent, and every fact read from it stays null (the v1.1.4 honesty law).
        EventEnvelope triggering = chain.stream()
                .filter(e -> e.eventId().equals(payload.triggeringEventId()))
                .findFirst()
                .orElse(null);
        RunExplanation.SubjectRefView subjectRef = triggering == null
                ? null
                : subjectRefView(triggering.subjectRef().type().name().toLowerCase(),
                        triggering.subjectRef().id().toString());
        Instant matchedAt = instantOf(triggered);
        return new RunExplanation.TriggerView(type, subjectRef, matchedAt,
                firingValueOf(triggering));
    }

    /**
     * The value the triggering event carried (v1.1.4, EXPLAIN-114a): a {@code state_changed}'s
     * {@code newValue} in the module's one string dialect ({@link AttributeValues#asString} —
     * the rendering the chain's {@code observedState[].value} and the ledger's
     * {@code state_confirmed} values already use; never a record {@code toString()}); a
     * {@code state_reported}'s {@code value} as recorded; {@code null} for any other payload or
     * when the triggering envelope is not in the correlation. A fact the log does not carry is
     * never guessed.
     */
    private static String firingValueOf(EventEnvelope triggering) {
        if (triggering == null) {
            return null;
        }
        return switch (triggering.payload()) {
            case StateChangedEvent p -> AttributeValues.asString(p.newValue());
            case StateReportedEvent p -> p.value();
            default -> null;
        };
    }

    // ---- conditions ---------------------------------------------------------

    private List<RunExplanation.ConditionView> buildConditions(List<EventEnvelope> chain,
                                                               RunId runId) {
        List<RunExplanation.ConditionView> conditions = new ArrayList<>();
        for (EventEnvelope e : chain) {
            if (!EventTypes.AUTOMATION_CONDITION_EVALUATED.equals(e.eventType())) {
                continue;
            }
            if (!(e.payload() instanceof AutomationConditionEvaluatedEvent p)
                    || !p.runId().equals(runId.value())) {
                continue;
            }
            List<RunExplanation.ObservedStateEntry> observed = new ArrayList<>();
            for (AutomationConditionEvaluatedEvent.EvaluatedEntityState es : p.evaluatedState()) {
                observed.add(new RunExplanation.ObservedStateEntry(
                        es.entityRef().toString(), es.attribute(), es.value()));
            }
            // expression is rendered from conditionType (the YAML text is not on the event);
            // evaluated is true by construction (the event exists only for an evaluated condition).
            conditions.add(new RunExplanation.ConditionView(
                    p.conditionType(), true, p.result(), observed));
        }
        return conditions;
    }

    // ---- actions ------------------------------------------------------------

    private List<RunExplanation.ActionView> buildActions(List<EventEnvelope> chain, RunId runId) {
        // This run's action_started events, in log order, plus its action_completed ENVELOPES by
        // index (the envelope, not only the payload: its position bounds the command window and
        // its instant is a non-dispatched action's settledAt, v1.1.4).
        List<EventEnvelope> started = new ArrayList<>();
        Map<Integer, EventEnvelope> completedByIndex = new HashMap<>();
        for (EventEnvelope e : chain) {
            if (e.payload() instanceof AutomationActionStartedEvent p && p.runId().equals(runId.value())) {
                started.add(e);
            } else if (e.payload() instanceof AutomationActionCompletedEvent p
                    && p.runId().equals(runId.value())) {
                completedByIndex.put(p.actionIndex(), e);
            }
        }

        // command_issued events in this correlation (no runId on the payload — attributed to an
        // action by position window + target membership below).
        List<EventEnvelope> commands = new ArrayList<>();
        for (EventEnvelope e : chain) {
            if (e.payload() instanceof CommandIssuedEvent) {
                commands.add(e);
            }
        }

        List<RunExplanation.ActionView> actions = new ArrayList<>();
        for (EventEnvelope startEnv : started) {
            AutomationActionStartedEvent sp = (AutomationActionStartedEvent) startEnv.payload();
            long startPos = startEnv.globalPosition();
            EventEnvelope completedEnv = completedByIndex.get(sp.actionIndex());
            long endPos = completedEnv == null ? Long.MAX_VALUE : completedEnv.globalPosition();

            List<EventEnvelope> actionCommands = new ArrayList<>();
            for (EventEnvelope c : commands) {
                if (c.globalPosition() <= startPos || c.globalPosition() > endPos) {
                    continue;
                }
                EntityId target = EntityId.of(((CommandIssuedEvent) c.payload()).targetEntityRef());
                if (sp.targetRefs().contains(target)) {
                    actionCommands.add(c);
                }
            }

            if (!actionCommands.isEmpty()) {
                for (EventEnvelope c : actionCommands) {
                    actions.add(commandActionView(sp.actionType(), c, chain));
                }
            } else {
                RunExplanation.ActionView view = nonDispatchedActionView(sp, completedEnv);
                if (view != null) {
                    actions.add(view);
                }
            }
        }
        return actions;
    }

    private RunExplanation.ActionView commandActionView(String actionType, EventEnvelope commandEnv,
                                                        List<EventEnvelope> chain) {
        CommandIssuedEvent ci = (CommandIssuedEvent) commandEnv.payload();
        RunExplanation.SubjectRefView targetRef =
                subjectRefView("entity", ci.targetEntityRef().toString());
        Outcome outcome = deriveOutcome(commandEnv.eventId(), chain);
        return new RunExplanation.ActionView(actionType, targetRef, ci.commandType(),
                ci.parameters(), outcome.value(), outcome.reason(), outcome.resultOutcome(),
                outcome.settled(), outcome.settledAt(), outcome.confirmedAt());
    }

    /**
     * Surfaces a command action that issued no command: a {@code "skipped"} completion as
     * {@code SKIPPED}, an {@code "error"} completion as {@code FAILED}. Successful non-command
     * actions (delay/wait/branch/emit) are omitted — they are not device commands and have no
     * place in the confirmation-centric outcome vocabulary. The completion envelope is the
     * classifying event, so its instant is the view's {@code settledAt} (v1.1.4); nothing
     * confirmed it, so {@code confirmedAt} is {@code null}.
     */
    private RunExplanation.ActionView nonDispatchedActionView(AutomationActionStartedEvent sp,
                                                              EventEnvelope completedEnv) {
        if (completedEnv == null) {
            return null;
        }
        AutomationActionCompletedEvent cp = (AutomationActionCompletedEvent) completedEnv.payload();
        RunExplanation.ActionOutcome outcome;
        if (ACTION_SKIPPED.equals(cp.outcome())) {
            outcome = RunExplanation.ActionOutcome.SKIPPED;
        } else if (ACTION_ERROR.equals(cp.outcome())) {
            outcome = RunExplanation.ActionOutcome.FAILED;
        } else {
            return null;
        }
        RunExplanation.SubjectRefView targetRef = sp.targetRefs().isEmpty()
                ? null
                : subjectRefView("entity", sp.targetRefs().get(0).toString());
        // No command was issued, so no command_result can exist (resultOutcome null), and a
        // SKIPPED/FAILED view is settled by the Q1b rule (only bare/acked DISPATCHED is provisional)
        // — at the completion envelope's instant, the classifying event (v1.1.4).
        return new RunExplanation.ActionView(sp.actionType(), targetRef, null, "{}",
                outcome, cp.errorDetail(), null, true, instantOf(completedEnv), null);
    }

    // ---- outcome derivation (pure log) --------------------------------------

    /**
     * Derives one command's confirmation outcome from the chain (v1.1.2 precedence, CORE-P1):
     * {@code state_confirmed} ⇒ CONFIRMED; else the last failure-class {@code command_result}
     * ⇒ FAILED; else the last {@code "unconfirmed"} result ⇒ UNCONFIRMED carrying the recorded
     * reason verbatim; else a confirmation timeout ⇒ UNCONFIRMED; else DISPATCHED (which is where
     * a superseded- or acknowledged-only result lands — supersession is an intent change, not a
     * failure). In every branch {@code resultOutcome} carries the LAST causation-matched
     * {@code command_result}'s raw outcome — a pure fact-carry, independent of which branch
     * classified. "Last" is {@code readByCorrelation}'s stable log order; no re-sort. Since
     * v1.1.4 the CLASSIFYING envelope's instant ({@link #instantOf}) rides along as
     * {@code settledAt} — and, for CONFIRMED, as {@code confirmedAt} — the event's instant,
     * never the command's; a superseded DISPATCHED carries the superseding result's instant as
     * {@code settledAt} ({@code settledAt != null ⇔ settled}); a bare or acknowledged DISPATCHED
     * carries neither.
     */
    private Outcome deriveOutcome(EventId commandEventId, List<EventEnvelope> chain) {
        EventEnvelope confirmed = null;
        CommandResultEvent lastResult = null;
        EventEnvelope lastResultEnv = null;
        EventEnvelope lastFailure = null;
        EventEnvelope lastUnconfirmed = null;
        EventEnvelope timedOut = null;
        for (EventEnvelope e : chain) {
            switch (e.payload()) {
                case StateConfirmedEvent p -> {
                    if (p.commandEventId().equals(commandEventId)) {
                        confirmed = e;
                    }
                }
                case CommandConfirmationTimedOutEvent p -> {
                    if (p.commandEventId().equals(commandEventId)) {
                        timedOut = e;
                    }
                }
                case CommandResultEvent p -> {
                    if (commandEventId.value().equals(e.causalContext().causationId())) {
                        lastResult = p;
                        lastResultEnv = e;
                        if (isFailure(p.outcome())) {
                            lastFailure = e;
                        } else if (OUTCOME_UNCONFIRMED.equals(p.outcome())) {
                            lastUnconfirmed = e;
                        }
                    }
                }
                default -> {
                    // not a confirmation-relevant event
                }
            }
        }
        String resultOutcome = lastResult != null ? lastResult.outcome() : null;
        if (confirmed != null) {
            Instant confirmedAt = instantOf(confirmed);
            return new Outcome(RunExplanation.ActionOutcome.CONFIRMED, null, resultOutcome,
                    confirmedAt, confirmedAt);
        }
        if (lastFailure != null) {
            CommandResultEvent failure = (CommandResultEvent) lastFailure.payload();
            return new Outcome(RunExplanation.ActionOutcome.FAILED,
                    firstNonBlank(failure.failureReason(), failure.outcome()),
                    resultOutcome, instantOf(lastFailure), null);
        }
        if (lastUnconfirmed != null) {
            // The recorded reason verbatim (e.g. zigbee's), never the generic timeout text.
            CommandResultEvent unconfirmed = (CommandResultEvent) lastUnconfirmed.payload();
            return new Outcome(RunExplanation.ActionOutcome.UNCONFIRMED,
                    firstNonBlank(unconfirmed.failureReason(), unconfirmed.outcome()),
                    resultOutcome, instantOf(lastUnconfirmed), null);
        }
        if (timedOut != null) {
            return new Outcome(RunExplanation.ActionOutcome.UNCONFIRMED, "confirmation timed out",
                    resultOutcome, instantOf(timedOut), null);
        }
        // DISPATCHED: no classifying event. A superseded result settles the Q1b flag (the ledger
        // dropped the command; nothing further arrives), so that envelope's instant is settledAt —
        // settledAt != null ⇔ settled (v1.1.4, the EXPLAIN-114a R3 ruling). A bare or acknowledged
        // DISPATCHED is provisional and carries neither instant.
        boolean settledByResult = resultOutcome != null && !OUTCOME_ACKNOWLEDGED.equals(resultOutcome);
        Instant settledAt = settledByResult ? instantOf(lastResultEnv) : null;
        return new Outcome(RunExplanation.ActionOutcome.DISPATCHED, null, resultOutcome, settledAt, null);
    }

    /**
     * Failure-class test (SD-7): any outcome outside {@link #NON_FAILURE_OUTCOMES} — including
     * unknown adapter-specific strings — is failure-class. A null outcome classifies nothing.
     */
    private static boolean isFailure(String outcome) {
        return outcome != null && !NON_FAILURE_OUTCOMES.contains(outcome);
    }

    /**
     * A derived per-action outcome, its human reason, the raw result-outcome fact-carry and, since
     * v1.1.4, the classifying envelope's instant ({@code settledAt}; {@code null} for DISPATCHED)
     * and the {@code state_confirmed}'s instant ({@code confirmedAt}; {@code null} unless
     * CONFIRMED).
     */
    private record Outcome(RunExplanation.ActionOutcome value, String reason,
                           String resultOutcome, Instant settledAt, Instant confirmedAt) {

        /**
         * The Q1b settledness derivation (v1.1.2): an action is provisional exactly while it is
         * {@code DISPATCHED} with no settling record ({@code resultOutcome} absent or a bare
         * {@code "acknowledged"}). A superseded {@code DISPATCHED} is settled — the ledger
         * dropped it, nothing further will arrive. Pure derivation, never stored (INV-SA-03).
         */
        boolean settled() {
            return !(value == RunExplanation.ActionOutcome.DISPATCHED
                    && (resultOutcome == null || OUTCOME_ACKNOWLEDGED.equals(resultOutcome)));
        }
    }

    // ---- lookups ------------------------------------------------------------

    private EventEnvelope locateTriggered(RunId runId) {
        long after = 0;
        while (true) {
            EventPage page = eventStore.readByType(EventTypes.AUTOMATION_TRIGGERED, after, SCAN_BATCH);
            for (EventEnvelope e : page.events()) {
                if (e.payload() instanceof AutomationTriggeredEvent p && p.runId().equals(runId.value())) {
                    return e;
                }
            }
            if (!page.hasMore()) {
                return null;
            }
            after = page.nextPosition();
        }
    }

    private static EventEnvelope firstForRun(List<EventEnvelope> chain, String eventType,
                                             RunId runId) {
        for (EventEnvelope e : chain) {
            if (!eventType.equals(e.eventType())) {
                continue;
            }
            if (e.payload() instanceof AutomationCompletedEvent p && p.runId().equals(runId.value())) {
                return e;
            }
        }
        return null;
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * An envelope's instant for the wire (v1.1.4): its {@code eventTime} when present, else its
     * {@code ingestTime} — the same rule {@code matchedAt} uses.
     */
    private static Instant instantOf(EventEnvelope envelope) {
        return envelope.eventTime() != null ? envelope.eventTime() : envelope.ingestTime();
    }

    private static AutomationId automationOf(EventEnvelope runLifecycleEvent) {
        // The producer publishes run-lifecycle events on SubjectRef.automation(...), so the
        // automation id is the envelope subject (the payload carries none).
        return AutomationId.of(runLifecycleEvent.subjectRef().id());
    }

    private static RunExplanation.SubjectRefView subjectRefView(String type, String id) {
        return new RunExplanation.SubjectRefView(type, id);
    }

    private static RunStatus parseStatus(String name) {
        try {
            return RunStatus.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
