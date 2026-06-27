/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.homesynapse.event.EventEnvelope;
import com.homesynapse.platform.identity.EntityId;

/**
 * The trigger&rarr;run handoff (M7.4b): turns the {@link StandardTriggerEvaluator}'s matched
 * triggers into root Runs by calling {@link RunManager#initiateRun} once per matched automation
 * (§1 D1 — a direct, co-located, in-process call; the engine's internal orchestration, not the
 * event-driven command hop). Package-private — it is wired into the {@code automation_engine}
 * subscriber by {@link AutomationEngineAssembly}.
 *
 * <p><strong>The derivation.</strong> {@link StandardTriggerEvaluator#evaluateMatches(EventEnvelope)}
 * already computes the immediate-match trigger indices, so {@code matchedTriggers} comes straight
 * from {@link StandardTriggerEvaluator.TriggerMatch} — never re-derived (which would duplicate the
 * matching logic and risk a silent {@code matchedTriggers}/causal-chain corruption). Per matched
 * automation this initiator: (a) looks the {@link AutomationDefinition} up by id, (b) takes the
 * matched indices from the {@code TriggerMatch}, and (c) resolves the automation's command-action
 * target selectors to entity sets ({@link RunContext#resolvedTargets()}, C4 — captured once at
 * trigger time, no re-resolution during action execution). It then calls {@code initiateRun(...,
 * RunCausalChain.root())} — these are trigger-initiated (root) Runs, not cascades.</p>
 *
 * <p><strong>Ordering (C3).</strong> Matched automations are ordered priority-descending then
 * {@code automationId}-ascending via {@link StandardRunManager#byExecutionOrder()} before the
 * {@code initiateRun} calls, so a batch triggered by one event admits deterministically.</p>
 *
 * <p><strong>What it does NOT decide.</strong> Idempotency/dedup (C2), concurrency-mode rejection,
 * cascade suppression, auto-disable, and the fail-closed-read are all the {@link RunManager}'s job
 * (it returns {@code Optional.empty()} for those). This initiator initiates and lets the FSM
 * decide; it never second-guesses the outcome.</p>
 *
 * <p><strong>Replay (D2).</strong> This initiator is only invoked in LIVE — the subscriber's
 * LIVE-only guard keeps the run pipeline from re-firing on recovery. Thread-safe and stateless
 * apart from its injected (thread-safe) collaborators.</p>
 */
final class RunInitiator {

    /** Selector-position key prefix for {@link RunContext#resolvedTargets()} (the "position" form). */
    private static final String TARGET_KEY_PREFIX = "action:";

    private final RunManager runManager;
    private final StandardAutomationRegistry registry;
    private final SelectorResolver selectorResolver;

    /**
     * Constructs the initiator over the run pipeline's entry point and the lookups it needs.
     *
     * @param runManager       the run-lifecycle FSM that admits a Run, never {@code null}
     * @param registry         resolves a matched {@code AutomationId} to its definition, never
     *                         {@code null}
     * @param selectorResolver resolves command-action target selectors for {@code resolvedTargets},
     *                         never {@code null}
     */
    RunInitiator(RunManager runManager, StandardAutomationRegistry registry,
                 SelectorResolver selectorResolver) {
        this.runManager = Objects.requireNonNull(runManager, "runManager");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.selectorResolver = Objects.requireNonNull(selectorResolver, "selectorResolver");
    }

    /**
     * Initiates exactly one root Run per matched automation, in C3 execution order.
     *
     * @param matches         the evaluator's matched automations with their immediate-match
     *                        trigger indices, never {@code null}
     * @param triggeringEvent the event that produced the matches, never {@code null}
     */
    void initiateRuns(List<StandardTriggerEvaluator.TriggerMatch> matches,
                      EventEnvelope triggeringEvent) {
        Objects.requireNonNull(matches, "matches");
        Objects.requireNonNull(triggeringEvent, "triggeringEvent");
        if (matches.isEmpty()) {
            return;
        }
        // Resolve definitions (dropping any since-removed automation), then order by C3 so a
        // batch admits deterministically regardless of the match source's ordering.
        List<Matched> ordered = new ArrayList<>(matches.size());
        for (StandardTriggerEvaluator.TriggerMatch match : matches) {
            registry.get(match.automationId()).ifPresent(definition ->
                    ordered.add(new Matched(definition, match.matchedTriggerIndices())));
        }
        ordered.sort(Comparator.comparing(Matched::definition, StandardRunManager.byExecutionOrder()));
        for (Matched matched : ordered) {
            runManager.initiateRun(
                    matched.definition(),
                    triggeringEvent,
                    matched.matchedIndices(),
                    resolveTargets(matched.definition()),
                    RunCausalChain.root());
        }
    }

    /**
     * Resolves the automation's top-level command-action target selectors at trigger time (C4),
     * keyed by action position ({@code "action:" + index}). Non-command top-level actions
     * contribute no command target and are absent from the map. The resolved sets are exactly
     * what the action layer will act upon; the executor re-resolves independently, so this map is
     * the durable trigger-time record carried on {@code automation_triggered} for explainability.
     */
    private Map<String, Set<EntityId>> resolveTargets(AutomationDefinition automation) {
        Map<String, Set<EntityId>> targets = new LinkedHashMap<>();
        List<ActionDefinition> actions = automation.actions();
        for (int index = 0; index < actions.size(); index++) {
            if (actions.get(index) instanceof CommandAction command) {
                targets.put(TARGET_KEY_PREFIX + index,
                        Set.copyOf(selectorResolver.resolve(command.target())));
            }
        }
        return targets;
    }

    /** A matched automation paired with its immediate-match trigger indices. */
    private record Matched(AutomationDefinition definition, List<Integer> matchedIndices) {
    }
}
