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
 * {@code rebuildTimer}/{@code TimerKey}), so it cannot live in another module.
 * The composition root (lifecycle/{@code HomeSynapseCore}) builds the evaluator
 * chain and calls {@link #automationEngineSubscriber(StandardTriggerEvaluator)}
 * to obtain a {@link Subscriber} it can register via
 * {@code EventBus.subscribeRuntime(SubscriberInfo, Subscriber)} — only after the
 * state projection has reached {@code LIVE} (the catch-up ordering invariant:
 * automations must not evaluate against partially-replayed state).</p>
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
     * Wraps the trigger evaluator in the {@code automation_engine} bus
     * subscriber and returns it typed as the event-bus {@link Subscriber}
     * contract.
     *
     * <p>The returned subscriber translates the bus's per-subscriber lifecycle
     * (COLD → REPLAY → TRANSITION → LIVE, driven by {@code setMode}/
     * {@code onCaughtUp}) onto the evaluator's replay-suppression behavior: no
     * duration timers are started during REPLAY, and accumulated timers are
     * rebuilt at the REPLAY → LIVE transition.</p>
     *
     * @param evaluator the production trigger evaluator (the head of the
     *                  registry → resolver → evaluator chain); never {@code null}
     * @return the {@code automation_engine} subscriber; never {@code null}
     */
    public static Subscriber automationEngineSubscriber(StandardTriggerEvaluator evaluator) {
        Objects.requireNonNull(evaluator, "evaluator");
        return new AutomationEngineSubscriber(evaluator);
    }
}
