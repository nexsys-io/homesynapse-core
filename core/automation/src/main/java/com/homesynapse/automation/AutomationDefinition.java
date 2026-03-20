/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * The complete, validated automation definition loaded from {@code automations.yaml}.
 *
 * <p>Each automation defines a set of triggers, optional conditions, and an ordered
 * action sequence. When any trigger fires and all conditions pass, the action sequence
 * executes on a virtual thread managed by the {@link RunManager}.</p>
 *
 * <h2>Identity Stability</h2>
 *
 * <p>The {@link #automationId()} is assigned at first load and preserved across reloads
 * via the {@code automations.ids.yaml} companion file (Doc 07 §4.1). Automations are
 * matched by {@link #slug()} on reload. Removed automation names are retained in the
 * identity file for 30 days (configurable via {@code automation.identity_retention_days})
 * to allow re-addition without identity loss.</p>
 *
 * <h2>Concurrency and Execution</h2>
 *
 * <p>The {@link #mode()} governs how overlapping Runs are handled. {@code maxConcurrent}
 * is meaningful for {@link ConcurrencyMode#QUEUED} and {@link ConcurrencyMode#PARALLEL}
 * (default 10) and is fixed at 1 for {@link ConcurrencyMode#SINGLE} and
 * {@link ConcurrencyMode#RESTART}. These defaults are applied at YAML load time.</p>
 *
 * <p>The {@link #triggers()} and {@link #actions()} lists are guaranteed non-empty.
 * The {@link #conditions()} list may be empty (no guard — actions always execute on
 * trigger fire).</p>
 *
 * <p>Defined in Doc 07 §3.3, §4.1, §8.2.</p>
 *
 * @param automationId       the stable identity for this automation, never {@code null}
 * @param slug               the human-readable slug, never {@code null}
 * @param name               the display name, never {@code null}
 * @param description        optional description; {@code null} if not provided
 * @param enabled            whether this automation is active
 * @param mode               the concurrency mode, never {@code null}
 * @param maxConcurrent      maximum concurrent Runs (for QUEUED/PARALLEL modes)
 * @param maxExceededSeverity log severity when triggers are dropped, never {@code null}
 * @param priority           execution priority (-100 to 100)
 * @param triggers           the trigger definitions, unmodifiable, non-empty, never {@code null}
 * @param conditions         the condition definitions, unmodifiable, may be empty, never {@code null}
 * @param actions            the action definitions, unmodifiable, non-empty, never {@code null}
 * @see AutomationRegistry
 * @see RunManager
 * @see TriggerEvaluator
 */
public record AutomationDefinition(
        AutomationId automationId,
        String slug,
        String name,
        String description,
        boolean enabled,
        ConcurrencyMode mode,
        int maxConcurrent,
        MaxExceededSeverity maxExceededSeverity,
        int priority,
        List<TriggerDefinition> triggers,
        List<ConditionDefinition> conditions,
        List<ActionDefinition> actions
) {

    /**
     * Validates non-null fields and makes all lists unmodifiable.
     *
     * @throws NullPointerException if any non-nullable field is {@code null}
     */
    public AutomationDefinition {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(maxExceededSeverity, "maxExceededSeverity must not be null");
        Objects.requireNonNull(triggers, "triggers must not be null");
        Objects.requireNonNull(conditions, "conditions must not be null");
        Objects.requireNonNull(actions, "actions must not be null");
        triggers = List.copyOf(triggers);
        conditions = List.copyOf(conditions);
        actions = List.copyOf(actions);
    }
}
