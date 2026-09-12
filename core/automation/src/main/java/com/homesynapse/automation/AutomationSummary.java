/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import java.util.List;
import java.util.Objects;

import com.homesynapse.platform.identity.AutomationId;

/**
 * One element of the {@code GET /api/v1/automations} list (the frozen v1.1 §B3 list shape).
 * Assembled by {@link ExplanationService#listAutomations()} from the {@link AutomationRegistry}
 * (the in-memory single source of truth for definitions, DP-B3) plus a best-effort most-recent-run
 * lookup ({@link #lastRunId}, nullable). Pure projection: never persisted, mints no event.
 *
 * <p>{@link #components} is a <strong>thin derived presentation summary</strong> off the
 * definition's existing {@code triggers()}/{@code conditions()}/{@code actions()} — one
 * {@link ComponentView} per trigger, condition, and action. It is <em>not</em> the Doc-16 §4
 * {@code AutomationComponent}/{@code ComponentRef}/{@code ComputedValue} component model (that is
 * out of V1 scope): there is no common accessor on the sealed {@code TriggerDefinition}/
 * {@code ConditionDefinition}/{@code ActionDefinition} interfaces, so each component's
 * {@link ComponentView#type()} is the concrete record's simple name and its
 * {@link ComponentView#summary()} is a short human rendering of that kind.</p>
 *
 * @param automationId the automation identity; never {@code null}
 * @param name         the display name; never {@code null}
 * @param enabled      whether the automation is currently enabled
 * @param components   the per-trigger/condition/action presentation summaries; never {@code null}
 * @param lastRunId    the most-recent terminal Run for this automation, or {@code null} if it has never run
 * @param definitionKey the v1.1.4 (EXPLAIN-114a) stable definition key: {@code DefinitionHashes}
 *                     over the registry's current definition (DP-5) — the SAME SHA-256 hex the
 *                     engine stamps on {@code automation_triggered.definitionHash}, so it equals
 *                     the causal chain's and the non-firing read's {@code definitionKey} for the
 *                     same definition; no store read. Nullable by contract; NOT null-checked
 */
public record AutomationSummary(
        AutomationId automationId,
        String name,
        boolean enabled,
        List<ComponentView> components,
        RunId lastRunId,
        String definitionKey) {

    /**
     * Validates non-nullable components and makes {@link #components} unmodifiable.
     * {@code lastRunId} is intentionally nullable (an automation that has never run);
     * {@code definitionKey} is nullable by contract and NOT null-checked.
     *
     * @throws NullPointerException if {@code automationId}, {@code name}, or {@code components} is {@code null}
     */
    public AutomationSummary {
        Objects.requireNonNull(automationId, "automationId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(components, "components must not be null");
        components = List.copyOf(components);
    }

    /**
     * Convenience constructor for the pre-v1.1.4 five-component form (test-convenience;
     * production constructs the canonical form): delegates with {@code definitionKey = null}
     * (validation lives ONLY in the canonical constructor).
     */
    public AutomationSummary(AutomationId automationId, String name, boolean enabled,
                             List<ComponentView> components, RunId lastRunId) {
        this(automationId, name, enabled, components, lastRunId, null);
    }

    /**
     * A thin derived view of one automation component (trigger, condition, or action).
     *
     * @param type    a stable kind label — the concrete definition record's simple name
     *                (e.g. {@code "StateChangeTrigger"}); never {@code null}
     * @param summary a short human rendering of that kind (e.g. {@code "state change trigger"}); never {@code null}
     * @param ref     the v1.1.3 entity reference (CG-1, DP-2): the {@code {type:"entity", id}}
     *                view of the ONE entity this component addresses by identity — a
     *                {@code DirectRefSelector} on a trigger, condition, or command action, or a
     *                {@code CalendarTrigger}'s calendar entity — or {@code null} for a group
     *                selector (the automation names a SET), a device-addressed trigger, a
     *                compound condition / branch (never descended), and every component with no
     *                selector at all; never a fabricated id (D5). Nullable by contract; NOT
     *                null-checked
     */
    public record ComponentView(String type, String summary, RunExplanation.SubjectRefView ref) {

        /**
         * @throws NullPointerException if {@code type} or {@code summary} is {@code null}
         */
        public ComponentView {
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(summary, "summary must not be null");
        }

        /**
         * Convenience constructor for the pre-v1.1.3 two-component form (test-convenience;
         * production constructs the canonical form): delegates with {@code ref = null}.
         */
        public ComponentView(String type, String summary) {
            this(type, summary, null);
        }
    }
}
