/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.DegradedAttributeValue;

/**
 * Package-private helpers for the run-init computed-value resolution seam (Doc 16 §3.2):
 * resolving the {@link ComputedValue}s occupying action value positions into concrete values
 * <em>before</em> the actions reach the frozen {@link ActionExecutor}, and the shared
 * typed-absent sentinel the permits return for absent inputs.
 *
 * <p>Mirrors the {@link AttributeValues}/{@code DefinitionHashes} same-package helper pattern.
 * Resolution is side-effect-free: it reads only the supplied {@link ComputedValueContext}
 * (the captured trigger-time snapshot + run-init time) and replaces matched values, leaving
 * everything else untouched.</p>
 */
final class ComputedValues {

    private ComputedValues() {
        // Utility class — non-instantiable.
    }

    /**
     * Returns the action list with every {@link ComputedValue} in a {@link CommandAction}'s
     * parameter value positions resolved to a concrete value against {@code ctx} (Doc 16 §3.2).
     *
     * <p><strong>Scope (V1 minimal).</strong> Only top-level {@link CommandAction} parameters
     * are resolved; other action types (and actions nested inside a
     * {@link ConditionBranchAction}) are returned unchanged — computed values in those
     * positions are deferred with the loader/component model. An action carrying no
     * {@code ComputedValue} is returned as-is (no copy), so production traffic — which loads no
     * computed values in V1 — is unaffected.</p>
     *
     * @param actions the Run's action list, never {@code null}
     * @param ctx     the run-init resolution context, never {@code null}
     * @return a list in which command-action computed parameters are concrete, never {@code null}
     */
    static List<ActionDefinition> resolveActions(List<ActionDefinition> actions,
                                                 ComputedValueContext ctx) {
        List<ActionDefinition> resolved = new ArrayList<>(actions.size());
        for (ActionDefinition action : actions) {
            resolved.add(resolveAction(action, ctx));
        }
        return resolved;
    }

    private static ActionDefinition resolveAction(ActionDefinition action,
                                                  ComputedValueContext ctx) {
        if (!(action instanceof CommandAction command)) {
            return action;   // only CommandAction parameters carry computed values in V1
        }
        Map<String, Object> resolvedParameters = resolveParameters(command.parameters(), ctx);
        if (resolvedParameters == null) {
            return command;   // no ComputedValue present — return unchanged (no copy)
        }
        return new CommandAction(command.target(), command.commandName(), resolvedParameters,
                command.onUnavailable());
    }

    /**
     * Returns a copy of {@code parameters} with each {@link ComputedValue} value resolved, or
     * {@code null} when no value is a {@link ComputedValue} (the no-op fast path).
     */
    private static Map<String, Object> resolveParameters(Map<String, Object> parameters,
                                                         ComputedValueContext ctx) {
        Map<String, Object> resolved = null;
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            if (entry.getValue() instanceof ComputedValue computed) {
                if (resolved == null) {
                    resolved = new LinkedHashMap<>(parameters);
                }
                resolved.put(entry.getKey(), computed.resolve(ctx));
            }
        }
        return resolved;
    }

    /**
     * The shared typed-absent sentinel a {@link ComputedValue} resolves to when an input is
     * absent or a fold is undefined over an empty selection. A {@link DegradedAttributeValue}
     * is the hierarchy's "value could not be produced" marker — it keeps resolution total and
     * non-{@code null} (C-SA-2), is self-describing via {@code reason}, and is never written to
     * canonical state under strict mode (AMD-47-INV-04).
     *
     * @param reason the Register-C explanation of why no concrete value was produced, never
     *               blank
     * @return a degraded sentinel carrying {@code reason}, never {@code null}
     */
    static AttributeValue absent(String reason) {
        return new DegradedAttributeValue("ComputedValue", "", reason);
    }
}
