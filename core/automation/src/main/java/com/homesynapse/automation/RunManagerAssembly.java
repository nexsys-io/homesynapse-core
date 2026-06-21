/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.time.Clock;
import java.util.Objects;

import com.homesynapse.event.EventPublisher;

/**
 * Public assembly seam that builds the {@link RunManager} FSM for the composition root
 * (AB-3 lifecycle) without promoting the concrete {@link StandardRunManager} to public.
 *
 * <p>{@code StandardRunManager} is package-private — it is coupled to the package-private
 * {@link RunConditionGate}/{@link ActionExecutor} seams and the automation event vocabulary
 * and must live in this module. The composition root constructs the FSM's plain-value
 * dependencies — an {@link EventPublisher}, the injected {@link ActionExecutor} and
 * {@link RunConditionGate} seams, an injected {@link Clock} (§4c), and a
 * {@link RunManagerConfig} read from {@code homesynapse.yaml} ({@code core -> config} is
 * banned, so the FSM never sees a {@code ConfigurationService}) — and calls
 * {@link #runManager(EventPublisher, ActionExecutor, RunConditionGate, Clock, RunManagerConfig)}
 * to obtain a {@link RunManager}.</p>
 *
 * <p>Mirrors {@link AutomationEngineAssembly}: only the interface type crosses the module
 * boundary; the concrete FSM and its internals stay package-private.</p>
 */
public final class RunManagerAssembly {

    private RunManagerAssembly() {
        // Static seam — no instantiation.
    }

    /**
     * Builds the run-lifecycle FSM and returns it typed as the {@link RunManager} contract.
     *
     * @param publisher      the durable event publish surface, never {@code null}
     * @param actionExecutor the RUNNING-state action executor, never {@code null}
     * @param conditionGate  the EVALUATING-state condition gate, never {@code null}
     * @param clock          the injected clock (§4c), never {@code null}
     * @param config         the cascade + auto-disable parameters, never {@code null}
     * @return the run-lifecycle {@link RunManager}; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static RunManager runManager(EventPublisher publisher, ActionExecutor actionExecutor,
                                        RunConditionGate conditionGate, Clock clock,
                                        RunManagerConfig config) {
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(actionExecutor, "actionExecutor");
        Objects.requireNonNull(conditionGate, "conditionGate");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(config, "config");
        return new StandardRunManager(publisher, actionExecutor, conditionGate, clock, config);
    }
}
