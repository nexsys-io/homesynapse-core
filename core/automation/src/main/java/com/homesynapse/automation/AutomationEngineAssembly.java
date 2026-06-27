/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import com.homesynapse.event.bus.Subscriber;

import java.util.Objects;

/**
 * Public assembly seam that exposes the {@code automation_engine} bus subscriber
 * to the composition root (AB-3) without promoting the concrete subscriber to
 * public.
 *
 * <p>{@code AutomationEngineSubscriber} is intentionally package-private: it is
 * tightly coupled to {@code StandardTriggerEvaluator}'s package-private
 * REPLAY-timer seams ({@code setReplayMode}/{@code isReplayMode}/
 * {@code rebuildTimer}/{@code TimerKey}) and to the package-private
 * {@link RunInitiator}, so it cannot live in another module. The composition root
 * (lifecycle/{@code HomeSynapseCore}) builds the evaluator chain and the run pipeline
 * and calls
 * {@link #automationEngineSubscriber(StandardTriggerEvaluator, RunManager, StandardAutomationRegistry, SelectorResolver)}
 * to obtain a {@link Subscriber} it can register via
 * {@code EventBus.subscribeRuntime(SubscriberInfo, Subscriber)} — only after the
 * state projection has reached {@code LIVE} (the catch-up ordering invariant:
 * automations must not evaluate against partially-replayed state).</p>
 *
 * <p><strong>M7.4b — the producer goes live.</strong> The seam now also takes the
 * {@link RunManager}, the {@link StandardAutomationRegistry} (definition lookup), and the
 * {@link SelectorResolver} (target resolution) so the subscriber can drive the trigger&rarr;run
 * handoff: a matched trigger initiates one root Run per matched automation (a direct, co-located,
 * in-process {@code initiateRun} call — §1 D1). The concrete subscriber and the package-private
 * {@link RunInitiator} stay off the exported API; only the {@code Subscriber} interface crosses
 * the boundary. {@link RunManager}/{@link StandardAutomationRegistry}/{@link SelectorResolver} are
 * already this module's public types, so no new module edge is introduced.</p>
 *
 * <h2>JPMS note</h2>
 *
 * <p>The return type {@link Subscriber} (from {@code com.homesynapse.event.bus})
 * is now part of this module's exported API. Per the {@code -Xlint:exports}
 * authoring rule (api ↔ {@code requires transitive}), {@code module-info}
 * declares {@code requires transitive com.homesynapse.event.bus} and
 * {@code build.gradle.kts} uses {@code api(":core:event-bus")}. The concrete
 * {@code AutomationEngineSubscriber} class and its event.bus-coupled internals
 * stay off the exported API; only the {@code Subscriber} interface is exposed
 * here.</p>
 */
public final class AutomationEngineAssembly {

    private AutomationEngineAssembly() {
        // Static seam — no instantiation.
    }

    /**
     * Wraps the trigger evaluator and the run pipeline in the {@code automation_engine} bus
     * subscriber and returns it typed as the event-bus {@link Subscriber} contract.
     *
     * <p>The returned subscriber translates the bus's per-subscriber lifecycle
     * (COLD → REPLAY → TRANSITION → LIVE, driven by {@code setMode}/{@code onCaughtUp}) onto the
     * evaluator's replay-suppression behavior — no duration timers are started during REPLAY, and
     * accumulated timers are rebuilt at the REPLAY → LIVE transition — and, in LIVE only (D2),
     * drives a matched trigger into one root Run per matched automation via {@code runManager}.</p>
     *
     * @param evaluator        the production trigger evaluator (the head of the
     *                         registry → resolver → evaluator chain); never {@code null}
     * @param runManager       the run-lifecycle FSM the matched triggers drive; never {@code null}
     * @param registry         the definition registry used to resolve a matched {@code AutomationId}
     *                         to its {@link AutomationDefinition}; never {@code null}
     * @param selectorResolver resolves command-action target selectors for the Run's
     *                         {@code resolvedTargets}; never {@code null}
     * @return the {@code automation_engine} subscriber; never {@code null}
     */
    public static Subscriber automationEngineSubscriber(StandardTriggerEvaluator evaluator,
                                                        RunManager runManager,
                                                        StandardAutomationRegistry registry,
                                                        SelectorResolver selectorResolver) {
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(runManager, "runManager");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(selectorResolver, "selectorResolver");
        return new AutomationEngineSubscriber(evaluator,
                new RunInitiator(runManager, registry, selectorResolver));
    }
}
